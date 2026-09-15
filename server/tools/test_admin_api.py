# -*- coding: utf-8 -*-
"""
平台端接口冒烟测试。

重点验证两件事：
1. 所有出参是**视图结构**（stats.views 这种嵌套），不是裸实体 —— 前端读的就是这个。
2. 平台端的写操作真的落库，并且**客户端接口立刻能感知到**（这是本次改造的核心）。

用法：
    python test_admin_api.py [base]
默认 base = http://127.0.0.1:8080

注意：审核（approve/reject）是不可逆的 —— 后端没有"退回待审核"的接口。
所以审核用例放在最后跑，跑完需要重启后端才能回到初始状态。
"""
import json
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
    print("== 平台端接口测试  %s ==" % BASE)

    # ---------- 1. 聚合接口结构 ----------
    print("\n[1] /admin/bootstrap 结构")
    ok, d = call("GET", "/api/admin/bootstrap")
    if not ok:
        print("  后端不可用：%s" % d)
        return
    need = ["overview", "shops", "dishes", "removed", "reports", "users",
            "pendingShops", "approvedShops", "rejectedShops", "config"]
    check("顶层 10 个键齐全", all(k in d for k in need),
          "缺失：" + str([k for k in need if k not in d]))
    check("pendingShops 非空", len(d["pendingShops"]) > 0, "实际 %d" % len(d["pendingShops"]))
    check("approvedShops 非空", len(d["approvedShops"]) > 0, "实际 %d" % len(d["approvedShops"]))
    check("rejectedShops 非空", len(d["rejectedShops"]) > 0, "实际 %d" % len(d["rejectedShops"]))
    check("config 含 publishRule", "publishRule" in d["config"],
          str(list(d["config"].keys())))

    # 视图结构（不是裸实体）
    s0 = d["shops"][0]
    check("shop 是视图结构（有 stats 嵌套）", isinstance(s0.get("stats"), dict),
          str(list(s0.keys()))[:120])
    check("shop.stats 含 views/likes/checkins",
          all(k in s0["stats"] for k in ("views", "likes", "checkins")),
          str(s0.get("stats")))
    check("shop 含审核字段", all(k in s0 for k in ("submittedAt", "reviewedAt", "reviewer", "rejectReason")))
    if d["dishes"]:
        dd = d["dishes"][0]
        check("dish 是视图结构（有 stats 嵌套）", isinstance(dd.get("stats"), dict))
        check("dish 含 priceTierId/publishedAt", "priceTierId" in dd and "publishedAt" in dd)
    if d["users"]:
        uu = d["users"][0]
        check("user 字段名对齐前端（comments/reports）",
              "comments" in uu and "reports" in uu, str(list(uu.keys())))

    check("overview 有 trend 且 7 天", len(d["overview"].get("trend") or []) == 7,
          str(d["overview"].get("trend"))[:100])
    check("overview.dishToday 是数字", isinstance(d["overview"].get("dishToday"), int))
    check("overview.shopPending == pending 数",
          d["overview"]["shopPending"] == len(d["pendingShops"]))

    # ---------- 2. 待审核店不在客户端 ----------
    print("\n[2] 待审核 / 已驳回的店不进客户端")
    ok, cb = call("GET", "/api/client/bootstrap")
    client_shop_ids = {s["id"] for s in cb["shops"]} if ok else set()
    pend_ids = {s["id"] for s in d["pendingShops"]}
    rej_ids = {s["id"] for s in d["rejectedShops"]}
    check("客户端看不到待审核店", not (pend_ids & client_shop_ids), str(pend_ids & client_shop_ids))
    check("客户端看不到已驳回店", not (rej_ids & client_shop_ids), str(rej_ids & client_shop_ids))

    # ---------- 3. 封店 → 客户端立刻消失 ----------
    print("\n[3] 封店落库，且客户端立刻感知")
    target = d["shops"][0]["id"]
    ok, r = call("POST", "/api/admin/shop/%s/ban" % target)
    check("封店接口返回视图", ok and r.get("status") == "banned", str(r)[:120])

    ok2, cb2 = call("GET", "/api/client/bootstrap")
    check("封店后客户端 bootstrap 已不含该店",
          ok2 and target not in {s["id"] for s in cb2["shops"]}, "target=%s" % target)

    ok, r = call("POST", "/api/admin/shop/%s/restore" % target)
    check("解封恢复", ok and r.get("status") == "normal", str(r)[:120])
    ok2, cb3 = call("GET", "/api/client/bootstrap")
    check("解封后客户端又能看到", ok2 and target in {s["id"] for s in cb3["shops"]})

    # ---------- 4. 内容下架 ----------
    print("\n[4] 内容下架 / 恢复")
    if d["dishes"]:
        did = d["dishes"][0]["id"]
        ok, r = call("POST", "/api/admin/dish/%s/status" % did, {"status": "removed"})
        check("下架返回 status=removed", ok and r.get("status") == "removed", str(r)[:120])
        ok2, cb4 = call("GET", "/api/client/bootstrap")
        check("下架后客户端看不到该菜品",
              ok2 and did not in {x["id"] for x in cb4["dishes"]},
              "did=%s" % did)

        ok, r = call("POST", "/api/admin/dish/%s/status" % did, {"status": "normal"})
        check("恢复正常", ok and r.get("status") == "normal")

        # 打标签
        ok, r = call("POST", "/api/admin/dish/%s/real-tag" % did, {"realTag": "ad"})
        check("打标签返回 realTag=ad", ok and r.get("realTag") == "ad", str(r)[:120])
        ok, r = call("POST", "/api/admin/dish/%s/real-tag" % did, {"realTag": None})
        check("清除标签", ok and r.get("realTag") is None)

    # ---------- 5. 置顶 / 权重 ----------
    print("\n[5] 置顶 / 权重")
    sid = d["shops"][1]["id"]
    before = next(s for s in d["shops"] if s["id"] == sid)
    ok, r = call("POST", "/api/admin/shop/%s/pinned" % sid, {"pinned": not before["pinned"]})
    check("置顶开关生效", ok and r.get("pinned") == (not before["pinned"]), str(r)[:120])
    call("POST", "/api/admin/shop/%s/pinned" % sid, {"pinned": before["pinned"]})

    ok, r = call("POST", "/api/admin/shop/%s/weight" % sid, {"weight": 77})
    check("权重设置生效", ok and r.get("weight") == 77, str(r)[:120])
    call("POST", "/api/admin/shop/%s/weight" % sid, {"weight": before["weight"]})

    # ---------- 6. 排序落库 ----------
    print("\n[6] 拖拽排序落库")
    live = [s["id"] for s in d["shops"] if s["status"] in ("normal", "muted")]
    pinned_ids = {s["id"] for s in d["shops"] if s.get("pinned")}
    rev = list(reversed(live))
    ok, r = call("POST", "/api/admin/ranking/order", {"shopIds": rev})
    check("排序接口返回全量店铺视图", ok and isinstance(r, list) and len(r) == len(d["shops"]), str(type(r)))
    if ok:
        # 注意：置顶优先级最高是冻结规则，所以拖拽只决定「非置顶店」之间的相对顺序。
        # 断言必须把置顶店排除掉，否则会把正确行为当成 bug。
        expect = [i for i in rev if i not in pinned_ids]
        got = [s["id"] for s in r if s["id"] in set(live) and s["id"] not in pinned_ids]
        check("非置顶店的落库顺序与提交一致", got == expect,
              "提交 %s / 实际 %s" % (expect, got))
        if pinned_ids:
            first = [s["id"] for s in r if s["id"] in set(live)][:len(pinned_ids)]
            check("置顶店被排在最前（冻结规则）",
                  set(first) >= pinned_ids, "前 %d 位是 %s，置顶的是 %s" % (len(first), first, pinned_ids))

    # ---------- 7. 发布规则 ----------
    print("\n[7] 单店规则 / 全局默认规则")
    ok, r = call("POST", "/api/admin/shop/%s/rule" % sid, {"intervalHours": 12, "dailyLimit": 2})
    check("单店规则落地", ok and r.get("intervalHours") == 12 and r.get("dailyLimit") == 2, str(r)[:120])
    call("POST", "/api/admin/shop/%s/rule" % sid,
         {"intervalHours": before["intervalHours"], "dailyLimit": before["dailyLimit"]})

    ok, r = call("GET", "/api/admin/config")
    check("config 含 publishRule", ok and "publishRule" in r, str(list(r.keys()) if ok else r))
    rule0 = r.get("publishRule") if ok else {}
    ok, r = call("POST", "/api/admin/config/publish-rule", {"intervalHours": 6, "dailyLimit": 3})
    check("全局规则保存", ok and r.get("intervalHours") == 6 and r.get("dailyLimit") == 3, str(r)[:120])
    call("POST", "/api/admin/config/publish-rule",
         {"intervalHours": rule0.get("intervalHours", 24), "dailyLimit": rule0.get("dailyLimit", 1)})

    # 参数校验
    ok, msg = call("POST", "/api/admin/config/publish-rule", {"intervalHours": 999})
    check("非法间隔被拒绝", not ok, "竟然通过了")

    # ---------- 8. 价格档 ----------
    print("\n[8] 价格档保存 + 自动重算菜品档位")
    old_tiers = d["config"]["priceTiers"]
    new_tiers = [
        {"id": "tier_a", "name": "便宜", "min": 0, "max": 30},
        {"id": "tier_b", "name": "一般", "min": 30, "max": 100},
        {"id": "tier_c", "name": "贵", "min": 100, "max": -1},
    ]
    ok, r = call("POST", "/api/admin/config/price-tiers", {"tiers": new_tiers})
    check("保存价格档", ok and len(r.get("priceTiers", [])) == 3, str(r)[:140])
    check("回传了重算后的菜品", ok and isinstance(r.get("dishes"), list), str(list(r.keys()) if ok else r))
    if ok:
        tiers = {t["id"] for t in r["priceTiers"]}
        bad = [x["id"] for x in r["dishes"] if x.get("priceTierId") not in tiers]
        check("没有菜品残留旧档位 id", not bad, "残留：%s" % bad[:5])

    ok, r = call("POST", "/api/admin/config/price-tiers", {"tiers": old_tiers})
    check("恢复原价格档", ok and len(r.get("priceTiers", [])) == 3)

    # ---------- 9. 用户状态 ----------
    print("\n[9] 用户状态")
    if d["users"]:
        uid = d["users"][0]["id"]
        u0 = d["users"][0]
        ok, r = call("POST", "/api/admin/user/%s/status" % uid, {"status": "banned"})
        check("封号生效", ok and r.get("status") == "banned", str(r)[:120])
        call("POST", "/api/admin/user/%s/status" % uid, {"status": u0["status"]})

    # ---------- 10. 举报处理（不可逆，放最后）----------
    print("\n[10] 举报处理")
    if d["reports"]:
        rid = d["reports"][0]["id"]
        ok, r = call("POST", "/api/admin/report/%s/handle" % rid, {"action": "rejected"})
        check("驳回举报", ok and r.get("status") == "rejected", str(r)[:120])
        # 还原成 pending，方便重复跑
        call("POST", "/api/admin/report/%s/handle" % rid, {"action": "pending"})

    # ---------- 11. 商家审核（不可逆）----------
    print("\n[11] 商家审核 —— 通过后客户端立即可见")
    if d["pendingShops"]:
        pid = d["pendingShops"][0]["id"]
        ok, r = call("POST", "/api/admin/audit/%s/approve" % pid, {"reviewer": "测试"})
        check("审核通过", ok and r.get("status") == "normal", str(r)[:140])
        check("写入审核人和时间", ok and r.get("reviewer") == "测试" and r.get("reviewedAt"))
        ok2, cb5 = call("GET", "/api/client/bootstrap")
        check("通过后客户端立刻能看到这家店",
              ok2 and pid in {s["id"] for s in cb5["shops"]}, "pid=%s" % pid)

        # 缺理由的驳回应被拒
        if len(d["pendingShops"]) > 1:
            pid2 = d["pendingShops"][1]["id"]
            ok, msg = call("POST", "/api/admin/audit/%s/reject" % pid2, {"reason": ""})
            check("驳回必须填理由", not ok, "竟然通过了")
            ok, r = call("POST", "/api/admin/audit/%s/reject" % pid2,
                         {"reason": "门头图不清晰，无法辨认店铺招牌"})
            check("驳回成功并记录理由",
                  ok and r.get("status") == "rejected" and r.get("rejectReason"), str(r)[:140])

    print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - %s" % f)
    print("\n提示：审核用例不可逆，重启后端即可回到初始数据。")


if __name__ == "__main__":
    main()
