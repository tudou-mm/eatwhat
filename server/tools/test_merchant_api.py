# -*- coding: utf-8 -*-
"""
商家端接口回归测试。

重点验证四件事：
1. 出参是**视图结构**（shop.stats.views 这种嵌套），不是裸实体 —— 前端读的就是这个。
2. 商家能 bootstrap 出**自己的店**，哪怕它还在 pending / rejected ——
   这正是 /client/bootstrap 做不到的（那个接口只给「客户端可见」的店）。
3. 两条最硬的业务规则在商家端真的拦得住：
   发布冷却 / 日上限，以及「内容形式发布后不可改」。
4. 下架是**逻辑下架**，不是物理删除，且可恢复。

用法：
    python test_merchant_api.py [base]
默认 base = http://127.0.0.1:8080

可用环境变量 MERCHANT_SHOP_ID 指定要测的店，默认自动挑一家
「今天还没发布过」的在营店，避免一上来就被冷却拦住。

注意：本脚本会发布菜品、改动店铺资料、插入一家待审核店 —— 均不可逆。
跑完请重启后端回到初始演示数据。
"""
import json
import os
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
PASS = FAIL = 0
FAILURES = []

# 商家端接口全部需要 merchant token（见 server 的 AuthInterceptor）
TOKEN = None
DEMO_PASSWORD = "123456"


def login(account, role="merchant", password=None):
    """
    登录拿 JWT。商家 account 可以是店铺 id，也可以是店内电话。
    不写全局 TOKEN —— 主流程要先以 admin 身份读店铺清单、
    再以商家身份跑用例，由调用方决定当前用哪张。
    """
    ok, d = call("POST", "/api/auth/login",
                 {"role": role, "account": account,
                  "password": password if password is not None else DEMO_PASSWORD},
                 auth=False)
    if not ok:
        raise SystemExit("登录失败（%s / %s）：%s" % (role, account, d))
    return d["token"]


