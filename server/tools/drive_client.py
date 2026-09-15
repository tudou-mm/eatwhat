# -*- coding: utf-8 -*-
"""
用真实 Chrome（CDP 协议）把客户端筛选流程完整走一遍，逐步截图存证。

为什么要真浏览器：筛选是 localStorage + 跨页跳转 + 同步 XHR 的组合，
纯 Node 沙箱验证不了「点一下 chip，返回首页信息流就变了」这种端到端行为。

用法：
    python drive_client.py [前端地址] [截图输出目录]
默认：http://127.0.0.1:5173  和  <项目根>/.shots
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

FRONT = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5173").rstrip("/")
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, ".shots")
DEBUG_PORT = 9333

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

# ---------- CDP 客户端 ----------


class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(
            ws_url, timeout=40, suppress_origin=True
        )
        self.seq = 0

    def send(self, method, params=None):
        self.seq += 1
        mid = self.seq
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            raw = self.ws.recv()
            msg = json.loads(raw)
            if msg.get("id") == mid:
                return msg

    def eval(self, expr, await_promise=False):
        r = self.send(
            "Runtime.evaluate",
            {
                "expression": expr,
                "returnByValue": True,
                "awaitPromise": await_promise,
            },
        )
        res = r.get("result", {})
        if "exceptionDetails" in res:
            raise RuntimeError(str(res["exceptionDetails"])[:400])
        return res.get("result", {}).get("value")

    def goto(self, url):
        self.send("Page.navigate", {"url": url})

    def wait_path(self, fragment, timeout=20):
        """轮询等待地址栏落到目标页"""
        end = time.time() + timeout
        while time.time() < end:
            try:
                if fragment in (self.eval("location.href") or ""):
                    return True
            except Exception:
                pass
            time.sleep(0.3)
        return False

    def wait_ready(self, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            try:
                if self.eval("document.readyState") == "complete":
                    return True
            except Exception:
                pass
            time.sleep(0.3)
        return False

    def shot(self, name):
        r = self.send(
            "Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False}
        )
        data = r.get("result", {}).get("data")
        if not data:
            print("   ! 截图失败: %s" % str(r)[:200])
            return None
        path = os.path.join(OUT, name)
        with open(path, "wb") as f:
            f.write(base64.b64decode(data))
        print("   [shot] %s  (%d KB)" % (name, os.path.getsize(path) // 1024))
        return path

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


# ---------- 启动 Chrome ----------


def launch():
    chrome = next((c for c in CHROME_CANDS if os.path.exists(c)), None)
    if not chrome:
        raise SystemExit("找不到可用的 Chrome / Edge")
    prof = tempfile.mkdtemp(prefix="cdp-prof-")
    proc = subprocess.Popen(
        [
            chrome,
            "--remote-debugging-port=%d" % DEBUG_PORT,
            "--user-data-dir=%s" % prof,
            "--headless=new",
            "--disable-gpu",
            "--no-first-run",
            "--no-default-browser-check",
            "--remote-allow-origins=*",
            "--window-size=390,844",
            "--hide-scrollbars",
            "about:blank",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(50):
        try:
            r = urllib.request.urlopen(
                "http://127.0.0.1:%d/json/version" % DEBUG_PORT, timeout=1
            )
            if r.status == 200:
                print("Chrome 已启动 (%s)" % os.path.basename(chrome))
                return proc, prof
        except Exception:
            time.sleep(0.4)
    proc.kill()
    raise SystemExit("Chrome 调试端口未就绪")


def page_ws():
    for _ in range(30):
        try:
            raw = urllib.request.urlopen(
                "http://127.0.0.1:%d/json/list" % DEBUG_PORT, timeout=2
            ).read()
            for t in json.loads(raw):
                if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
                    return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(0.4)
    raise SystemExit("拿不到 page target")


# ---------- 主流程 ----------


def main():
    os.makedirs(OUT, exist_ok=True)
    proc, prof = launch()
    ok = 0
    bad = 0

    def check(label, cond, detail=""):
        nonlocal ok, bad
        if cond:
            ok += 1
            print("  [PASS] %s %s" % (label, detail))
        else:
            bad += 1
            print("  [FAIL] %s %s" % (label, detail))

    try:
        c = CDP(page_ws())
        c.send("Page.enable")
        c.send("Runtime.enable")

        print("\n=== 1. 打开首页（?api=1，走后端数据）===")
        c.goto(FRONT + "/client/feed.html?api=1")
        c.wait_ready()
        time.sleep(2.5)  # 等图片与预加载

        api_mode = c.eval("!!(window.EAT_API && window.EAT_API.cache.loaded)")
        check("适配层已连上后端", api_mode is True)
        n_cards = c.eval("document.querySelectorAll('.fc').length")
        shops0 = c.eval(
            "JSON.stringify([...document.querySelectorAll('.fc')].map(e=>e.dataset.id))"
        )
        print("   首页卡片数: %s" % n_cards)
        print("   菜品 id: %s" % shops0)
        c.shot("01-feed-未筛选.png")

        print("\n=== 2. 点右上角 ⚙ 进筛选页 ===")
        c.eval("document.querySelector('.filter-btn').click()")
        check("跳到 filter.html", c.wait_path("filter.html"))
        c.wait_ready()
        time.sleep(1.0)
        cleared = c.eval("document.querySelectorAll('#chipPrice .chip.is-on').length")
        check("初始无已选条件", cleared == 0)
        c.shot("02-filter-初始.png")

        print("\n=== 3. 勾选「中等」+「麻辣」 ===")
        r = c.eval(
            """(function(){
              var out = [];
              var p = [...document.querySelectorAll('#chipPrice .chip')]
                        .find(c => c.textContent.trim() === '中等');
              var t = [...document.querySelectorAll('#chipTaste .chip')]
                        .find(c => c.textContent.trim() === '麻辣');
              if (p) { p.click(); out.push('中等'); }
              if (t) { t.click(); out.push('麻辣'); }
              return JSON.stringify({clicked: out,
                resultCount: document.getElementById('resNum').textContent,
                stored: localStorage.getItem('feedFilter')});
            })()"""
        )
        print("   点击: %s" % r)
        c.shot("03-filter-已勾选.png")

        print("\n=== 4. 点「查看结果」回首页 ===")
        c.eval("document.querySelector('.fbar__btn').click()")
        check("跳回 feed.html", c.wait_path("feed.html"))
        c.wait_ready()
        time.sleep(2.0)

        bar = c.eval(
            "document.getElementById('filterBar') ? document.getElementById('filterBar').innerText.replace(/\\s+/g,' ').trim() : null"
        )
        check("首页出现筛选条件条", bool(bar), "→ %s" % bar)
        n2 = c.eval("document.querySelectorAll('.fc').length")
        ids2 = c.eval(
            "JSON.stringify([...document.querySelectorAll('.fc')].map(e=>e.dataset.id))"
        )
        print("   筛选后卡片数: %s   菜品: %s" % (n2, ids2))
        check("信息流确实变了", ids2 != shops0, "%s → %s" % (shops0, ids2))
        c.shot("04-feed-筛选后.png")

        print("\n=== 5. 核对：首页每张卡的档位/口味是否都命中 ===")
        detail = c.eval(
            """(function(){
              var ids = [...document.querySelectorAll('.fc')].map(e=>e.dataset.id);
              var f = MOCK.getFilter();
              return JSON.stringify(ids.map(function(id){
                var d = MOCK.getDish(id);
                return {id:id, name:d.name, tier:d.priceTierId, taste:d.tasteTags,
                        shop:d.shopName,
                        ok: MOCK.matchFilter(d, f)};
              }));
            })()"""
        )
        print("   %s" % detail)
        check(
            "每一条都命中筛选条件",
            '"ok":false' not in (detail or ""),
        )

        print("\n=== 6. 点「清除」恢复全部 ===")
        c.eval(
            "document.querySelector('#filterBar .filter-bar__clear, #filterBar [onclick*=clear]')"
            " && document.querySelector('#filterBar .filter-bar__clear, #filterBar [onclick*=clear]').click()"
        )
        time.sleep(1.5)
        n3 = c.eval("document.querySelectorAll('.fc').length")
        # 注意：不能用 innerText 判断显隐 —— Chrome 对 display:none 的元素
        # 会回退成 textContent，读到的是残留文本。必须看计算样式。
        bar3 = c.eval(
            "(function(){var e=document.getElementById('filterBar');"
            "return e ? getComputedStyle(e).display : 'missing';})()"
        )
        print("   清除后卡片数: %s   条件条 display: %s" % (n3, bar3))
        check("清除后恢复全部内容", str(n3) == str(n_cards), "%s → %s" % (n_cards, n3))
        check("清除后条件条隐藏", bar3 == "none")
        c.shot("05-feed-已清除.png")

        print("\n=== 7. 筛一个组合必然为空的场景（AND 语义下的窄条件）===")
        combo = c.eval(
            """(function(){
              var tiers = MOCK.priceTiers.map(function(t){return t.id;});
              var tastes = MOCK.tasteTags;
              for (var i=0;i<tiers.length;i++){
                for (var j=0;j<tastes.length;j++){
                  var f = {tiers:[tiers[i]], tastes:[tastes[j]]};
                  var hit = MOCK.dishes.filter(function(d){
                    return d.status==='normal' && MOCK.matchFilter(d, f);
                  }).length;
                  if (hit === 0) return JSON.stringify({tiers:[tiers[i]], tastes:[tastes[j]]});
                }
              }
              return null;
            })()"""
        )
        print("   探到必然为空的组合: %s" % combo)
        if combo:
            c.eval("localStorage.setItem('clientFilter', %s); location.reload();" % json.dumps(combo))
            c.wait_ready()
            time.sleep(2.0)
            empty_txt = c.eval(
                "document.getElementById('feed') ? "
                "document.getElementById('feed').innerText.replace(/\\s+/g,' ').trim().slice(0,120) : ''"
            )
            has_clear = c.eval(
                "!!document.querySelector('#feed [onclick*=clearFilter]')"
            )
            print("   空态文案: %s" % empty_txt)
            check("筛空时显示空态且有清除入口", bool(empty_txt) and has_clear is True)
            c.shot("06-feed-空态.png")
        else:
            check("探到必然为空的组合", False, "所有组合都有内容")

        print("\n=== 8. 平台端删档后的遗留条件（用户浏览器存着失效 id）===")
        c.eval(
            "localStorage.setItem('clientFilter', "
            "JSON.stringify({tiers:['tier_ghost'], tastes:['麻辣']})); location.reload();"
        )
        c.wait_ready()
        time.sleep(2.0)
        label8 = c.eval(
            "document.getElementById('filterText') ? "
            "document.getElementById('filterText').textContent : ''"
        )
        raw8 = c.eval("localStorage.getItem('clientFilter')")
        n8 = c.eval("document.querySelectorAll('.fc').length")
        print("   条件条: %r" % label8)
        print("   localStorage: %s" % raw8)
        print("   卡片数: %s" % n8)
        check("不把失效档位的原始 id 显示给用户", "tier_ghost" not in (label8 or ""))
        check("失效档位已从 localStorage 剔除", "tier_ghost" not in (raw8 or ""))
        check("仍有效的口味保留、还能筛出内容", str(n8) != "0", "%s 条" % n8)
        c.shot("07-feed-失效条件已收敛.png")

        # 收尾：清掉筛选，别把脏状态留给下一个打开页面的人
        c.eval("MOCK.clearFilter()")

        print("\n=== 汇总 ===")
        print("通过 %d 项，失败 %d 项" % (ok, bad))
        print("截图目录：%s" % OUT)
        return 0 if bad == 0 else 1

    finally:
        try:
            c.close()
        except Exception:
            pass
        proc.kill()
        time.sleep(1)
        shutil.rmtree(prof, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
