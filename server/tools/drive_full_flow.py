# -*- coding: utf-8 -*-
"""
完整业务流程端到端：一条数据从产生到消失的全链路。

别的 drive_* 脚本是「逐页体检」，这个脚本只干一件事：
**把一道新菜从发布到下架完整走一遍，验证三端之间真的是通的。**

流程（全部在真实 Chrome 里点）：
  ① 商家端用「手机号 + 短信验证码」登录（演示店 s_001）
  ② 换一家「今日可发」的店，用密码登录，发布一道新菜
  ③ 平台端内容管理能看到这条
  ④ 客户端首页信息流能刷到这条
  ⑤ 平台端把它下架
  ⑥ 客户端信息流里它消失
  ⑦ 商家端菜品管理里显示「已下架」

为什么必须这么跑：接口测试能证明"后端落库了"、单页 e2e 能证明"某个页面显示对了"，
但证明不了**跨端闭环**——发布之后客户端真的能刷到、下架之后真的会消失。
这条链路断在中间任何一环，单页测试都是绿的。

用法：
    python drive_full_flow.py [前端地址]
默认 http://127.0.0.1:5173

⚠️ 会发一条真实菜品（店进入冷却）。跑完重启后端即回到干净数据。
"""
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

try:
    import websocket
except ImportError:
    raise SystemExit("需要 websocket-client：pip install websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-flow")
DEBUG_PORT = 9338
FRONT = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5173").rstrip("/")
API = os.environ.get("EAT_API_BASE", "http://127.0.0.1:8080").rstrip("/")

ADMIN_ACCOUNT, ADMIN_PWD = "admin", "admin123"
MERCHANT_PWD = "123456"
DEMO_SHOP, DEMO_PHONE = "s_001", "13800138000"

# 一张 1x1 的合法 PNG，用来喂给发布页的 file input
PNG_HEX = ("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
           "1f15c4890000000a49444154789c63000100000500010d0a2db4000000"
           "0049454e44ae426082")

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

PASS = FAIL = 0
FAILURES = []


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [OK]   %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  [FAIL] %s   %s" % (name, detail))


# ---------------- 直接问后端要权威数据（用来和页面显示对比） ----------------

def api_call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            j = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        j = json.loads(e.read().decode("utf-8", "replace"))
    if not isinstance(j, dict) or j.get("code") != 0:
        raise SystemExit("接口 %s %s 失败：%s" % (method, path, j))
    return j.get("data")


# ---------------- CDP ----------------

class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=45, suppress_origin=True)
        self.seq = 0
        self.errors = []

    def _handle(self, msg):
        if msg.get("method") == "Runtime.exceptionThrown":
            d = msg.get("params", {}).get("exceptionDetails") or {}
            txt = (d.get("exception") or {}).get("description") or d.get("text") or ""
            self.errors.append(txt.split("\n")[0][:200])

    def send(self, method, params=None, timeout=45):
        self.seq += 1
        mid = self.seq
        self.ws.settimeout(timeout)
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                return msg
            self._handle(msg)

    def eval(self, expr):
        r = self.send("Runtime.evaluate", {"expression": expr, "returnByValue": True})
        res = r.get("result", {})
        if "exceptionDetails" in res:
            return {"__err": str(res["exceptionDetails"])[:250]}
        return res.get("result", {}).get("value")

    def open(self, url, settle=1.2):
        self.errors = []
        self.send("Page.navigate", {"url": url})
        end = time.time() + 20
        while time.time() < end:
            try:
                if self.eval("document.readyState") == "complete":
                    break
            except Exception:
                pass
            time.sleep(0.25)
        time.sleep(settle)
        self.eval("window.confirm = function(){ return true; };")

    def shot(self, name):
        r = self.send("Page.captureScreenshot", {"format": "png"})
        data = (r.get("result") or {}).get("data")
        if data:
            with open(os.path.join(OUT, name), "wb") as f:
                f.write(base64.b64decode(data))

    def no_js_error(self, where):
        errs = [e for e in self.errors if e and "favicon" not in e.lower()]
        check("%s 无 JS 异常" % where, not errs, "; ".join(errs[:2]))

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def find_chrome():
    for c in CHROME_CANDS:
        if os.path.exists(c):
            return c
    raise SystemExit("找不到 Chrome/Edge")


