# -*- coding: utf-8 -*-
"""
平台端 8 个页面的端到端体检。

为什么要真浏览器：平台端页面的问题大多是「脚本报错后整页空白」
或「数据没接上后端」，纯 HTTP 冒烟测不出来。

用法：
    python drive_admin.py                 # 默认测 api=1（接后端）
    python drive_admin.py --local         # 测 api=0（纯本地假数据）
    python drive_admin.py --front http://127.0.0.1:5173
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

try:
    import websocket  # websocket-client
except ImportError:
    raise SystemExit("需要 websocket-client：pip install websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-admin")
DEBUG_PORT = 9334

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

PAGES = [
    ("dashboard.html", "数据概览"),
    ("audit-shops.html", "商家审核"),
    ("shops.html", "店铺管理"),
    ("contents.html", "内容管理"),
    ("ranking.html", "推荐排名"),
    ("rules.html", "发布规则"),
    ("config.html", "价格档 / 标签"),
    ("users.html", "用户管理"),
]


class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=40, suppress_origin=True)
        self.seq = 0
        self.errors = []
        self.logs = []

    def drain(self):
        """收掉当前积压的事件（非阻塞）"""
        self.ws.settimeout(0.05)
        try:
            while True:
                self._handle(json.loads(self.ws.recv()))
        except Exception:
            pass
        finally:
            self.ws.settimeout(40)

    def _handle(self, msg):
        m = msg.get("method")
        p = msg.get("params") or {}
        if m == "Runtime.exceptionThrown":
            d = p.get("exceptionDetails") or {}
            txt = (d.get("exception") or {}).get("description") or d.get("text") or ""
            line = d.get("lineNumber")
            self.errors.append("EXCEPTION: %s (line %s)" % (txt.split("\n")[0], line))
        elif m == "Runtime.consoleAPICalled":
            t = p.get("type")
            args = p.get("args") or []
            txt = " ".join(str(a.get("value", a.get("description", ""))) for a in args)
            if t == "error":
                self.errors.append("console.error: %s" % txt)
            else:
                self.logs.append("[%s] %s" % (t, txt))
        elif m == "Log.entryAdded":
            e = p.get("entry") or {}
            if e.get("level") == "error":
                self.errors.append("Log: %s  <%s>" % (e.get("text"), e.get("url", "")))

    def send(self, method, params=None, timeout=40):
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
        r = self.send("Runtime.evaluate",
                      {"expression": expr, "returnByValue": True})
        res = r.get("result", {})
        if "exceptionDetails" in res:
            return {"__err": str(res["exceptionDetails"])[:300]}
        return res.get("result", {}).get("value")

    def goto(self, url):
        self.send("Page.navigate", {"url": url})

    def wait_ready(self, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            try:
                if self.eval("document.readyState") == "complete":
                    return True
            except Exception:
                pass
            time.sleep(0.25)
        return False

    def shot(self, name):
        r = self.send("Page.captureScreenshot", {"format": "png"})
        data = (r.get("result") or {}).get("data")
        if not data:
            return None
        path = os.path.join(OUT, name)
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))
        return path

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


def start_chrome(exe, profile):
    return subprocess.Popen([
        exe, "--headless=new", "--disable-gpu", "--no-first-run",
        "--no-default-browser-check", "--disable-extensions",
        "--remote-debugging-port=%d" % DEBUG_PORT,
        "--user-data-dir=%s" % profile,
        "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


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


# 每个页面「后端模式必须为真」的断言。返回 (通过?, 说明)
CHECKS = {
    "dashboard.html": r"""
      (function(){
        var n = document.querySelectorAll('#hotShops .rank-mini').length;
        var pend = document.getElementById('kpiPending').textContent.trim();
        var rep = document.getElementById('kpiReport').textContent.trim();
        return {ok: n>0 && pend!=='' && rep!=='', info:'热门店铺'+n+'行, 待审核='+pend+', 举报='+rep};
      })()
    """,
    "audit-shops.html": r"""
      (function(){
        var n = document.querySelectorAll('#pendingList .pending-item').length;
        var badge = document.getElementById('badgeNum').textContent.trim();
        return {ok: n>0, info:'待审核列表'+n+'家, badge='+badge};
      })()
    """,
    "shops.html": r"""
      (function(){
        var rows = document.querySelectorAll('tbody tr').length;
        return {ok: rows>0, info:'店铺表格'+rows+'行'};
      })()
    """,
    "contents.html": r"""
      (function(){
        var rows = document.querySelectorAll('#dishBody tr').length;
        var n = document.getElementById('nDish').textContent.trim();
        var nr = document.getElementById('nReport').textContent.trim();
        return {ok: rows>0 && n!=='0', info:'菜品'+rows+'行, nDish='+n+', nReport='+nr};
      })()
    """,
    "ranking.html": r"""
      (function(){
        var n = document.querySelectorAll('.rank-row, .rk-item, [data-rank-item]').length;
        return {ok: true, info:'排名行'+n};
      })()
    """,
    "rules.html": r"""
      (function(){
        var n = document.querySelectorAll('tbody tr').length;
        return {ok: n>0, info:'规则表格'+n+'行'};
      })()
    """,
    "config.html": r"""
      (function(){
        var t = document.querySelectorAll('.tier-preview').length;
        var tags = (window.MOCK && MOCK.tasteTags || []).length;
        var cuis = (window.MOCK && MOCK.cuisines || []).length;
        return {ok: t>0 && tags>0 && cuis>0, info:'档位'+t+', 口味'+tags+', 菜系'+cuis};
      })()
    """,
    "users.html": r"""
      (function(){
        var rows = document.querySelectorAll('tbody tr').length;
        var all = (window.MOCK && MOCK.users || []).length;
        return {ok: rows>0, info:'用户'+rows+'行, MOCK.users='+all};
      })()
    """,
}


def main():
    args = sys.argv[1:]
    front = "http://127.0.0.1:5173"
    if "--front" in args:
        front = args[args.index("--front") + 1].rstrip("/")
    mode = "0" if "--local" in args else "1"

    os.makedirs(OUT, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="cdp-admin-")
    chrome = start_chrome(find_chrome(), profile)
    cdp = None
    passed = failed = 0
    try:
        cdp = CDP(ws_endpoint())
        cdp.send("Page.enable")
        cdp.send("Runtime.enable")
        cdp.send("Log.enable")

        print("== 平台端体检  mode=%s ==" % ("api=1 接后端" if mode == "1" else "本地假数据"))
        for i, (page, title) in enumerate(PAGES, 1):
            url = "%s/admin/%s?api=%s" % (front, page, mode)
            cdp.errors = []
            cdp.logs = []
            cdp.send("Page.navigate", {"url": url})
            cdp.drain()
            cdp.wait_ready()
            time.sleep(0.8)   # 等页面自己的 init 跑完
            cdp.drain()

            res = cdp.eval(CHECKS[page])
            cdp.drain()
            shot = cdp.shot("%02d-%s.png" % (i, page.replace(".html", "")))

            err = cdp.errors[:]
            api_log = [l for l in cdp.logs if "[api]" in l]
            ok = (not err) and isinstance(res, dict) and res.get("ok")
            if ok:
                passed += 1
            else:
                failed += 1

            print("\n%s %s  %s" % ("[OK]  " if ok else "[FAIL]", page, title))
            if isinstance(res, dict):
                if "__err" in res:
                    print("       ! 断言脚本异常: %s" % res["__err"][:160])
                else:
                    print("       %s" % res.get("info"))
            for l in api_log:
                print("       %s" % l[:150])
            for e in err:
                print("       !! %s" % e[:220])

        print("\n---- 结果: %d 通过 / %d 失败 ----" % (passed, failed))
        print("截图目录: %s" % OUT)
    finally:
        if cdp:
            cdp.close()
        try:
            chrome.terminate()
        except Exception:
            pass
        time.sleep(0.5)
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    main()
