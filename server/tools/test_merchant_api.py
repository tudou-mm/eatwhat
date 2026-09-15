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


def call(method, path, body=None):
    """返回 (ok, data_or_msg)"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
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
    print("== 商家端接口测试  %s ==" % BASE)

    # ---------- 0. 准备：从平台端拿全量店铺，挑出各类测试对象 ----------
    ok, admin = call("GET", "/api/admin/bootstrap")
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
        ok, pd = call("GET", "/api/merchant/bootstrap?shopId=" + pid)
        check("pending 店 bootstrap 成功", ok, str(pd))
        if ok:
            check("返回的正是 pending 状态", pd["shop"]["status"] == "pending",
                  "得到 %s" % pd["shop"].get("status"))
            check("pending 店菜品为空", len(pd["dishes"]) == 0)
        ok, cd = call("GET", "/api/client/bootstrap")
        in_client = pid in [s["id"] for s in cd["shops"]] if ok else False
        check("pending 店不在客户端可见列表", not in_client)
    else:
        print("  (跳过：没有待审核店铺)")

    # ---------- 4. 发布冷却拦截 ----------
    print("\n[4] 发布冷却拦截")
    if cooldown:
        cs = cooldown[0]
        ok, r = call("POST", "/api/merchant/dish", {
            "shopId": cs["id"], "name": "冷却测试菜", "type": "image",
            "media": ["https://picsum.photos/seed/cooldown/800/1200"], "price": 30
        })
        check("冷却中的店发布被拒", not ok, "居然成功了")
        check("拒绝原因可读（提到小时/上限）",
              (not ok) and any(w in str(r) for w in ["小时", "上限", "等待"]), str(r))
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
        "city": "成都市", "district": "武侯区", "address": "成都市武侯区回归路 1 号",
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
        check("新店自己能看自己的审核状态", ok and bp["shop"]["status"] == "pending")

        ok, ad = call("GET", "/api/admin/bootstrap")
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
