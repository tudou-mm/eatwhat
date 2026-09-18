# -*- coding: utf-8 -*-
"""
验证「后端托管前端 + 同源 /api」这条新链路（v1.9）。

为什么值得单独一条测试
----------------------
这次改动把前端从 5173 挪到了后端 8080，牵扯三件**都会静默出错**的事：

  ① 静态资源映射挂错前缀 → 页面 404，但 /api 还是好的（看着像前端问题）
  ② `API_BASE` 该用相对路径却写死 localhost → **电脑上完全正常、手机上全挂**
     （手机上的 localhost 是手机自己）—— 这是最危险的一类，因为开发时看不出来
  ③ HTML 被长缓存 → 改了页面手机上还是旧的，刷新也不管用

前两条都是「本机测着好、真实环境挂」的典型，所以必须在**真的从 8080 打开页面**
的前提下验证，光看 curl 的 200 不够。

用法（需先后端 + 后端自身托管前端，即 `python dev.py`）：
    python drive_static_host.py
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

try:
    import websocket
except ImportError:
    raise SystemExit("需要 websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-host")
os.makedirs(OUT, exist_ok=True)

# ⚠️ 这里打的是**后端端口**，不是 5173 —— 本测试要验的就是「后端自己发前端」。
HOST_PORT = sys.argv[1] if len(sys.argv) > 1 else "8080"
BASE = "http://127.0.0.1:%s" % HOST_PORT
DEBUG_PORT = 9232

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print("  [%s] %s%s" % ("OK" if cond else "FAIL", name,
                           ("   " + str(detail)) if detail else ""))


def find_chrome():
    for c in CHROME_CANDS:
        if os.path.exists(c):
            return c
    raise SystemExit("找不到 Chrome/Edge")


def http(url, method="GET", timeout=8):
    """返回 (status, headers, body_bytes)；异常也翻译成状态码，不用 try 包一层"""
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers or {}), e.read()
    except Exception as e:
        return 0, {}, str(e).encode()


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

    def shot(self, name):
        r = self.send("Page.captureScreenshot", {"format": "png"})
        data = (r.get("result") or {}).get("data")
        if data:
            with open(os.path.join(OUT, name), "wb") as f:
                f.write(base64.b64decode(data))

    def errs(self):
        return [e for e in self.errors if e and "favicon" not in e.lower()]

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def fresh_profile(name):
    """每次启动前删干净 —— 持久 profile 会缓存旧 JS，导致断言看到旧代码。
    详见 drive_user_loc.py 的 fresh_profile 注释（本项目踩过一次）。"""
    prof = os.path.join(OUT, name)
    shutil.rmtree(prof, ignore_errors=True)
    return prof


def ws_endpoint():
    end = time.time() + 25
    while time.time() < end:
        try:
            with urllib.request.urlopen(
                    "http://127.0.0.1:%d/json/list" % DEBUG_PORT, timeout=2) as r:
                tabs = json.loads(r.read().decode("utf-8"))
            for t in tabs:
                if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
                    return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(0.4)
    raise SystemExit("等不到 Chrome 调试端口")


def main():
    # ============ 1. HTTP 层：静态资源与接口并存 ============
    print("\n[1] 后端自己发前端（HTTP 层）")
    st, _, body = http(BASE + "/client/feed.html")
    check("客户端首页由后端发出（不是 5173）", st == 200, st)
    check("首页内容是 HTML（不是 JSON 错误体）",
          st == 200 and b"<html" in body.lower(), body[:60])

    # 根路径要能直接进客户端首页 —— 手机用户输域名不该看到 404
    st, _, _ = http(BASE + "/")
    check("访问根路径可直达（重定向或直接渲染）", st in (200, 302), st)

    pages = ["client/feed.html", "client/shop.html", "client/dish.html",
             "client/filter.html", "client/login.html", "client/profile.html",
             "merchant/login.html", "merchant/dashboard.html",
             "admin/login.html", "admin/shops.html"]
    bad = []
    for p in pages:
        st, _, _ = http("%s/%s" % (BASE, p))
        if st != 200:
            bad.append("%s=%s" % (p, st))
    check("三端页面全部可取（%d 个）" % len(pages), not bad, " ".join(bad))

    assets = ["assets/data/mock.js", "assets/js/api.js", "assets/js/user-loc.js",
              "assets/js/map-picker.js", "assets/css/base.css"]
    bad = []
    for a in assets:
        st, _, b = http("%s/%s" % (BASE, a))
        if st != 200 or len(b) < 200:
            bad.append("%s=%s/%d" % (a, st, len(b)))
    check("前端静态资源全部可取（%d 个）" % len(assets), not bad, " ".join(bad))

    # ⚠️ 接口绝不能被静态处理器吃掉。挂 `/**` 就会这样，而且可能只坏一半。
    st, _, b = http(BASE + "/api/health")
    check("接口没被静态资源抢走（/api/health 正常）",
          st == 200 and b"eatwhat-server" in b, st)

    # ============ 2. 缓存策略 ============
    print("\n[2] 缓存策略（改页面要立刻生效，改静态资源可缓存）")
    st, h, _ = http(BASE + "/client/feed.html")
    cc = (h.get("Cache-Control") or "").lower()
    check("HTML 不做长缓存（否则改了页面手机上看到旧的）",
          "no-store" not in cc and "max-age" not in cc, cc or "(空)")

    st, h, _ = http(BASE + "/assets/data/mock.js")
    cc = (h.get("Cache-Control") or "").lower()
    check("静态资源可缓存（省流量、加载快）",
          "max-age" in cc, cc or "(空)")

    # ============ 3. 浏览器层：同源 /api 真的通 ============
    print("\n[3] 浏览器从后端打开，且接口走同源相对路径 /api")
    chrome = subprocess.Popen([
        find_chrome(), "--headless=new", "--disable-gpu", "--no-first-run",
        "--remote-debugging-port=%d" % DEBUG_PORT, "--window-size=390,844",
        "--user-data-dir=" + fresh_profile("_profile"), "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    c = CDP(ws_endpoint())
    try:
        c.send("Runtime.enable")
        c.send("Page.enable")

        c.open(BASE + "/client/feed.html?api=1")
        check("页面能正常加载（无 JS 异常）", not c.errs(), c.errs()[:2])

        base_val = c.eval("window.__API_BASE__ || null")
        # ⚠️ 核心断言：必须是**相对路径** /api，不能是 http://localhost:8080/api。
        # 写死了在电脑上照样全绿，但手机上的 localhost 指手机自己 → 全挂。
        check("接口地址是同源相对路径 /api（不是写死的 localhost）",
              base_val == "/api", base_val)
        check("接口地址里不含 localhost（手机连不上写死的 localhost）",
              "localhost" not in str(base_val), base_val)

        check("同源取到了真实店铺数据（不是降级到假数据）",
              (c.eval("window.EAT_API && EAT_API.cache.loaded") is True),
              c.eval("typeof EAT_API !== 'undefined' ? EAT_API.cache.shops.length : 'no-api'"))
        shops = c.eval("window.EAT_API ? EAT_API.cache.shops.length : 0")
        check("店铺数据条数 > 0", isinstance(shops, int) and shops > 0, shops)

        # 页面真的渲染出来了 —— 这才是「用户看得到」的判据
        cards = c.eval("document.querySelectorAll('.fc').length")
        check("信息流卡片已渲染", isinstance(cards, int) and cards > 0, cards)
        c.shot("01-feed-from-backend.png")

        # 同源之后不该再有跨域；确认没有出现 CORS 报错
        errs = " ".join(c.errs()).lower()
        check("没有跨域报错（同源本就不该有）",
              "cors" not in errs and "cross-origin" not in errs, errs[:80] or "(无)")

        # ============ 4. 跨页仍带同源地址 ============
        print("\n[4] 跨页保持同源（不能某个页面单独写死）")
        c.open(BASE + "/client/shop.html?id=s_001&api=1")
        b2 = c.eval("window.__API_BASE__ || null")
        check("店铺详情页同样是 /api", b2 == "/api", b2)
        check("店铺详情页无 JS 异常", not c.errs(), c.errs()[:2])
        c.shot("02-shop-from-backend.png")

        c.open(BASE + "/admin/login.html?api=1")
        b3 = c.eval("window.__API_BASE__ || null")
        check("平台端同样是 /api（三端口径一致）", b3 == "/api", b3)

        # ============ 5. 缺失资源要 404 而不是 500 ============
        print("\n[5] 缺失资源返回 404（不是 500）")
        st, _, b = http(BASE + "/client/does-not-exist.html")
        check("不存在的页面 → 404", st == 404, st)
        # 提示应该是给人看的中文，不是 Spring 那句 No static resource ...
        txt = b.decode("utf-8", "replace")
        check("404 的提示是给人看的（不是 Spring 原始措辞）",
              "找不到" in txt, txt[:80])

    finally:
        c.close()
        chrome.terminate()

    print("\n" + "=" * 54)
    print("结果：%d 通过 / %d 失败" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：")
        for f in FAIL:
            print("  - " + f)
    print("截图目录：%s" % OUT)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
