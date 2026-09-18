# -*- coding: utf-8 -*-
"""
商家端浏览器端到端测试（真实 Chrome + CDP）。

为什么非要用真浏览器：
接口测试能证明"后端落库了"，但证明不了"页面上真的调用了 MOCK.act、
点完 UI 真的刷新了"。本次改动最容易出错的地方恰恰是后者。

覆盖：
  1. 登录写入商家身份（localStorage.merchantShopId）—— 商家端各页靠它取数
  2. 工作台：店铺名 / 菜品数 / 冷却倒计时都来自后端（不是写死的 24:00:00）
  3. 菜品管理：条数 == 后端；下架 → 状态变「已下架」→ 恢复
  4. 评论管理：条数 == 后端；回评 → 状态变「已回复」→ 清除回评
  5. 店铺资料：表单回显后端值；保存后 MOCK 里的值同步更新
  6. 发布页：冷却中应显示倒计时视图，且秒数 = 后端 cooldownSeconds
  7. 审核页：状态由服务端决定（normal → 审核通过），演示开关自动隐藏
  8. 全流程无 JS 异常

用法：
    python drive_merchant_e2e.py [前端地址]
默认 http://127.0.0.1:5173
"""
import base64
import json
import os
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
OUT = os.path.join(ROOT, ".shots-merchant")
DEBUG_PORT = 9337
FRONT = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5173").rstrip("/")

# 演示商家：mock 里 currentMerchant.shopId，也是 DataSeeder 里的第一家店（PINNED）
DEMO_SHOP = "s_001"

# ⚠️ 这里**故意不写死店名**（血泪）。
#
# 曾经有 `DEMO_SHOP_NAME = "蜀香小馆"`，并断言「页面店名 == 这个常量」。
# 22 家演示数据时 `s_001` 恰好叫这个名字，所以碰巧通过。
# 数据扩到仪征 1620 家真实店铺后，`s_001` 被 PINNED 成「永安水煮活鱼」
# （保住商家端登录测试依赖的那条记录），这条断言立刻假红。
#
# 真正该断言的是「**页面显示的是后端真数据，不是 mock.js 里的写死假数据**」——
# 那就应该拿**后端返回的店名**去比（见第 2 节），而不是跟一个硬编码常量比。
# 硬编码等价于「把测试绑在某一条种子记录上」，数据一换就碎。
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


MERCHANT_TOKEN = None


