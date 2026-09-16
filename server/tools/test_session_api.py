# -*- coding: utf-8 -*-
"""
会话作废 / 状态闸门 专项测试。

为什么单独一个脚本：
    「封店之后商家还能不能继续操作」这种事，只看代码很容易以为已经拦住了 ——
    实际上登录口拦了、发布拦了，但改资料、上下架、回评全都没拦，
    而且**已经发出去的 token 根本没人管**，能一直用到过期。
    这类问题不会报错、不会崩，只会静默地放行，所以必须用断言钉死。

覆盖 7 组：
  [1] token 里带会话版本号
  [2] 封店 → 旧 token 立即全面失效（读 + 写都得挂）
  [3] 封店后无法重新登录；解封后旧 token 仍然无效、重新登录可用
  [4] 主动登出 → 凭证立刻作废；重复登出不报错
  [5] 禁言 → 仍可登录（不是变相封店），但发布 / 回评被拒
  [6] 客户端评论必须带身份（无 token 401 / 冒名无效 / 封号后 401）
  [7] 平台端登出 → 旧 admin token 失效

用法：
    python test_session_api.py [base]
默认 base = http://127.0.0.1:8080

注意：本脚本会封一家店、禁言一家店、封一个用户，跑完**重启后端**即可复原。
"""
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
PASS = FAIL = 0
FAILURES = []

ADMIN = ("admin", "admin123")
MERCHANT_PWD = "123456"