def call(method, path, body=None, auth=True, token=None):
    """返回 (ok, data_or_msg)。auth=False 时不带 token（用于测鉴权本身）"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    tk = token if token is not None else (TOKEN if auth else None)
    if tk:
        req.add_header("Authorization", "Bearer " + tk)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            j = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            j = json.loads(e.read().decode("utf-8"))
            return False, j.get("msg") or ("HTTP %s" % e.code)
        except Exception:
            return False, "HTTP %s" % e.code
    except Exception as e:
        return False, str(e)
    if j.get("code") != 0:
        return False, j.get("msg")
    return True, j.get("data")


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  [FAIL] %s   %s" % (name, detail))


def main():
    global TOKEN
    print("== 商家端接口测试  %s ==" % BASE)

    # ---------- 0-a. 鉴权：没 token 必须进不来 ----------
    print("\n[0] 鉴权")
    ok, msg = call("GET", "/api/merchant/bootstrap?shopId=s_001", auth=False)
    check("无 token 打商家端被 401 拦下", (not ok) and "未登录" in str(msg), str(msg)[:80])

    # ---------- 0-b. 准备：以 admin 身份读店铺清单，挑出各类测试对象 ----------
    admin_tok = login("admin", role="admin", password="admin123")
    ok, admin = call("GET", "/api/admin/bootstrap", token=admin_tok)
    if not ok:
        print("  后端不可用：%s" % admin)
        return

    shops = admin["shops"]
    normal = [s for s in shops if s["status"] == "normal"]
    postable = [s for s in normal if s["canPostToday"]]
    cooldown = [s for s in normal if not s["canPostToday"]]
    pendings = [s for s in shops if s["status"] == "pending"]

    forced = os.environ.get("MERCHANT_SHOP_ID")
    if forced:
        target = next((s for s in normal if s["id"] == forced), None)
    else:
        target = postable[0] if postable else (normal[0] if normal else None)

    print("  在营 %d 家 / 其中今日可发 %d 家 / 待审核 %d 家"
          % (len(normal), len(postable), len(pendings)))
    if not target:
        print("  [x] 没有可用于测试的在营店铺")
        return
    print("  测试店：%s (%s)" % (target["name"], target["id"]))
    SID = target["id"]

    # 换成商家身份：后面的接口都带这张 token，且只能操作 SID 这一家
    TOKEN = login(SID)
    check("以测试店身份登录成功", bool(TOKEN))

    # 越权：拿本店 token 去读别家店，必须 403
    other = next((s["id"] for s in normal if s["id"] != SID), None)
    if other:
        ok, msg = call("GET", "/api/merchant/bootstrap?shopId=" + other)
        check("拿本店 token 读别家店被 403 拦下",
              (not ok) and ("本店" in str(msg) or "无权" in str(msg)), str(msg)[:80])

    # ---------- 1. bootstrap 结构 ----------
    print("\n[1] /merchant/bootstrap 结构")
    ok, d = call("GET", "/api/merchant/bootstrap?shopId=" + SID)
    if not ok:
        check("bootstrap 可访问", False, str(d))
        return

    for k in ["shop", "dishes", "comments", "config",
              "canPostToday", "cooldownSeconds", "intervalHours", "dailyLimit"]:
        check("顶层键 %s 存在" % k, k in d)

    check("config 含 priceTiers/tasteTags/cuisines/publishRule",
          all(k in d["config"] for k in ["priceTiers", "tasteTags", "cuisines", "publishRule"]))
    check("bootstrap 里的店就是请求的店",
          d["shop"]["id"] == SID, "得到 %s" % d["shop"].get("id"))

    # ---------- 2. 视图嵌套 ----------
    print("\n[2] 视图结构（前端读嵌套，实体是扁平）")
    shop = d["shop"]
    check("shop.stats.views 是数字", isinstance(shop.get("stats", {}).get("views"), int))
    check("shop 不是裸实体（没有扁平 statViews）", "statViews" not in shop)
    check("shop 带审核痕迹字段",
          all(k in shop for k in ["status", "submittedAt", "reviewedAt", "reviewer", "rejectReason"]))
    if d["dishes"]:
        dd = d["dishes"][0]
        check("dish.stats.likes 是数字", isinstance(dd.get("stats", {}).get("likes"), int))
        check("dish 不是裸实体（没有扁平 statLikes）", "statLikes" not in dd)

    # ---------- 3. 待审核店也能 bootstrap ----------
    print("\n[3] 待审核店也能 bootstrap（客户端接口做不到这点）")
    if pendings:
        pid = pendings[0]["id"]
        # 换成这家待审核店的身份再读 —— 拿 s_001 的 token 读别家店会 403（那是对的）
        prev = TOKEN
        TOKEN = login(pid)
        check("待审核店也能登录（否则进不了自己的后台）", bool(TOKEN))
        ok, pd = call("GET", "/api/merchant/bootstrap?shopId=" + pid)
        check("pending 店 bootstrap 成功", ok, str(pd))
        if ok:
            check("返回的正是 pending 状态", pd["shop"]["status"] == "pending",
                  "得到 %s" % pd["shop"].get("status"))
            check("pending 店菜品为空", len(pd["dishes"]) == 0)
        TOKEN = prev
        ok, cd = call("GET", "/api/client/bootstrap")
        in_client = pid in [s["id"] for s in cd["shops"]] if ok else False
        check("pending 店不在客户端可见列表", not in_client)
    else:
        print("  (跳过：没有待审核店铺)")

    # ---------- 4. 发布冷却拦截 ----------
    print("\n[4] 发布冷却拦截")
    if cooldown:
        cs = cooldown[0]
        # 同样要换成「处于冷却中的那家店」的身份。
        # 拿 SID 的 token 去发 cs 的菜不会 403 —— 服务端会把 shopId 强制改成 SID，
        # 结果变成「往自己店发了一条」，把 SID 也拖进冷却，后面的用例全崩。
        prev = TOKEN
        TOKEN = login(cs["id"])
        ok, r = call("POST", "/api/merchant/dish", {
            "shopId": cs["id"], "name": "冷却测试菜", "type": "image",
            "media": ["https://picsum.photos/seed/cooldown/800/1200"], "price": 30
        })
        check("冷却中的店发布被拒", not ok, "居然成功了")
        check("拒绝原因可读（提到小时/上限）",
              (not ok) and any(w in str(r) for w in ["小时", "上限", "等待"]), str(r))
        TOKEN = prev
    else:
        print("  (跳过：没有处于冷却中的店)")

    # ---------- 5. 发布成功 + 价格档自动归档 ----------
    print("\n[5] 发布菜品")
    ok, r = call("POST", "/api/merchant/dish", {
        "shopId": SID, "name": "回归测试菜", "desc": "自动化测试用",
        "type": "image", "media": ["https://picsum.photos/seed/reg/800/1200"],
        "price": 68, "tasteTags": ["麻辣"]
    })
    check("发布成功", ok, str(r))
    new_dish = r if ok else None
    if new_dish:
        check("价格 68 自动归入高等档", new_dish["priceTierId"] == "tier_high",
              "得到 %s" % new_dish.get("priceTierId"))
        check("新菜默认待核实标签", new_dish["realTag"] == "pending")
        check("新菜状态为 normal", new_dish["status"] == "normal")
        check("返回视图结构（含 stats）", "stats" in new_dish and "statViews" not in new_dish)

        ok, cd = call("GET", "/api/client/bootstrap")
        check("客户端立刻能看到这道新菜",
              ok and any(x["id"] == new_dish["id"] for x in cd["dishes"]))

    # 发布后这家店应进入冷却
    ok, bp = call("GET", "/api/merchant/bootstrap?shopId=" + SID)
    if ok:
        check("发布后 canPostToday 变 false", bp["canPostToday"] is False)
        check("发布后 cooldownSeconds > 0", bp["cooldownSeconds"] > 0,
              "得到 %s" % bp["cooldownSeconds"])
        ok, r = call("POST", "/api/merchant/dish", {
            "shopId": SID, "name": "连发第二道", "type": "image",
            "media": ["https://picsum.photos/seed/again/800/1200"]
        })
        check("紧接着再发一次被拒（冷却真的生效）", not ok, "居然成功了")

    # ---------- 6. 媒体与必填校验 ----------
    print("\n[6] 发布校验")
    ok, r = call("POST", "/api/merchant/dish", {
        "shopId": SID, "name": "没有媒体的菜", "type": "image", "media": []
    })
    check("无媒体被拒", not ok, "居然成功了")

    ok, r = call("POST", "/api/merchant/dish", {
        "shopId": SID, "name": "图太多", "type": "image",
        "media": ["https://picsum.photos/seed/x%d/800/1200" % i for i in range(10)]
    })
    check("图文超过 9 张被拒", not ok, "居然成功了")

    ok, r = call("POST", "/api/merchant/dish", {
        "shopId": SID, "name": "类型不对", "type": "audio",
        "media": ["https://picsum.photos/seed/a/800/1200"]
    })
    check("非法内容形式被拒", not ok, "居然成功了")

    # ---------- 7. 编辑菜品 ----------
    print("\n[7] 编辑菜品")
    if new_dish:
        did = new_dish["id"]
        ok, r = call("PUT", "/api/merchant/dish/" + did, {
            "name": "回归测试菜（改名）", "type": "video"
        })
        check("改内容形式被拒", not ok, "居然成功了")

        ok, r = call("PUT", "/api/merchant/dish/" + did, {
            "name": "回归测试菜（改名）", "price": 200
        })
        check("编辑成功", ok, str(r))
        if ok:
            check("改价后档位重新归档为超高级", r["priceTierId"] == "tier_ultra",
                  "得到 %s" % r.get("priceTierId"))
            check("改名生效", r["name"] == "回归测试菜（改名）")
            check("类型保持不变", r["type"] == "image")

    # ---------- 8. 下架 / 恢复 ----------
    print("\n[8] 下架与恢复（逻辑下架，不物理删除）")
    if new_dish:
        did = new_dish["id"]
        ok, r = call("POST", "/api/merchant/dish/%s/remove" % did, {"shopId": SID})
        check("下架成功", ok, str(r))
        if ok:
            check("状态变为 removed", r["status"] == "removed")

        ok, cd = call("GET", "/api/client/bootstrap")
        check("下架后客户端看不到它",
              ok and not any(x["id"] == did for x in cd["dishes"]))

        ok, bp = call("GET", "/api/merchant/bootstrap?shopId=" + SID)
        check("商家端仍能查到已下架的菜（下架≠删除）",
              ok and any(x["id"] == did for x in bp["dishes"]))

        ok, r = call("POST", "/api/merchant/dish/%s/restore" % did, {"shopId": SID})
        check("恢复成功", ok, str(r))
        if ok:
            check("状态回到 normal", r["status"] == "normal")

        ok, cd = call("GET", "/api/client/bootstrap")
        check("恢复后客户端又能看到",
              ok and any(x["id"] == did for x in cd["dishes"]))

    # ---------- 9. 越权校验 ----------
    print("\n[9] 越权校验（只能动自己店的菜）")
    other = next((s for s in normal if s["id"] != SID), None)
    if new_dish and other:
        did = new_dish["id"]
        ok, r = call("POST", "/api/merchant/dish/%s/remove" % did, {"shopId": other["id"]})
        check("用别家的 shopId 下架被拒", not ok, "居然成功了")
        check("拒绝原因提到「本店」", (not ok) and "本店" in str(r), str(r))

    # ---------- 10. 店铺资料 ----------
    print("\n[10] 店铺资料更新")
    ok, r = call("PUT", "/api/merchant/shop/" + SID, {
        "intro": "【回归测试写入的简介】"
    })
    check("保存店铺资料成功", ok, str(r))
    if ok:
        check("返回视图结构", "stats" in r and "statViews" not in r)
        check("简介已更新", r["intro"] == "【回归测试写入的简介】")
        ok, cd = call("GET", "/api/client/bootstrap")
        hit = next((s for s in cd["shops"] if s["id"] == SID), None) if ok else None
        check("客户端立刻能看到新简介",
              bool(hit) and hit["intro"] == "【回归测试写入的简介】",
              str(hit.get("intro") if hit else "店铺未找到"))

    # ---------- 11. 评论与回评 ----------
    print("\n[11] 评论与回评")
    ok, cs = call("GET", "/api/merchant/comments/" + SID)
    check("评论列表可访问", ok, str(cs))
    if ok and cs:
        c0 = cs[0]
        check("评论是视图结构（reply 为嵌套对象或 null）",
              "reply" in c0 and isinstance(c0["reply"], (dict, type(None))))
        check("评论带 dishName（商家端要显示是哪道菜）", bool(c0.get("dishName")))
        check("评论不是裸实体（没有 replyContent 扁平字段）", "replyContent" not in c0)

        cid = c0["id"]
        ok, r = call("POST", "/api/merchant/comment/%s/reply" % cid, {"content": "感谢反馈，欢迎再来！"})
        check("回评成功", ok, str(r))
        if ok:
            check("回评内容写入", (r.get("reply") or {}).get("content") == "感谢反馈，欢迎再来！")
            check("回评带时间", bool((r.get("reply") or {}).get("at")))

        ok, r = call("DELETE", "/api/merchant/comment/%s/reply" % cid)
        check("清除回评成功", ok, str(r))
        if ok:
            check("清除后 reply 为 null", r.get("reply") is None)
    else:
        print("  (跳过：该店暂无评论)")

    # ---------- 12. 入驻申请 ----------
    print("\n[12] 入驻申请")
    ok, cd_before = call("GET", "/api/client/bootstrap")
    n_before = len(cd_before["shops"]) if ok else 0

    ok, r = call("POST", "/api/merchant/apply", {
        "name": "回归测试小馆", "phone": "13900001111", "cuisine": "川菜",
        "city": "仪征市", "district": "真州镇", "address": "仪征市真州镇回归路 1 号",
        "hours": "10:00 - 22:00", "intro": "自动化测试提交"
    })
    check("入驻申请成功", ok, str(r))
    if ok:
        new_id = r["id"]
        check("状态为 pending", r["status"] == "pending", "得到 %s" % r.get("status"))
        check("distance 为 null（给 0 会插到附近榜首）", r["distance"] is None,
              "得到 %s" % r.get("distance"))
        check("lat/lng 为 null", r["lat"] is None and r["lng"] is None)
        check("未过审不能发布", r["canPostToday"] is False)
        check("提交时间已写入", bool(r.get("submittedAt")))

        ok, cd_after = call("GET", "/api/client/bootstrap")
        check("客户端可见店数不变（待审核店不露出）",
              ok and len(cd_after["shops"]) == n_before,
              "前 %d / 后 %d" % (n_before, len(cd_after["shops"]) if ok else -1))

        ok, bp = call("GET", "/api/merchant/bootstrap?shopId=" + new_id)
        check("新店不能拿别家 token 看自己的状态（越权会被 403）",
              (not ok) and ("本店" in str(bp) or "无权" in str(bp)), str(bp)[:80])
        # 换成新店自己的身份，才应该看得到自己的审核状态
        prev = TOKEN
        TOKEN = login(new_id)
        ok, bp = call("GET", "/api/merchant/bootstrap?shopId=" + new_id)
        check("新店自己能看自己的审核状态", ok and bp["shop"]["status"] == "pending")
        TOKEN = prev

        admin_tok = login("admin", role="admin", password="admin123")
        ok, ad = call("GET", "/api/admin/bootstrap", token=admin_tok)
        check("平台端待审核队列里出现这家店",
              ok and any(s["id"] == new_id for s in ad["pendingShops"]))

    # 必填校验
    ok, r = call("POST", "/api/merchant/apply", {"phone": "13900002222"})
    check("缺店铺名被拒", not ok, "居然成功了")
    ok, r = call("POST", "/api/merchant/apply", {"name": "没电话的店"})
    check("缺联系电话被拒", not ok, "居然成功了")

    # ---------- 汇总 ----------
    print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - " + f)
    else:
        print("提示：本脚本会发布菜品、改店铺资料、插入待审核店，重启后端即可回到初始数据。")


if __name__ == "__main__":
    main()
    sys.exit(1 if FAIL else 0)
