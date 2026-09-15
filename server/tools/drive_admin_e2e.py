# -*- coding: utf-8 -*-
"""
平台端浏览器端到端测试（真实 Chrome + CDP）。

为什么非要用真浏览器：
接口测试能证明"后端落库了"，但证明不了"页面上真的调用了 MOCK.act、
点完 UI 真的刷新了"。本次改造最容易出错的地方恰恰是后者 ——
页面代码没接上，接口再正确也没用。

覆盖：
  1. 数据概览 KPI 来自后端（不是写死的 6 / 1286）
  2. 商家审核：点「通过并上线」→ 待审核列表少一家、角标跟着变
  3. 店铺管理：点「封店」→ 状态列变成"封禁"
  4. 内容管理：点「下架」→ 已下架 tab 计数 +1
  5. 用户管理：确认 MOCK.users 来自后端（后端按举报数倒序，首位是 u_003）
  6. 跨端联动：平台端封店后，客户端页面加载出的店铺列表里没有这家

用法：
    python drive_admin_e2e.py [前端地址]
默认 http://127.0.0.1:5173
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
    import websocket
except ImportError:
    raise SystemExit("需要 websocket-client：pip install websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-admin")
DEBUG_PORT = 9336
FRONT = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5173").rstrip("/")

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

    def open(self, url, wait=1.0, settle=0.9):
        self.errors = []
        self.send("Page.navigate", {"url": url})
        end = time.time() + wait + 20
        while time.time() < end:
            try:
                if self.eval("document.readyState") == "complete":
                    break
            except Exception:
                pass
            time.sleep(0.25)
        time.sleep(settle)
        # 自动确认所有 confirm，否则 headless 下 confirm 直接返回 false，
        # 点击「封店」「通过」这类带二次确认的操作会静默失败
        self.eval("window.confirm = function(){ return true; };")

    def shot(self, name):
        r = self.send("Page.captureScreenshot", {"format": "png"})
        data = (r.get("result") or {}).get("data")
        if not data:
            return
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(base64.b64decode(data))

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


def main():
    os.makedirs(OUT, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="cdp-adm-e2e-")
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
        # 平台端是桌面后台，用真实桌面视口截图才代表用户看到的样子
        cdp.send("Emulation.setDeviceMetricsOverride",
                 {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False})

        print("== 平台端浏览器端到端  %s ==" % FRONT)

        # ---------- 1. 数据概览 ----------
        print("\n[1] 数据概览 KPI 来自后端")
        cdp.open("%s/admin/dashboard.html?api=1" % FRONT)
        on = cdp.eval("EAT_API.isOnline()")
        check("适配层已连上后端", on is True, "isOnline=%s" % on)

        kpi = cdp.eval("""(function(){
          var g=function(id){var e=document.getElementById(id);return e?e.textContent.trim():null};
          return {shop:g('kpiShop'), pend:g('kpiPending'), today:g('kpiDishToday'),
                  user:g('kpiUser'), rep:g('kpiReport'),
                  bars:document.querySelectorAll('#bars .bar-col').length,
                  hot:document.querySelectorAll('#hotShops .rank-mini').length};
        })()""")
        check("在营店铺 KPI 有值", kpi.get("shop") not in (None, "", "—"), str(kpi))
        # 回归：KPI 必须是「在营」(normal+muted)=6，不能拿 shopCount=9（含待审+已驳回）充数
        check("在营店铺 = 6（不是总数 9）", kpi.get("shop") == "6",
              "shop=%s（若为 9 说明又拿 shopCount 当在营数了）" % kpi.get("shop"))
        check("KPI 不再是写死的 1,286", kpi.get("user") != "1,286", "user=%s" % kpi.get("user"))
        check("待审核商家 = 2", kpi.get("pend") == "2", "pend=%s" % kpi.get("pend"))
        check("今日发布菜品 > 0", kpi.get("today") not in (None, "0", "—"), "today=%s" % kpi.get("today"))
        check("趋势图 7 根柱", kpi.get("bars") == 7, "bars=%s" % kpi.get("bars"))
        check("热门店铺有行", (kpi.get("hot") or 0) > 0, "hot=%s" % kpi.get("hot"))
        cdp.shot("e2e-01-dashboard.png")

        # ---------- 2. 商家审核 ----------
        print("\n[2] 商家审核：通过并上线")
        cdp.open("%s/admin/audit-shops.html?api=1" % FRONT)
        before = cdp.eval("document.querySelectorAll('#pendingList .pending-item').length")
        badge_before = cdp.eval("document.getElementById('badgeNum').textContent.trim()")
        name = cdp.eval("(MOCK.pendingShops[0]||{}).name")
        check("初始待审核列表有数据", before > 0, "count=%s" % before)
        check("侧边栏角标与列表一致", badge_before == str(before), "%s vs %s" % (badge_before, before))

        cdp.eval("approve()")
        time.sleep(0.7)
        after = cdp.eval("document.querySelectorAll('#pendingList .pending-item').length")
        badge_after = cdp.eval("document.getElementById('badgeNum').textContent.trim()")
        check("通过后待审核少一家", after == before - 1, "%s → %s" % (before, after))
        check("角标同步变小", badge_after == str(after), "%s vs %s" % (badge_after, after))

        approved_names = cdp.eval(
            "(MOCK.approvedShops||[]).map(function(s){return s.name}).join('|')")
        check("已通过列表含刚审核的店", name and name in (approved_names or ""),
              "找 %s / 实际 %s" % (name, (approved_names or "")[:80]))
        cdp.shot("e2e-02-audit.png")

        # ---------- 3. 店铺管理：封店 ----------
        print("\n[3] 店铺管理：封店")
        cdp.open("%s/admin/shops.html?api=1" % FRONT)
        target = cdp.eval("""(function(){
          var s=MOCK.shops.find(function(x){return x.status==='normal'});
          return s?{id:s.id,name:s.name}:null;
        })()""")
        check("取到一家在营店铺", isinstance(target, dict), str(target))
        tname = target["name"]

        cdp.eval("setStatus('%s','banned')" % target["id"])
        time.sleep(0.7)
        st = cdp.eval("""(function(){
          var s=MOCK.shops.find(function(x){return x.id==='%s'});
          return s?s.status:null;
        })()""" % target["id"])
        check("内存里状态已变 banned", st == "banned", "status=%s" % st)

        row_txt = cdp.eval("""(function(){
          var rows=document.querySelectorAll('#body tr');
          for(var i=0;i<rows.length;i++){
            if(rows[i].innerText.indexOf('%s')>=0) return rows[i].innerText.replace(/\\s+/g,' ');
          }
          return null;
        })()""" % tname)
        check("表格里该店显示「封禁」", row_txt and "封禁" in row_txt, str(row_txt)[:100])

        # 回归：店铺名列被压成「一列一字」竖排。宽度过小或高度远高于单行即为回归。
        cell = cdp.eval("""(function(){
          var n=document.querySelector('.shop-cell__name');
          if(!n) return null;
          var r=n.getBoundingClientRect();
          var lh=parseFloat(getComputedStyle(n).lineHeight)||18;
          return {w:Math.round(r.width), h:Math.round(r.height), lh:Math.round(lh)};
        })()""")
        check("店铺名列未被压成竖排",
              isinstance(cell, dict) and cell.get("w", 0) >= 70 and cell.get("h", 999) <= cell.get("lh", 18) * 2,
              str(cell))
        cdp.shot("e2e-03-shops-banned.png")

        # ---------- 4. 跨端联动：客户端看不到被封的店 ----------
        print("\n[4] 跨端联动：平台端封店 → 客户端消失")
        cdp.open("%s/client/feed.html?api=1" % FRONT)
        client_ids = cdp.eval("(MOCK.shops||[]).map(function(s){return s.id}).join(',')")
        check("客户端店铺列表不含被封的店", target["id"] not in (client_ids or ""),
              "被封=%s / 客户端=%s" % (target["id"], (client_ids or "")[:120]))
        cdp.shot("e2e-04-client-after-ban.png")

        # 解封恢复，避免影响后续演示
        cdp.open("%s/admin/shops.html?api=1" % FRONT)
        cdp.eval("setStatus('%s','normal')" % target["id"])
        time.sleep(0.6)
        cdp.open("%s/client/feed.html?api=1" % FRONT)
        back = cdp.eval("(MOCK.shops||[]).map(function(s){return s.id}).join(',')")
        check("解封后客户端又能看到", target["id"] in (back or ""), "解封=%s" % target["id"])

        # ---------- 5. 内容管理：下架 ----------
        print("\n[5] 内容管理：下架 / 已下架 tab")
        cdp.open("%s/admin/contents.html?api=1" % FRONT)
        ndish = cdp.eval("document.getElementById('nDish').textContent.trim()")
        nrem = cdp.eval("document.getElementById('nRemoved').textContent.trim()")
        did = cdp.eval("(MOCK.dishes[0]||{}).id")
        check("菜品 tab 计数 > 0", ndish not in ("0", None), "nDish=%s" % ndish)

        cdp.eval("removeDish('%s')" % did)
        time.sleep(0.7)
        nrem2 = cdp.eval("document.getElementById('nRemoved').textContent.trim()")
        check("下架后已下架计数 +1", int(nrem2) == int(nrem) + 1, "%s → %s" % (nrem, nrem2))

        cdp.eval("switchTab('removed', document.querySelector('.t4[data-t=\"removed\"]'))")
        time.sleep(0.5)
        rows = cdp.eval("document.querySelectorAll('#dishBody tr').length")
        check("已下架列表里有内容", rows > 0, "rows=%s" % rows)
        cdp.shot("e2e-05-contents-removed.png")

        # 打标签
        cdp.eval("switchTab('dishes', document.querySelector('.t4[data-t=\"dishes\"]'))")
        time.sleep(0.4)
        cdp.eval("restore('%s')" % did)
        time.sleep(0.6)

        # ---------- 6. 用户管理：数据确实来自后端 ----------
        print("\n[6] 用户管理：数据源确认")
        cdp.open("%s/admin/users.html?api=1" % FRONT)
        u0 = cdp.eval("(MOCK.users[0]||{}).id")
        rows = cdp.eval("document.querySelectorAll('#body tr').length")
        # 本地 mock 的顺序是 u_001 开头；后端按举报次数倒序，首位是 u_003
        check("用户列表首位是后端排序结果 u_003", u0 == "u_003",
              "首位=%s（若是 u_001 说明还在用本地假数据）" % u0)
        check("表格有行", rows > 0, "rows=%s" % rows)
        cdp.shot("e2e-06-users.png")

        # ---------- 7. 价格档 ----------
        print("\n[7] 价格档 / 标签配置")
        cdp.open("%s/admin/config.html?api=1" % FRONT)
        tier_rows = cdp.eval("document.querySelectorAll('#tierList .tier-row').length")
        tags = cdp.eval("document.querySelectorAll('#tagCloud .cloud-tag').length")
        cuis = cdp.eval("document.querySelectorAll('#cuisineCloud .cloud-tag').length")
        check("价格档有行", tier_rows > 0, "tiers=%s" % tier_rows)
        check("口味标签有值", tags > 0, "tags=%s" % tags)
        check("菜系有值（后端给 10 个）", cuis == 10, "cuisines=%s" % cuis)
        cdp.shot("e2e-07-config.png")

        # ---------- 8. 页面 JS 无异常 ----------
        print("\n[8] 控制台异常检查")
        noisy = [e for e in cdp.errors if "favicon" not in e]
        check("最后访问的页面无 JS 异常", not noisy, str(noisy[:2]))

        # 逐页扫一遍异常
        bad_pages = []
        for page in ["dashboard", "audit-shops", "shops", "contents",
                     "ranking", "rules", "config", "users"]:
            cdp.open("%s/admin/%s.html?api=1" % (FRONT, page))
            errs = [e for e in cdp.errors if "favicon" not in e]
            if errs:
                bad_pages.append("%s: %s" % (page, errs[0][:80]))
        check("8 个平台端页面均无 JS 异常", not bad_pages, str(bad_pages))

        print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
        if FAILURES:
            print("失败项：")
            for f in FAILURES:
                print("  - %s" % f)
        print("截图目录：%s" % OUT)
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