def call(method, path, body=None, token=None):
    """返回 (status, json_or_text)"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt[:200]
    except Exception as e:
        return -1, str(e)[:160]


def api(method, path, body=None, token=None):
    """只关心业务结果：返回 (ok, data_or_msg)"""
    st, j = call(method, path, body=body, token=token)
    if isinstance(j, dict) and j.get("code") == 0:
        return True, j.get("data")
    msg = j.get("msg") if isinstance(j, dict) else j
    return False, msg or ("HTTP %s" % st)


def status_of(method, path, body=None, token=None):
    return call(method, path, body=body, token=token)[0]


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  [FAIL] %s   %s" % (name, detail))


def jwt_payload(token):
    import base64
    part = token.split(".")[1]
    part += "=" * (-len(part) % 4)
    part = part.replace("-", "+").replace("_", "/")
    return json.loads(base64.b64decode(part).decode("utf-8"))


def login_merchant(shop_id):
    ok, d = api("POST", "/api/auth/login",
                {"role": "merchant", "account": shop_id, "password": MERCHANT_PWD})
    return (d.get("token") if ok else None), d


def login_client(phone):
    """走真实发码流程登录客户端，返回 (token, user, msg)"""
    ok, d = api("POST", "/api/auth/sms-code", {"phone": phone})
    if not ok:
        return None, None, "发码失败：%s" % d
    code = (d or {}).get("devCode")
    if not code:
        return None, None, "接口没回显 devCode（echoSmsCode 关了？）"
    ok, d = api("POST", "/api/login", {"phone": phone, "code": code})
    if not ok:
        return None, None, d
    return d.get("token"), d.get("user"), None


def main():
    print("== 会话作废 / 状态闸门 专项测试  %s ==" % BASE)

    # ---------- 准备：管理员登录，挑两家在营店 ----------
    ok, admin = api("POST", "/api/auth/login",
                    {"role": "admin", "account": ADMIN[0], "password": ADMIN[1]})
    if not ok:
        print("平台端登录失败，无法继续：%s" % admin)
        return
    ATOK = admin["token"]

    ok, boot = api("GET", "/api/admin/bootstrap", token=ATOK)
    if not ok:
        print("拿不到 admin/bootstrap：%s" % boot)
        return
    normal = [s for s in (boot.get("shops") or []) if s.get("status") == "normal"]
    if len(normal) < 2:
        print("在营店铺不足 2 家，无法测试")
        return
    BAN_SHOP = normal[0]["id"]
    MUTE_SHOP = normal[1]["id"]
    print("   用店：封店测试=%s  禁言测试=%s" % (BAN_SHOP, MUTE_SHOP))

    # ================= 1. token 里带会话版本号 =================
    print("\n[1] token 携带会话版本号")
    MTOK, mdata = login_merchant(BAN_SHOP)
    check("商家登录成功", bool(MTOK), str(mdata)[:120])
    if not MTOK:
        return
    pay = jwt_payload(MTOK)
    check("payload 里有 tv 字段", "tv" in pay, str(pay))
    check("tv 是非负整数", isinstance(pay.get("tv"), int) and pay["tv"] >= 0, str(pay.get("tv")))
    # 记下此刻的版本号，后面一律用「相对差值」断言 ——
    # 写死 0 / 1 的话，脚本跑第二遍（上次封过店）就会假红
    TV0 = pay.get("tv")
    check("商家能正常访问工作台",
          status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=MTOK) == 200)

    # ================= 2. 封店 → 旧 token 立即失效 =================
    print("\n[2] 封店后旧 token 立刻失效（读写都要挂）")
    ok, _ = api("POST", "/api/admin/shop/%s/ban" % BAN_SHOP, token=ATOK)
    check("平台端封店成功", ok)

    check("工作台读取被拒（401，不是 403）",
          status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=MTOK) == 401,
          "http=%s" % status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=MTOK))
    check("菜品列表被拒",
          status_of("GET", "/api/merchant/dishes/" + BAN_SHOP, token=MTOK) == 401)
    check("改店铺资料被拒",
          status_of("PUT", "/api/merchant/shop/" + BAN_SHOP,
                    {"intro": "封店后还想改"}, token=MTOK) == 401)
    st, j = call("POST", "/api/merchant/dish",
                 {"shopId": BAN_SHOP, "name": "封店后硬发", "type": "image",
                  "media": ["https://picsum.photos/seed/x/600/600"]}, token=MTOK)
    check("发布菜品被拒", st == 401, "http=%s %s" % (st, j))
    check("下架菜品被拒",
          status_of("POST", "/api/merchant/dish/d_001/remove", {}, token=MTOK) == 401)

    # ================= 3. 封店后登录与解封 =================
    print("\n[3] 封店后不能登录；解封后旧 token 仍然无效")
    tok2, d2 = login_merchant(BAN_SHOP)
    check("封店后重新登录被拒", tok2 is None, str(d2)[:120])

    api("POST", "/api/admin/shop/%s/restore" % BAN_SHOP, token=ATOK)
    check("解封后旧 token 依然无效（版本号已 +1）",
          status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=MTOK) == 401)

    tok3, d3 = login_merchant(BAN_SHOP)
    check("解封后重新登录成功", bool(tok3), str(d3)[:120])
    if tok3:
        check("新 token 能正常访问",
              status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=tok3) == 200)
        check("封店把版本号推进了一格（旧 token 才会作废）",
              jwt_payload(tok3).get("tv") == TV0 + 1,
              "封店前 %s → 重新登录后 %s" % (TV0, jwt_payload(tok3).get("tv")))

    # ================= 4. 主动登出 =================
    print("\n[4] 主动登出 → 凭证立即作废")
    if tok3:
        check("登出前可访问",
              status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=tok3) == 200)
        ok, d = api("POST", "/api/auth/logout", {}, token=tok3)
        check("登出接口返回成功", ok and d.get("revoked") is True, str(d)[:120])
        check("登出后旧 token 立即 401",
              status_of("GET", "/api/merchant/dashboard/" + BAN_SHOP, token=tok3) == 401)
        ok, d = api("POST", "/api/auth/logout", {}, token=tok3)
        check("重复登出仍返回成功（幂等）", ok, str(d)[:120])
        ok, d = api("POST", "/api/auth/logout", {})
        check("不带 token 登出也返回成功", ok, str(d)[:120])
        st, _ = call("POST", "/api/auth/logout", {}, token="not-a-jwt")
        check("坏 token 登出不报错", st == 200, "http=%s" % st)

    # ================= 5. 禁言：能登录，但不能发 / 不能回评 =================
    print("\n[5] 禁言只限制发布与回评，不该变成变相封店")
    MTOK2, d4 = login_merchant(MUTE_SHOP)
    check("禁言前登录成功", bool(MTOK2), str(d4)[:120])
    ok, _ = api("POST", "/api/admin/shop/%s/mute" % MUTE_SHOP, token=ATOK)
    check("平台端禁言成功", ok)

    check("禁言后仍能登录（本地已有 token 仍可用）",
          status_of("GET", "/api/merchant/dashboard/" + MUTE_SHOP, token=MTOK2) == 200,
          "http=%s" % status_of("GET", "/api/merchant/dashboard/" + MUTE_SHOP, token=MTOK2))
    tok_m, d_m = login_merchant(MUTE_SHOP)
    check("禁言后重新登录也放行（不是封店）", bool(tok_m), str(d_m)[:120])

    st, j = call("POST", "/api/merchant/dish",
                 {"shopId": MUTE_SHOP, "name": "禁言后硬发", "type": "image",
                  "media": ["https://picsum.photos/seed/y/600/600"]}, token=MTOK2)
    check("禁言后发布被拒", st >= 400, "http=%s %s" % (st, j))

    ok, cmts = api("GET", "/api/merchant/comments/" + MUTE_SHOP, token=MTOK2)
    cid = (cmts or [{}])[0].get("id") if isinstance(cmts, list) and cmts else None
    if cid:
        st, j = call("POST", "/api/merchant/comment/%s/reply" % cid,
                     {"content": "禁言后硬回"}, token=MTOK2)
        check("禁言后回评被拒", st >= 400, "http=%s %s" % (st, j))
    else:
        check("禁言后回评被拒（该店无评论可测，跳过）", True, "no comment")

    api("POST", "/api/admin/shop/%s/restore" % MUTE_SHOP, token=ATOK)

    # ================= 6. 客户端评论必须带身份 =================
    print("\n[6] 客户端评论：身份必须来自 token")
    ok, cb = api("GET", "/api/client/bootstrap")
    dish_id = (cb.get("dishes") or [{}])[0].get("id") if ok else None
    check("取到一条菜品用于评论测试", bool(dish_id), str(dish_id))

    if dish_id:
        st, j = call("POST", "/api/client/dish/%s/comment" % dish_id,
                     {"userId": "u_999999", "content": "匿名冒名"})
        check("不带 token 发评论被拒（401）", st == 401, "http=%s %s" % (st, j))
        check("商家 token 发评论被拒（403，角色不符）",
              status_of("POST", "/api/client/dish/%s/comment" % dish_id,
                        {"content": "商家假冒食客"}, token=MTOK2) == 403)

    # 每次跑用一个全新的手机号：短信发码有 60 秒冷却，
    # 固定号码会让脚本「连跑两遍」时第二遍直接假红
    import random
    CLIENT_PHONE = "139" + "".join(random.choice("0123456789") for _ in range(8))
    CT, CUSER, err = login_client(CLIENT_PHONE)
    check("客户端登录成功（%s）" % CLIENT_PHONE, bool(CT), str(err))
    if CT and dish_id:
        ok, c1 = api("POST", "/api/client/dish/%s/comment" % dish_id,
                     {"userId": "u_999999", "content": "这条应该记在登录人头上"}, token=CT)
        check("带 token 发评论成功", ok, str(c1)[:120])
        if ok:
            check("评论归属登录用户，而不是请求体里的 userId",
                  c1.get("userId") == CUSER.get("id"),
                  "服务端记成了 %s，登录人是 %s" % (c1.get("userId"), CUSER.get("id")))

    # 封号 → 旧 token 立即失效
    if CT and CUSER:
        ok, _ = api("POST", "/api/admin/user/%s/status" % CUSER["id"], {"status": "banned"}, token=ATOK)
        check("平台端封号成功", ok)
        st, j = call("POST", "/api/client/dish/%s/comment" % dish_id,
                     {"content": "封号后还想发"}, token=CT)
        check("封号后旧 token 发评论被拒（401）", st == 401, "http=%s %s" % (st, j))
        # 「封号后能不能重新登录」这里测不了：短信验证码是**用后即焚**的，
        # 发码又有 60 秒冷却，拿不到第二个有效码去试。
        # 商家侧那条（封店后重新登录被拒）用的是密码登录，没有这个限制，已经覆盖了。

    # ================= 7. 平台端登出 =================
    print("\n[7] 平台端登出 → 旧 admin token 失效")
    ok, a2 = api("POST", "/api/auth/login",
                 {"role": "admin", "account": ADMIN[0], "password": ADMIN[1]})
    ATOK2 = a2.get("token") if ok else None
    check("平台端重新登录成功", bool(ATOK2), str(a2)[:120])
    if ATOK2:
        check("登录后可访问平台端",
              status_of("GET", "/api/admin/bootstrap", token=ATOK2) == 200)
        api("POST", "/api/auth/logout", {}, token=ATOK2)
        check("平台端登出后旧 token 失效",
              status_of("GET", "/api/admin/bootstrap", token=ATOK2) == 401)
        # 收尾：重新登录一次并还原被测试影响的店铺状态
        ok, a3 = api("POST", "/api/auth/login",
                     {"role": "admin", "account": ADMIN[0], "password": ADMIN[1]})
        if ok:
            api("POST", "/api/admin/shop/%s/restore" % BAN_SHOP, token=a3["token"])
            api("POST", "/api/admin/shop/%s/restore" % MUTE_SHOP, token=a3["token"])

    # ---------- 汇总 ----------
    print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - " + f)
    else:
        print("提示：本脚本封过店、禁过言、封过一个用户号，重启后端即可回到初始演示数据。")


if __name__ == "__main__":
    main()
    sys.exit(1 if FAIL else 0)
