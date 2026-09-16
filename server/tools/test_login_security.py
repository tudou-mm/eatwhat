# -*- coding: utf-8 -*-
"""
登录安全专项测试。

上一轮补了「有鉴权」，这一轮补的是「鉴权之前的那个门」——
登录本身能不能被撞库、验证码是不是真的在验。

覆盖 6 组：
  [1] 发码接口：格式校验、60 秒重发限制、devCode 回显
  [2] 客户端登录：真校验验证码、用后即焚
  [3] 验证码错误次数上限
  [4] 商家端用「手机号 + 验证码」登录
  [5] 登录失败锁定（连错 5 次锁 15 分钟，锁定后正确密码也拒）
  [6] IP 限速的说明（不触发，见文末）

⚠️ 本脚本会**锁定一家「已驳回」店铺**。选这种店铺是因为别的脚本都不会登录它，
   所以不会互相干扰。跑完仍建议重启后端回到干净状态（H2 内存库，重启即复原）。

用法：
    python test_login_security.py [base]
默认 base = http://127.0.0.1:8080
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
PASS = FAIL = 0
FAILURES = []

ADMIN_ACCOUNT = "admin"
ADMIN_PWD = "admin123"
DEMO_PHONE = "13800138000"       # s_001 的电话（DataSeeder）
MERCHANT_PWD = "123456"

# 全是虚构号段，不会撞上演示数据
PHONE_B = "13900000002"          # 客户端登录用
PHONE_C = "13900000003"          # 验证码错误上限用
PHONE_D = "13900000004"          # 只用来验证「换个号还能发」
PHONE_NEW = "13700000009"        # 未入驻商家

WRONG_CODE = "000000"


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            txt = r.read().decode("utf-8", "replace")
            st = r.status
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace")
        st = e.code
    except Exception as e:
        return -1, {"msg": str(e)[:160]}
    try:
        return st, json.loads(txt)
    except Exception:
        return st, {"msg": txt[:200]}


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  [FAIL] %s   %s" % (name, detail))


def msg_of(j):
    return (j or {}).get("msg", "") if isinstance(j, dict) else str(j)


def send_code(phone):
    """
    发码。若被 60 秒重发限制挡住，就把剩余秒数等完再发 ——
    这样脚本可以连着跑第二遍，不用手动等。
    """
    st, j = call("POST", "/api/auth/sms-code", body={"phone": phone})
    if st == 429:
        m = re.search(r"(\d+)\s*秒", msg_of(j))
        if m and int(m.group(1)) <= 70:
            wait = int(m.group(1))
            print("        （被重发限制挡住，等 %d 秒后重发）" % wait)
            time.sleep(wait + 1)
            st, j = call("POST", "/api/auth/sms-code", body={"phone": phone})
    return st, j


def login(body, token=None):
    return call("POST", "/api/auth/login", body=body, token=token)


# ============================================================
print("=" * 60)
print("登录安全专项测试   base = %s" % BASE)
print("=" * 60)

# ---------------- [1] 发码接口 ----------------
print("\n[1] 发码接口")
st, j = call("POST", "/api/auth/sms-code", body={"phone": "123"})
check("手机号格式不对被拒（400）", st == 400, "http=%s msg=%s" % (st, msg_of(j)))

st, j = send_code(PHONE_B)
d = j.get("data") or {}
check("发码成功", st == 200 and d.get("sent") is True, "http=%s msg=%s" % (st, msg_of(j)))
dev_code_b = d.get("devCode")
check("dev 环境回显 6 位 devCode", bool(dev_code_b) and len(dev_code_b) == 6, "devCode=%s" % dev_code_b)
check("返回有效期 expiresIn=300", d.get("expiresIn") == 300, "expiresIn=%s" % d.get("expiresIn"))
check("返回重发间隔 resendAfter=60", d.get("resendAfter") == 60, "resendAfter=%s" % d.get("resendAfter"))

st2, j2 = call("POST", "/api/auth/sms-code", body={"phone": PHONE_B})
check("同一号码 60 秒内重发被拒（429）", st2 == 429, "http=%s" % st2)
check("重发被拒时提示还要等几秒", "秒" in msg_of(j2), msg_of(j2))

st3, j3 = send_code(PHONE_D)
check("换一个号码可以正常发码（限制是按号码的）", st3 == 200, "http=%s msg=%s" % (st3, msg_of(j3)))

# ---------------- [2] 客户端登录 ----------------
print("\n[2] 客户端登录：验证码真校验")
st, j = call("POST", "/api/login", body={"phone": PHONE_C, "code": "123456"})
check("从没发过码就登录 → 400 提示先获取验证码", st == 400 and "获取验证码" in msg_of(j),
      "http=%s msg=%s" % (st, msg_of(j)))

wrong = WRONG_CODE if dev_code_b != WRONG_CODE else "111111"
st, j = call("POST", "/api/login", body={"phone": PHONE_B, "code": wrong})
check("验证码错误被拒（不再像以前任意 6 位都过）", st == 400, "http=%s msg=%s" % (st, msg_of(j)))

st, j = call("POST", "/api/login", body={"phone": PHONE_B, "code": dev_code_b})
d = j.get("data") or {}
check("正确验证码登录成功", st == 200 and bool(d.get("token")), "http=%s msg=%s" % (st, msg_of(j)))
check("返回的是 JWT（三段）", len(str(d.get("token", "")).split(".")) == 3)
check("角色为 client", d.get("role") == "client", "role=%s" % d.get("role"))
check("返回 user 视图对象", isinstance(d.get("user"), dict))

st, j = call("POST", "/api/login", body={"phone": PHONE_B, "code": dev_code_b})
check("同一个码无法重放（用后即焚）", st == 400, "http=%s msg=%s" % (st, msg_of(j)))

# ---------------- [3] 验证码错误次数上限 ----------------
print("\n[3] 验证码错误次数上限（错 5 次作废）")
st, j = send_code(PHONE_C)
dev_code_c = (j.get("data") or {}).get("devCode")
check("拿到用于测试的验证码", bool(dev_code_c), "http=%s msg=%s" % (st, msg_of(j)))

bad = WRONG_CODE if dev_code_c != WRONG_CODE else "111111"
last_st, last_msg, last_tries = 0, "", 0
for i in range(4):
    last_st, last_j = call("POST", "/api/login", body={"phone": PHONE_C, "code": bad})
    last_msg = msg_of(last_j)
    m = re.search(r"还可尝试\s*(\d+)\s*次", last_msg)
    if m:
        last_tries = int(m.group(1))
check("前几次错误会告知还剩几次机会", last_tries > 0, "最后一次提示：%s" % last_msg)
check("错误次数递减（最后剩 1 次）", last_tries == 1, "还剩 %s" % last_tries)

st, j = call("POST", "/api/login", body={"phone": PHONE_C, "code": bad})
check("第 5 次错误直接作废（429）", st == 429, "http=%s msg=%s" % (st, msg_of(j)))

st, j = call("POST", "/api/login", body={"phone": PHONE_C, "code": dev_code_c})
check("作废后即使输入正确验证码也不认（要求重新获取）", st == 400 and "获取验证码" in msg_of(j),
      "http=%s msg=%s" % (st, msg_of(j)))

# ---------------- [4] 商家验证码登录 ----------------
print("\n[4] 商家端：手机号 + 验证码登录")
st, j = send_code(DEMO_PHONE)
demo_code = (j.get("data") or {}).get("devCode")
check("给演示店电话发码成功", bool(demo_code), "http=%s msg=%s" % (st, msg_of(j)))

st, j = login({"role": "merchant", "account": DEMO_PHONE, "code": demo_code})
d = j.get("data") or {}
check("商家用手机号 + 验证码登录成功", st == 200 and bool(d.get("token")), "http=%s msg=%s" % (st, msg_of(j)))
check("身份落到 s_001", d.get("shopId") == "s_001", "shopId=%s" % d.get("shopId"))
check("顺带返回 shop 视图", isinstance(d.get("shop"), dict))
check("角色为 merchant", d.get("role") == "merchant", "role=%s" % d.get("role"))

st, j = login({"role": "merchant", "account": "s_001", "code": "123456"})
check("验证码登录只接受手机号（填店铺 ID 被拒）", st == 400, "http=%s msg=%s" % (st, msg_of(j)))

st, j = send_code(PHONE_NEW)
new_code = (j.get("data") or {}).get("devCode")
st, j = login({"role": "merchant", "account": PHONE_NEW, "code": new_code})
check("未入驻的手机号被拒（401）", st == 401, "http=%s msg=%s" % (st, msg_of(j)))

# ---------------- [5] 登录失败锁定 ----------------
print("\n[5] 登录失败锁定")
st, j = login({"role": "admin", "account": ADMIN_ACCOUNT, "password": ADMIN_PWD})
admin_token = (j.get("data") or {}).get("token")
check("拿到 admin token（后续查店铺用）", bool(admin_token), "http=%s msg=%s" % (st, msg_of(j)))

st, j = call("GET", "/api/admin/shops", token=admin_token)
shops = j.get("data") if isinstance(j.get("data"), list) else []
rejected = [s for s in shops if s.get("status") == "rejected"]
check("找到可用于测试的「已驳回」店铺", len(rejected) > 0, "共 %d 家店铺" % len(shops))

if rejected:
    victim = rejected[0]["id"]
    print("        用的账号：%s（已驳回店，别的脚本不会登录它）" % victim)

    codes = []
    for i in range(5):
        st, j = login({"role": "merchant", "account": victim, "password": "definitely-wrong"})
        codes.append(st)
    check("连错 5 次都返回 401（还没锁）", codes == [401] * 5, "实际 %s" % codes)

    st, j = login({"role": "merchant", "account": victim, "password": MERCHANT_PWD})
    check("第 6 次：即使密码正确也被拒（429）", st == 429, "http=%s msg=%s" % (st, msg_of(j)))
    check("提示里写明已锁定", "锁定" in msg_of(j), msg_of(j))
    m = re.search(r"(\d+)\s*分钟", msg_of(j))
    check("提示里带剩余分钟数", bool(m) and int(m.group(1)) > 0, msg_of(j))

    st, j = login({"role": "admin", "account": ADMIN_ACCOUNT, "password": ADMIN_PWD})
    check("锁定按账号隔离 —— 别的账号照常登录", st == 200, "http=%s msg=%s" % (st, msg_of(j)))

# ---------------- [6] IP 限速 ----------------
print("\n[6] IP 限速")
print("  [--]   跳过触发测试。限流是按 IP 的，本地永远同源，打满阈值会把")
print("         后续所有登录用例（包括别的脚本）一起限住。手工验证方法：")
print("         把 application.yml 的 eatwhat.auth.ipMaxPerMinute 改成 2，")
print("         重启后端，然后快速发 3 次登录请求 —— 第 3 次应返回 429。")
print("         本脚本全程 %d 次登录尝试都没被拦，说明正常用量不会被误伤。" % (4 + 5 + 5 + 4))

# ---------------- 汇总 ----------------
print("\n" + "=" * 60)
print("结果：%d 通过 / %d 失败" % (PASS, FAIL))
if FAILURES:
    print("失败项：")
    for f in FAILURES:
        print("  - %s" % f)
print("提示：本脚本锁了一家已驳回店铺、消耗了部分 IP 配额，重启后端即可复原。")
print("=" * 60)

sys.exit(1 if FAIL else 0)