def login_token(account, password="123456", role="merchant"):
    """拿一张商家 JWT。api_data() 要用它读后端权威数据（接口现在要鉴权了）。"""
    global MERCHANT_TOKEN
    base = os.environ.get("EAT_API_BASE", "http://127.0.0.1:8080")
    payload = json.dumps({"role": role, "account": account, "password": password}).encode()
    req = urllib.request.Request(base + "/api/auth/login", data=payload, method="POST",
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        j = json.loads(r.read().decode("utf-8"))
    if j.get("code") != 0:
        raise SystemExit("商家登录失败（%s）：%s" % (account, j.get("msg")))
    MERCHANT_TOKEN = j["data"]["token"]
    return MERCHANT_TOKEN


def api_data(path):
    """直接问后端要权威数据，用来和 DOM 里显示的值对比。

    不硬编码任何数量 —— 数据一改测试就红，而且红得没意义。
    断言「页面显示 == 后端返回」才真正在验证页面接对了后端。
    """
    base = os.environ.get("EAT_API_BASE", "http://127.0.0.1:8080")
    req = urllib.request.Request(base + "/api" + path)
    if MERCHANT_TOKEN:
        req.add_header("Authorization", "Bearer " + MERCHANT_TOKEN)
    with urllib.request.urlopen(req, timeout=10) as r:
        j = json.loads(r.read().decode("utf-8"))
    if j.get("code") != 0:
        raise SystemExit("后端 %s 返回异常：%s" % (path, j.get("msg")))
    return j["data"]


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

    def open(self, url, settle=1.0):
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
        # headless 下 confirm 直接返回 false，下架/恢复这类带二次确认的操作会静默失败
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


def main():
    os.makedirs(OUT, exist_ok=True)
    profile = tempfile.mkdtemp(prefix="cdp-mch-e2e-")
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

        print("== 商家端浏览器端到端  %s ==" % FRONT)

        login_token(DEMO_SHOP)
        BACK = api_data("/merchant/bootstrap?shopId=" + DEMO_SHOP)
        SHOP = BACK["shop"]
        n_dishes = len(BACK["dishes"])
        n_comments = len(BACK["comments"])
        print("  后端：%s  菜品 %d  评论 %d  今日可发 %s  冷却 %ds"
              % (SHOP["name"], n_dishes, n_comments,
                 BACK["canPostToday"], BACK["cooldownSeconds"]))

        # ---------- 1. 登录写入商家身份 ----------
        print("\n[1] 登录：写入商家身份")
        cdp.open("%s/merchant/login.html?api=1" % FRONT)
        cdp.eval("document.getElementById('acct').value='13800138000'; doLogin();")
        time.sleep(1.2)
        stored = cdp.eval("localStorage.getItem('merchantShopId')")
        check("登录后写入 merchantShopId", stored == DEMO_SHOP, "得到 %s" % stored)
        cdp.no_js_error("登录页")

        # ---------- 2. 工作台 ----------
        print("\n[2] 工作台：数据来自后端")
        cdp.open("%s/merchant/dashboard.html?api=1" % FRONT)
        on = cdp.eval("EAT_API.isOnline()")
        check("适配层已连上后端", on is True, "isOnline=%s" % on)

        d = cdp.eval("""(function(){
          var g=function(id){var e=document.getElementById(id);return e?e.textContent.trim():null};
          return {shop:(MOCK.getShop(MOCK.currentMerchant.shopId)||{}).name,
                  dishes:document.querySelectorAll('#dishGrid > *').length,
                  cd:g('cd'),
                  title:(document.querySelector('.sc__title')||{}).textContent};
        })()""")
        check("页面读到的就是我这家店", d.get("shop") == SHOP["name"],
              "页面=%s 后端=%s" % (d.get("shop"), SHOP["name"]))
        # 「不是写死的假数据」＝ 页面店名必须是**后端真名**，且**非空**。
        # 不与任何硬编码常量比 —— 那等于把测试绑死在一条种子记录上（见文件头说明）。
        check("店铺名来自后端真数据（不是空值/占位假数据）",
              bool(d.get("shop")) and d.get("shop") == SHOP["name"],
              "页面=%s 后端=%s" % (d.get("shop"), SHOP["name"]))
        check("今日状态卡有内容", bool(d.get("title")), str(d.get("title")))

        # 冷却倒计时要跟后端口径一致：s_001 今天已发布 → 显示倒计时
        if not BACK["canPostToday"]:
            cd_txt = d.get("cd") or ""
            parts = [p.strip() for p in cd_txt.split(":")] if ":" in cd_txt else []
            page_sec = None
            if len(parts) == 3 and all(p.isdigit() for p in parts):
                page_sec = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
            check("冷却中显示倒计时", page_sec is not None, "cd=%r" % cd_txt)
            if page_sec is not None:
                check("倒计时秒数 == 后端 cooldownSeconds（±30s）",
                      abs(page_sec - BACK["cooldownSeconds"]) <= 30,
                      "页面=%s 后端=%s" % (page_sec, BACK["cooldownSeconds"]))
                check("倒计时不再是写死的 23:45:12",
                      page_sec != 23 * 3600 + 45 * 60 + 12, "页面=%s" % page_sec)
        else:
            check("可发布时不显示倒计时", not d.get("cd"), "cd=%r" % d.get("cd"))
        cdp.no_js_error("工作台")
        cdp.shot("e2e-01-dashboard.png")

        # ---------- 3. 菜品管理 ----------
        print("\n[3] 菜品管理")
        cdp.open("%s/merchant/dishes.html?api=1" % FRONT)
        rows = cdp.eval("document.querySelectorAll('#body tr').length")
        check("菜品行数 == 后端", rows == n_dishes, "页面=%s 后端=%s" % (rows, n_dishes))
        check("菜品有封面图", cdp.eval(
            "(document.querySelector('#body tr img')||{}).src ? true : false") is True)

        # 找一道在架的菜，下架 → 恢复
        live = next((x for x in BACK["dishes"] if x["status"] != "removed"), None)
        if live:
            cdp.eval("offlineDish('%s')" % live["id"])
            time.sleep(0.6)
            st = cdp.eval("(MOCK.getDish('%s')||{}).status" % live["id"])
            check("下架后状态变 removed", st == "removed", "得到 %s" % st)
            has_tag = cdp.eval(
                "document.getElementById('body').innerHTML.indexOf('已下架') > -1")
            check("列表出现「已下架」标签", has_tag is True)

            cdp.eval("restoreDish('%s')" % live["id"])
            time.sleep(0.6)
            st2 = cdp.eval("(MOCK.getDish('%s')||{}).status" % live["id"])
            check("恢复后状态回到 normal", st2 == "normal", "得到 %s" % st2)
        cdp.no_js_error("菜品管理")
        cdp.shot("e2e-02-dishes.png")

        # ---------- 4. 评论管理 ----------
        print("\n[4] 评论管理")
        cdp.open("%s/merchant/comments.html?api=1" % FRONT)
        crows = cdp.eval("document.querySelectorAll('.cm-row').length")
        check("评论行数 == 后端", crows == n_comments,
              "页面=%s 后端=%s" % (crows, n_comments))
        check("评论渲染了头像和内容", cdp.eval(
            "(document.querySelector('.cm-av img')||{}).src ? true : false") is True)

        if n_comments:
            cid = BACK["comments"][0]["id"]
            reply_before = cdp.eval("(MOCK.comments.find(function(c){return c.id==='%s'})||{}).reply" % cid)
            # 先清干净，保证从"未回复"开始
            cdp.eval("MOCK.act('merchant.comment.clearReply',{id:'%s'})" % cid)
            cdp.eval("render()")
            cdp.eval("openReply('%s')" % cid)
            time.sleep(0.3)
            cdp.eval("document.getElementById('ri_%s').value='感谢反馈，欢迎再来！'" % cid)
            cdp.eval("sendReply('%s')" % cid)
            time.sleep(0.6)
            rp = cdp.eval("(MOCK.comments.find(function(c){return c.id==='%s'})||{}).reply" % cid)
            check("回评后 reply 有内容",
                  isinstance(rp, dict) and rp.get("content") == "感谢反馈，欢迎再来！",
                  str(rp))
            check("列表出现「已回复」标记", cdp.eval(
                "document.getElementById('list').innerHTML.indexOf('已回复') > -1") is True)

            cdp.eval("MOCK.act('merchant.comment.clearReply',{id:'%s'}); render()" % cid)
            time.sleep(0.3)
            rp2 = cdp.eval("(MOCK.comments.find(function(c){return c.id==='%s'})||{}).reply" % cid)
            check("清除回评后 reply 为 null", rp2 is None, str(rp2))
            # 还原成初始状态，别把数据改脏
            if isinstance(reply_before, dict):
                cdp.eval("MOCK.act('merchant.comment.reply',{id:'%s',content:%s})"
                         % (cid, json.dumps(reply_before.get("content", ""))))
        cdp.no_js_error("评论管理")
        cdp.shot("e2e-03-comments.png")

        # ---------- 5. 店铺资料 ----------
        print("\n[5] 店铺资料")
        cdp.open("%s/merchant/shop-info.html?api=1" % FRONT)
        form = cdp.eval("""(function(){
          var g=function(id){var e=document.getElementById(id);return e?e.value:null};
          return {name:g('fName'), phone:g('fPhone'), addr:g('fAddr'),
                  cuisine:g('fCuisine'), cover:(document.getElementById('coverImg')||{}).src};
        })()""")
        check("店名回显后端值", form.get("name") == SHOP["name"],
              "页面=%s 后端=%s" % (form.get("name"), SHOP["name"]))
        check("电话回显后端值", form.get("phone") == SHOP["phone"],
              "页面=%s 后端=%s" % (form.get("phone"), SHOP["phone"]))
        check("门头图已加载", bool(form.get("cover")), str(form.get("cover")))

        # 改简介 → 保存 → MOCK 同步 → 还原
        old_intro = SHOP["intro"]
        cdp.eval("document.getElementById('fIntro').value='【e2e 临时简介】'; save();")
        time.sleep(0.6)
        now_intro = cdp.eval("(MOCK.getShop(MOCK.currentMerchant.shopId)||{}).intro")
        check("保存后 MOCK 里的简介同步更新", now_intro == "【e2e 临时简介】",
              "得到 %s" % now_intro)

        if old_intro:
            cdp.eval("document.getElementById('fIntro').value=%s; save();" % json.dumps(old_intro))
            time.sleep(0.6)
            back_intro = cdp.eval("(MOCK.getShop(MOCK.currentMerchant.shopId)||{}).intro")
            check("简介已还原，不留脏数据", back_intro == old_intro, "得到 %s" % back_intro)
        cdp.no_js_error("店铺资料")
        cdp.shot("e2e-04-shopinfo.png")

        # ---------- 6. 发布页 ----------
        print("\n[6] 发布页：冷却状态")
        cdp.open("%s/merchant/publish.html?api=1" % FRONT)
        pv = cdp.eval("""(function(){
          var form=document.getElementById('formView'),
              cd=document.getElementById('cooldownView'),
              t=document.getElementById('cdTime');
          return {formHidden:form.classList.contains('hidden'),
                  cdHidden:cd.classList.contains('hidden'),
                  cdText:t?t.textContent.trim():null,
                  cdRaw:MOCK.cooldownSeconds};
        })()""")
        if not BACK["canPostToday"]:
            # 重新取一次：冷却秒数每秒都在走，拿开场那份快照比会越比越偏
            # （页面里的 MOCK.cooldownSeconds 是它自己加载那一刻的值）
            fresh = api_data("/merchant/bootstrap?shopId=" + DEMO_SHOP)["cooldownSeconds"]
            check("冷却中：表单隐藏", pv.get("formHidden") is True, str(pv))
            check("冷却中：倒计时视图显示", pv.get("cdHidden") is False, str(pv))
            check("页面拿到的 cooldownSeconds 与后端一致（±20s）",
                  isinstance(pv.get("cdRaw"), int) and abs(pv["cdRaw"] - fresh) <= 20,
                  "页面=%s 后端=%s" % (pv.get("cdRaw"), fresh))
            # 倒计时文本形如 "20 : 36 : 41"
            txt = (pv.get("cdText") or "").replace(" ", "")
            check("倒计时已按真实剩余时间渲染", txt != "23:45:12" and ":" in txt,
                  "cdText=%r" % pv.get("cdText"))
        else:
            check("可发布：表单显示", pv.get("formHidden") is False, str(pv))
        cdp.no_js_error("发布页")
        cdp.shot("e2e-05-publish.png")

        # ---------- 7. 审核状态页 ----------
        print("\n[7] 审核状态页")
        cdp.open("%s/merchant/audit.html?api=1" % FRONT)
        au = cdp.eval("""(function(){
          var t=document.querySelector('.status-title'),
              sw=document.querySelector('.demo-switch');
          return {title:t?t.textContent.trim():null,
                  switchHidden:sw? (sw.style.display==='none') : null};
        })()""")
        expect = "审核通过" if SHOP["status"] == "normal" else SHOP["status"]
        check("状态与后端一致", au.get("title") == expect,
              "页面=%s 后端 status=%s" % (au.get("title"), SHOP["status"]))
        check("演示开关在真实数据下自动隐藏", au.get("switchHidden") is True,
              "switchHidden=%s" % au.get("switchHidden"))
        cdp.no_js_error("审核页")
        cdp.shot("e2e-06-audit.png")

        # ---------- 8. 跨端联动 ----------
        print("\n[8] 跨端联动：商家端改了，客户端能看到")
        cdp.open("%s/merchant/shop-info.html?api=1" % FRONT)
        cdp.eval("document.getElementById('fIntro').value='【e2e 跨端验证】'; save();")
        time.sleep(0.5)
        client = api_data("/client/bootstrap")
        hit = next((s for s in client["shops"] if s["id"] == DEMO_SHOP), None)
        check("客户端接口已能看到商家端刚改的简介",
              bool(hit) and hit["intro"] == "【e2e 跨端验证】",
              str(hit.get("intro") if hit else "店铺未找到"))
        if old_intro:
            cdp.eval("document.getElementById('fIntro').value=%s; save();" % json.dumps(old_intro))
            time.sleep(0.5)

        # ---------- 汇总 ----------
        print("\n---- 结果：%d 通过 / %d 失败 ----" % (PASS, FAIL))
        if FAILURES:
            print("失败项：")
            for f in FAILURES:
                print("  - " + f)
        else:
            print("截图目录：%s" % OUT)
    finally:
        if cdp:
            cdp.close()
        chrome.terminate()


if __name__ == "__main__":
    main()
    sys.exit(1 if FAIL else 0)
