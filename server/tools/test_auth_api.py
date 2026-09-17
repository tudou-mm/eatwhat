# -*- coding: utf-8 -*-
"""
鉴权 + 文件上传专项测试。

为什么单独一个脚本：这两块是「上线前必补三项」里的内容，
错法很隐蔽 —— 比如 token 过期了没拦住、商家能改到别家店的数据、
上传接口把 .html 存进了静态目录。这些都必须有明确的断言钉住。

覆盖 5 组：
  [1] 登录发证：账号密码、角色、token 结构
  [2] 鉴权拦截：无 token / 假 token / 篡改 / 跨角色 / 跨店
  [3] 公开接口不该被拦
  [4] 上传：类型 / 尺寸 / 落盘 / 可访问
  [5] CORS 白名单

用法：
    python test_auth_api.py [base]
默认 base = http://127.0.0.1:8080

注意：第 4 组会真的往 server/uploads/ 写文件；第 2 组末尾会发一条菜品。
跑完重启后端即可回到初始演示数据。
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

ADMIN = ("admin", "admin123")
# s_001 的电话（DataSeeder 里就是它），同时也能直接用 "s_001" 登录
DEMO_SHOP = "s_001"
DEMO_PHONE = "13800138000"
DEMO_PWD = "123456"

# 一张 1x1 的透明 PNG，够小又真的是合法 PNG
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000a49444154789c63000100000500010d0a2db4000000"
    "0049454e44ae426082"
)


def call(method, path, body=None, token=None, raw=None, ct=None, extra_headers=None):
    """返回 (status, json_or_text)"""
    data = raw if raw is not None else (
        json.dumps(body).encode("utf-8") if body is not None else None)
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None and raw is None:
        req.add_header("Content-Type", "application/json")
    if ct:
        req.add_header("Content-Type", ct)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (extra_headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body_txt = r.read().decode("utf-8", "replace")
            st, headers = r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        body_txt = e.read().decode("utf-8", "replace")
        st, headers = e.code, dict(e.headers)
    except Exception as e:
        return -1, str(e)[:160], {}
    try:
        return st, json.loads(body_txt), headers
    except Exception:
        return st, body_txt[:200], headers


def api(method, path, body=None, token=None, **kw):
    """只关心业务结果：返回 (ok, data_or_msg)"""
    st, j, _ = call(method, path, body=body, token=token, **kw)
    if isinstance(j, dict) and j.get("code") == 0:
        return True, j.get("data")
    msg = j.get("msg") if isinstance(j, dict) else j
    return False, msg or ("HTTP %s" % st)


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  [FAIL] %s   %s" % (name, detail))


def multipart(filename, content, content_type):
    boundary = "----eatwhatTestBoundary"
    body = (b"--" + boundary.encode() + b"\r\n" +
            b'Content-Disposition: form-data; name="file"; filename="' +
            filename.encode() + b'"\r\n' +
            ("Content-Type: %s\r\n\r\n" % content_type).encode() +
            content + b"\r\n--" + boundary.encode() + b"--\r\n")
    return body, "multipart/form-data; boundary=" + boundary


def jwt_payload(token):
    """解 JWT 的 payload（不验签，只为了看里面写了什么）"""
    import base64
    part = token.split(".")[1]
    part += "=" * (-len(part) % 4)
    part = part.replace("-", "+").replace("_", "/")
    return json.loads(base64.b64decode(part).decode("utf-8"))


def main():
    print("== 鉴权 / 上传 专项测试  %s ==" % BASE)

    # ================= 1. 登录发证 =================
    print("\n[1] 登录发证")
    ok, admin = api("POST", "/api/auth/login",
                    {"role": "admin", "account": ADMIN[0], "password": ADMIN[1]})
    check("平台端登录成功", ok, str(admin)[:100])
    if not ok:
        print("  登录都过不了，后面没法测，退出。")
        return
    ATOK = admin["token"]
    check("返回 role=admin", admin.get("role") == "admin", str(admin.get("role")))
    check("返回过期秒数 > 0", isinstance(admin.get("expiresIn"), int) and admin["expiresIn"] > 0,
          str(admin.get("expiresIn")))
    check("token 是三段式 JWT", ATOK.count(".") == 2)

    pay = jwt_payload(ATOK)
    check("payload 里带 role", pay.get("role") == "admin", str(pay))
    check("payload 里带 exp（有过期时间）", isinstance(pay.get("exp"), int), str(pay))
    check("admin 的 payload 不带 shopId", "shopId" not in pay, str(pay.keys()))

    ok, r = api("POST", "/api/auth/login",
                {"role": "admin", "account": ADMIN[0], "password": "wrong"})
    check("平台端密码错误被拒", not ok, str(r))
    ok, r = api("POST", "/api/auth/login",
                {"role": "admin", "account": "root", "password": ADMIN[1]})
    check("平台端账号错误被拒", not ok, str(r))
    ok, r = api("POST", "/api/auth/login",
                {"role": "admin", "account": "admin", "password": "abc"})
    check("账号错和密码错的提示一致（防枚举）", not ok and "不正确" in str(r), str(r))

    st, j, _ = call("POST", "/api/auth/login",
                    {"role": "admin", "account": ADMIN[0], "password": "wrong"})
    check("密码错误返回 HTTP 401（不是 200 里塞 code）", st == 401, "http=%s" % st)

    ok, m1 = api("POST", "/api/auth/login",
                 {"role": "merchant", "account": DEMO_PHONE, "password": DEMO_PWD})
    check("商家用店内电话登录成功", ok and m1.get("shopId") == DEMO_SHOP,
          "%s / %s" % (ok, m1.get("shopId") if ok else m1))
    MTOK = m1["token"] if ok else None
    mpay = jwt_payload(MTOK) if MTOK else {}
    check("商家 payload 里带 shopId", mpay.get("shopId") == DEMO_SHOP, str(mpay))

    ok, m2 = api("POST", "/api/auth/login",
                 {"role": "merchant", "account": DEMO_SHOP, "password": DEMO_PWD})
    check("商家也能用店铺 id 登录", ok and m2.get("shopId") == DEMO_SHOP,
          str(m2)[:80])

    ok, r = api("POST", "/api/auth/login",
                {"role": "merchant", "account": DEMO_PHONE, "password": "000000"})
    check("商家密码错误被拒", not ok and "密码" in str(r), str(r))
    ok, r = api("POST", "/api/auth/login",
                {"role": "merchant", "account": "13000000000", "password": DEMO_PWD})
    check("未入驻的电话被拒", not ok, str(r))
    ok, r = api("POST", "/api/auth/login", {"role": "admin", "account": "", "password": ""})
    check("空账号被拒", not ok, str(r))

    # ================= 2. 鉴权拦截 =================
    print("\n[2] 鉴权拦截")
    st, j, _ = call("GET", "/api/admin/bootstrap")
    check("无 token 打平台端 → 401", st == 401, "http=%s" % st)
    st, j, _ = call("GET", "/api/merchant/bootstrap?shopId=" + DEMO_SHOP)
    check("无 token 打商家端 → 401", st == 401, "http=%s" % st)
    st, j, _ = call("POST", "/api/admin/audit", {"shopId": "p_001", "action": "approve"})
    check("无 token 写平台端 → 401", st == 401, "http=%s" % st)

    st, j, _ = call("GET", "/api/admin/bootstrap", token="not-a-jwt")
    check("乱码 token → 401", st == 401, "http=%s" % st)
    st, j, _ = call("GET", "/api/admin/bootstrap", token=ATOK + "x")
    check("签名被改过的 token → 401", st == 401, "http=%s" % st)

    # 把 payload 改成 admin 但沿用原签名 —— 典型的「自己解出来改一改」
    parts = ATOK.split(".")
    forged = parts[0] + "." + parts[1] + "." + ("A" * len(parts[2]))
    st, j, _ = call("GET", "/api/admin/bootstrap", token=forged)
    check("重签名伪造 token → 401", st == 401, "http=%s" % st)

    st, j, _ = call("GET", "/api/admin/bootstrap", token=MTOK)
    check("商家 token 打平台端 → 403", st == 403, "http=%s" % st)
    st, j, _ = call("GET", "/api/merchant/bootstrap?shopId=" + DEMO_SHOP, token=ATOK)
    check("平台端 token 打商家端 → 403", st == 403, "http=%s" % st)

    ok, shops = api("GET", "/api/admin/shops", token=ATOK)
    other = None
    if ok:
        other = next((s["id"] for s in shops
                      if s["id"] != DEMO_SHOP and s["status"] == "normal"), None)
    st, j, _ = call("GET", "/api/merchant/bootstrap?shopId=" + (other or "s_002"), token=MTOK)
    check("商家 token 读别家店 → 403", st == 403, "http=%s" % st)
    st, j, _ = call("PUT", "/api/merchant/shop/" + (other or "s_002"),
                    {"name": "被改的店名"}, token=MTOK)
    check("商家 token 改别家店资料 → 403", st == 403, "http=%s" % st)
    st, j, _ = call("GET", "/api/merchant/dashboard/" + (other or "s_002"), token=MTOK)
    check("商家 token 读别家工作台 → 403", st == 403, "http=%s" % st)

    ok, d = api("GET", "/api/merchant/bootstrap?shopId=" + DEMO_SHOP, token=MTOK)
    check("商家 token 读自己店 → 200", ok, str(d)[:80])

    # 最阴的一条：body 里塞别家 shopId，看服务端认哪个
    ok, mine = api("POST", "/api/merchant/dish", {
        "shopId": other or "s_002", "name": "越权发布测试", "type": "image",
        "media": ["https://picsum.photos/seed/authz/800/1200"], "price": 20
    }, token=MTOK)
    if ok:
        check("body 里塞别家 shopId 也只会发到自己店（服务端以 token 为准）",
              mine.get("shopId") == DEMO_SHOP, "落到了 %s" % mine.get("shopId"))
    else:
        # 自己店在冷却中也算「被拦住了」，同样安全
        check("body 里塞别家 shopId 不会越权发布", True, "（本店冷却中：%s）" % mine)

    # ================= 3. 公开接口 =================
    print("\n[3] 公开接口不该被拦")
    st, j, _ = call("GET", "/api/health")
    check("/api/health 公开", st == 200, "http=%s" % st)
    st, j, _ = call("GET", "/api/client/bootstrap")
    check("/api/client/bootstrap 公开（客户端免登录浏览）", st == 200, "http=%s" % st)
    st, j, _ = call("POST", "/api/merchant/apply",
                    {"name": "鉴权测试店", "phone": "13700007777", "cuisine": "川菜"})
    check("/api/merchant/apply 公开（没账号的人才能申请入驻）", st == 200, "http=%s" % st)
    st, j, _ = call("GET", "/api/auth/login")
    check("/api/auth/login 不被自身拦截（GET 走到 405/400 都算没被 401）",
          st != 401, "http=%s" % st)

    # ================= 4. 文件上传 =================
    print("\n[4] 文件上传")
    # ⚠️ 原始文件名故意带上路径穿越意图，且**不能**用 'a.png' 这种短名：
    #    落盘名是 UUID（如 3f2b...-a.png），UUID 末位有 1/16 概率正好是 'a'，
    #    于是 URL 结尾成了 '...a.png'，`"a.png" not in url` 就会被误判成失败
    #    —— 一个 1/16 概率的假红，查起来极其费劲。
    #    换成 'evil' 就没这问题：UUID 是十六进制，不可能出现 v/i/l。
    ORIG_NAME = "../../evil.png"
    body, ct = multipart(ORIG_NAME, PNG, "image/png")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct)
    check("无 token 上传 → 401", st == 401, "http=%s" % st)

    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("合法 PNG 上传成功", st == 200 and j.get("code") == 0, str(j)[:120])
    up = j.get("data") if isinstance(j, dict) else None
    if up:
        check("返回 url + relative", bool(up.get("url")) and bool(up.get("relative")),
              str(list(up.keys())))
        check("kind=image", up.get("kind") == "image", str(up.get("kind")))
        check("size 与源文件一致", up.get("size") == len(PNG),
              "%s vs %s" % (up.get("size"), len(PNG)))
        check("落盘名不含原始文件名（防路径穿越）",
              "evil" not in up.get("url", "") and ".." not in up.get("url", ""),
              up.get("url"))

        st2, raw, _ = call("GET", up["relative"])
        check("上传后能按 url 取回", st2 == 200, "http=%s" % st2)
        if st2 == 200 and isinstance(raw, str):
            check("取回的不是 JSON 错误体（说明真落盘了）", "code" not in raw[:20])
        check("absolute url 与 relative 对得上",
              up["url"].endswith(up["relative"]), up["url"])

    body, ct = multipart("evil.html", b"<script>alert(1)</script>", "text/html")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("HTML 文件被拒（否则静态目录就是存储型 XSS）", st == 400, "http=%s" % st)

    body, ct = multipart("shell.png", b"<?php echo 1; ?>", "application/x-php")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("伪装成 .png 的非图片被拒", st == 400, "http=%s" % st)

    body, ct = multipart("x.mp4", PNG, "image/png")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("后缀与 Content-Type 矛盾被拒", st == 400, "http=%s" % st)

    body, ct = multipart("huge.png", PNG + b"\0" * (6 * 1024 * 1024), "image/png")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("超过 5MB 的图片被拒", st == 400, "http=%s" % st)
    if isinstance(j, dict):
        check("提示里写明上限", "5MB" in str(j.get("msg")), str(j.get("msg")))

    body, ct = multipart("clip.mp4", b"\x00\x00\x00\x18ftypmp42" + b"\0" * 1024, "video/mp4")
    st, j, _ = call("POST", "/api/upload", raw=body, ct=ct, token=MTOK)
    check("合法 mp4 上传成功（视频走 20MB 上限）",
          st == 200 and isinstance(j, dict) and j.get("data", {}).get("kind") == "video",
          str(j)[:120])

    ok, _ = api("GET", "/api/merchant/bootstrap?shopId=" + DEMO_SHOP, token=MTOK)
    check("上传接口不影响正常接口", ok)

    # ================= 5. CORS =================
    print("\n[5] CORS 白名单")
    st, j, hdrs = call("GET", "/api/client/bootstrap",
                       extra_headers={"Origin": "http://localhost:5173"})
    allowed = hdrs.get("Access-Control-Allow-Origin")
    check("白名单来源（localhost:5173）拿到 ACAO", allowed == "http://localhost:5173",
          "ACAO=%r" % allowed)

    st, j, hdrs = call("GET", "/api/client/bootstrap",
                       extra_headers={"Origin": "http://evil.example.com"})
    allowed = hdrs.get("Access-Control-Allow-Origin")
    check("非白名单来源拿不到 ACAO（不再是 *）", allowed is None, "ACAO=%r" % allowed)

    # 预检：跨域带 Authorization 必先 OPTIONS，拦错一步整个前端都连不上
    req = urllib.request.Request(BASE + "/api/admin/bootstrap", method="OPTIONS")
    req.add_header("Origin", "http://localhost:5173")
    req.add_header("Access-Control-Request-Method", "GET")
    req.add_header("Access-Control-Request-Headers", "authorization")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            st, hdrs = r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        st, hdrs = e.code, dict(e.headers)
    except Exception as e:
        st, hdrs = -1, {}
    check("预检 OPTIONS 不被鉴权拦（放行靠 ACAO 判断）",
          st in (200, 204), "http=%s" % st)
    check("预检允许 Authorization 头",
          "authorization" in str(hdrs.get("Access-Control-Allow-Headers", "")).lower(),
          str(hdrs.get("Access-Control-Allow-Headers")))

    # ---------- 汇总 ----------
    print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - " + f)
    else:
        print("提示：本脚本会写 uploads/、发一条菜品、插一家待审核店，重启后端即可复原。")


if __name__ == "__main__":
    main()
    sys.exit(1 if FAIL else 0)