def ws_endpoint():
    end = time.time() + 25
    while time.time() < end:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/json" % DEBUG_PORT, timeout=2) as r:
                for t in json.loads(r.read().decode("utf-8")):
                    if t.get("type") == "page":
                        return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(0.4)
    raise SystemExit("Chrome 调试端口未就绪")


def grab_sms_code(cdp, tries=3):
    """在商家登录页点「获取验证码」，从 toast 里把码读出来。

    码是 dev 环境后端回显的（echoSmsCode=true）。被 60 秒重发限制挡住时，
    等剩余秒数再试 —— 这样脚本可以连着跑第二遍。
    """
    for _ in range(tries):
        cdp.eval("sendSmsCode();")
        wait = None
        for _ in range(28):
            t = cdp.eval("document.getElementById('toast').textContent") or ""
            m = re.search(r"验证码：(\d{6})", t)
            if m:
                return m.group(1)
            m2 = re.search(r"请\s*(\d+)\s*秒", t)
            if m2:
                wait = int(m2.group(1))
                break
            time.sleep(0.25)
        if wait:
            print("        （发码被限流，等 %d 秒后重试）" % wait)
            time.sleep(wait + 1)
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="cdp-flow-")
    chrome = subprocess.Popen([
        find_chrome(), "--headless=new", "--disable-gpu", "--no-first-run",
        "--no-default-browser-check", "--disable-extensions",
        "--remote-debugging-port=%d" % DEBUG_PORT,
        "--user-data-dir=%s" % profile, "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    cdp = None
    try:
        cdp = CDP(ws_endpoint())
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        cdp.send("Emulation.setDeviceMetricsOverride",
                 {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False})

        print("== 完整业务流程端到端  %s ==" % FRONT)

        # ---------------- 准备：挑一家今日可发的店 ----------------
        admin_token = api_call("POST", "/api/auth/login",
                               {"role": "admin", "account": ADMIN_ACCOUNT,
                                "password": ADMIN_PWD})["token"]
        shops = api_call("GET", "/api/admin/shops", token=admin_token)
        candidates = [s for s in shops
                      if s.get("status") == "normal" and s.get("canPostToday")]
        if not candidates:
            raise SystemExit("没有「今日可发」的店铺了。重启后端回到干净数据再跑。")
        target = candidates[0]
        TARGET, TARGET_NAME = target["id"], target["name"]
        dish_name = "端到端新菜-%s" % time.strftime("%H%M%S")
        print("  发布用的店：%s %s（今日可发）" % (TARGET, TARGET_NAME))
        print("  新菜名：%s\n" % dish_name)

        # ---------------- ① 商家端：手机号 + 验证码登录 ----------------
        print("[1] 商家端登录（手机号 + 短信验证码）")
        cdp.open("%s/merchant/login.html?api=1" % FRONT)
        cdp.eval("switchMode('code');")
        cdp.eval("document.getElementById('acct').value='%s';" % DEMO_PHONE)
        time.sleep(0.3)
        code = grab_sms_code(cdp)
        check("页面上能取到短信验证码（后端回显 devCode）", bool(code), "拿到 %s" % code)

        if code:
            cdp.eval("document.getElementById('smsCode').value='%s'; doLogin();" % code)
            time.sleep(1.8)
            stored = cdp.eval("localStorage.getItem('merchantShopId')")
            check("验证码登录成功，身份落盘为演示店", stored == DEMO_SHOP, "得到 %s" % stored)
            check("跳转到了工作台", "dashboard" in str(cdp.eval("location.pathname")),
                  str(cdp.eval("location.pathname")))
        cdp.shot("01-商家端验证码登录.png")
        cdp.no_js_error("商家登录页")

        # ---------------- ② 换店发布新菜 ----------------
        print("\n[2] 商家端发布新菜（换一家今日可发的店）")
        cdp.open("%s/merchant/login.html?api=1" % FRONT)
        cdp.eval("switchMode('pwd');"
                 "document.getElementById('acct').value='%s';"
                 "document.getElementById('pwd').value='%s';"
                 "doLogin();" % (TARGET, MERCHANT_PWD))
        time.sleep(1.8)
        stored = cdp.eval("localStorage.getItem('merchantShopId')")
        check("切换到 %s 并登录成功" % TARGET, stored == TARGET, "得到 %s" % stored)

        cdp.open("%s/merchant/publish.html?api=1" % FRONT)
        cooling = cdp.eval("document.getElementById('cooldownView')"
                           ".classList.contains('hidden')")
        check("该店今日可发，发布表单是可见的", cooling is True,
              "cooldownView hidden=%s" % cooling)

        cdp.eval("document.getElementById('dishName').value='%s';"
                 "document.getElementById('shopName').value='%s';"
                 "document.getElementById('dishDesc').value='端到端流程验证，可忽略';"
                 "document.getElementById('dishPrice').value='38';"
                 "if (typeof render === 'function') render();" % (dish_name, TARGET_NAME))

        # 往 file input 里塞一张真 PNG —— 走的就是页面的选图流程
        cdp.eval("""(function(){
            var hex = "%s";
            var arr = new Uint8Array(hex.match(/../g).map(function(h){return parseInt(h,16);}));
            var f = new File([arr], "flow.png", {type:"image/png"});
            var dt = new DataTransfer(); dt.items.add(f);
            var inp = document.getElementById('imgInput');
            inp.files = dt.files;
            inp.dispatchEvent(new Event('change'));
            return true;
        })()""" % PNG_HEX)

        n_img = 0
        for _ in range(48):
            n_img = cdp.eval("(typeof S !== 'undefined' && S.images) ? S.images.length : 0") or 0
            if n_img > 0:
                break
            time.sleep(0.25)
        check("选图后上传完成，进入待发布列表", n_img >= 1, "S.images.length=%s" % n_img)
        cdp.shot("02-发布页已填好.png")

        cdp.eval("submitDish();")
        time.sleep(0.4)
        opened = cdp.eval("document.getElementById('confirmModal')"
                          ".classList.contains('is-open')")
        check("点发布后弹出二次确认", opened is True, "confirmModal=%s" % opened)

        cdp.eval("doSubmit();")
        time.sleep(1.2)
        done = cdp.eval("document.getElementById('successModal')"
                        ".classList.contains('is-open')")
        check("确认后提示「发布成功」", done is True, "successModal=%s" % done)
        cdp.shot("03-发布成功.png")
        cdp.no_js_error("发布页")

        # 后端核对
        m_token = api_call("POST", "/api/auth/login",
                           {"role": "merchant", "account": TARGET,
                            "password": MERCHANT_PWD})["token"]
        mine = api_call("GET", "/api/merchant/dishes/%s" % TARGET, token=m_token)
        hit = [d for d in mine if d.get("name") == dish_name]
        check("后端确实落库了这条新菜", len(hit) == 1, "匹配 %d 条" % len(hit))
        dish_id = hit[0]["id"] if hit else None
        check("新菜状态为在架（normal）", bool(hit) and hit[0].get("status") == "normal",
              hit[0].get("status") if hit else "—")

        # ---------------- ③ 平台端能看到 ----------------
        print("\n[3] 平台端内容管理")
        cdp.open("%s/admin/login.html?api=1" % FRONT)
        cdp.eval("document.getElementById('account').value='%s';"
                 "document.getElementById('password').value='%s';"
                 "document.getElementById('submit').click();" % (ADMIN_ACCOUNT, ADMIN_PWD))
        time.sleep(1.8)
        check("平台端登录后跳转成功",
              "dashboard" in str(cdp.eval("location.pathname")),
              str(cdp.eval("location.pathname")))

        cdp.open("%s/admin/contents.html?api=1" % FRONT)
        txt = cdp.eval("document.body.innerText") or ""
        check("平台端内容管理里能看到这条新菜", dish_name in txt,
              "页面里没找到 %s" % dish_name)
        cdp.shot("04-平台端能看到.png")
        cdp.no_js_error("平台端内容管理")

        # ---------------- ④ 客户端能刷到 ----------------
        print("\n[4] 客户端首页信息流")
        cdp.open("%s/client/feed.html?api=1" % FRONT)
        in_data = cdp.eval(
            "MOCK.buildFeed('nearby').some(function(d){"
            "return d.shopId==='%s' && d.name==='%s';})" % (TARGET, dish_name))
        check("客户端信息流数据里有这条新菜", in_data is True, "buildFeed 命中=%s" % in_data)
        feed_txt = cdp.eval("document.getElementById('feed').innerText") or ""
        check("卡片确实渲染在页面上", dish_name in feed_txt, "feed 里没找到")
        cdp.shot("05-客户端刷到.png")
        cdp.no_js_error("客户端首页")

        # ---------------- ⑤ 平台端下架 ----------------
        print("\n[5] 平台端下架这条内容")
        cdp.open("%s/admin/contents.html?api=1" % FRONT)
        if dish_id:
            r = cdp.eval("removeDish('%s')" % dish_id)
            time.sleep(1.0)
            check("下架动作执行成功（返回值不是 false）", r is not False, "返回 %s" % r)
            after = api_call("GET", "/api/admin/contents", token=admin_token)
            removed = [d for d in (after.get("removed") or []) if d.get("id") == dish_id]
            check("后端「已下架」列表里出现这条", len(removed) == 1,
                  "已下架 %d 条" % len(after.get("removed") or []))
        else:
            check("下架动作执行成功", False, "没拿到 dish_id，跳过")
        cdp.shot("06-平台端已下架.png")

        # ---------------- ⑥ 客户端消失 ----------------
        print("\n[6] 客户端信息流里它应该消失了")
        cdp.open("%s/client/feed.html?api=1" % FRONT)
        still = cdp.eval(
            "MOCK.buildFeed('nearby').some(function(d){"
            "return d.shopId==='%s' && d.name==='%s';})" % (TARGET, dish_name))
        check("下架后客户端信息流里刷不到这条", still is not True, "仍然命中=%s" % still)
        cdp.shot("07-客户端已消失.png")
        cdp.no_js_error("客户端首页（下架后）")

        # ---------------- ⑦ 商家端看得到「已下架」 ----------------
        print("\n[7] 商家端菜品管理")
        cdp.open("%s/merchant/dishes.html?api=1" % FRONT)
        st = cdp.eval(
            "(function(){var a=(MOCK.dishes||[]).filter(function(d){"
            "return d.id==='%s';});return a.length?a[0].status:null;})()" % dish_id)
        check("商家端能看到这条菜已下架（removed）", st == "removed", "status=%s" % st)
        page_txt = cdp.eval("document.body.innerText") or ""
        check("页面上标出了「已下架」", "已下架" in page_txt)
        cdp.shot("08-商家端已下架.png")
        cdp.no_js_error("商家端菜品管理")

        # ---------------- 汇总 ----------------
        print("\n" + "=" * 60)
        print("结果：%d 通过 / %d 失败" % (PASS, FAIL))
        if FAILURES:
            print("失败项：")
            for f in FAILURES:
                print("  - %s" % f)
        print("截图目录：%s" % OUT)
        print("提示：跑完这条链路后 %s 已进入冷却，重启后端即可复原。" % TARGET)

    finally:
        if cdp:
            cdp.close()
        chrome.terminate()

    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
