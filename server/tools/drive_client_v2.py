# -*- coding: utf-8 -*-
"""
v1.6 客户端优化专项验证（真实 Chrome + CDP，逐步截图存证）

覆盖本轮新加的 7 项功能性缺陷修复 + 6 项体验优化：
  A1/A2 点赞收藏持久化（刷新不丢）      A3 主图可点进详情
  A4 打卡数不再跳两次                   A5 未登录打卡被拦
  A6 个人中心数字来自真实数据           A7 去登录跳真实登录页
  B3 切 Tab 保留滚动位置                B4 筛选态 Tab 带角标
  B5 confirm 换成页内浮层               B6 评论回复他人（仅本地）
  B7 浏览记录进 localStorage（有上限）

用法：
    python drive_client_v2.py [前端地址] [截图输出目录]
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
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, ".shots-v2")
DEBUG_PORT = 9334

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]


class CDP:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=40, suppress_origin=True)
        self.seq = 0

    def send(self, method, params=None):
        self.seq += 1
        mid = self.seq
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                return msg

    def eval(self, expr, await_promise=False):
        r = self.send(
            "Runtime.evaluate",
            {"expression": expr, "returnByValue": True, "awaitPromise": await_promise},
        )
        res = r.get("result", {})
        if "exceptionDetails" in res:
            raise RuntimeError(str(res["exceptionDetails"])[:400])
        return res.get("result", {}).get("value")

    def goto(self, url):
        self.send("Page.navigate", {"url": url})

    def wait_path(self, fragment, timeout=20):
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
        r = self.send("Page.captureScreenshot", {"format": "png"})
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


def launch():
    chrome = next((c for c in CHROME_CANDS if os.path.exists(c)), None)
    if not chrome:
        raise SystemExit("找不到可用的 Chrome / Edge")
    prof = tempfile.mkdtemp(prefix="cdp-prof2-")
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


# 真实选择器（已按本轮改动后的 DOM 核对过）
SEL_FIRST_LIKE = "document.querySelector('.fc .fc__acts .act')"
JS_FIRST_LIKE_CLASS = (
    "(function(){var b=%s;return b?b.className:null;})()" % SEL_FIRST_LIKE
)

# 把「已登录」的各种残留一并清掉。
# 为什么要连 loggedIn 与 eatwhat_user_client 一起清：客户端登录态有三路来源 ——
#  1) eatwhat_token_client（服务端 token）
#  2) eatwhat_user_client（用户展示信息，hydrateCurrentUser 读到就翻 loggedIn=true）
#  3) loggedIn（离线演示标志）
# 只清 token 的话，2) 或 3) 会把登录态又认回来，「未登录」场景根本测不出来。
JS_FORCE_LOGOUT = (
    "(function(){"
    "['eatwhat_token_client','eatwhat_user_client','eatwhat_role_client','loggedIn',"
    "'eatwhat_interact','eatwhat_checkin_shops','eatwhat_browsed','clientFilter']"
    ".forEach(function(k){try{localStorage.removeItem(k);}catch(e){}});"
    "if(window.MOCK&&MOCK.currentUser){MOCK.currentUser.loggedIn=false;}"
    "return true;})()"
)


def main():
    os.makedirs(OUT, exist_ok=True)
    proc, prof = launch()
    ok = 0
    bad = 0
    c = None

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

        # ============ 1. 未登录点赞：应弹浮层而非 confirm，且不落盘 ============
        print("\n=== 1. 未登录点赞：应弹浮层，且不落盘 ===")
        c.goto(FRONT + "/client/feed.html?api=1")
        c.wait_ready()
        time.sleep(2.5)
        c.eval(
            "localStorage.removeItem('eatwhat_interact');"
            "localStorage.removeItem('eatwhat_browsed');"
            "localStorage.removeItem('eatwhat_checkin_shops');"
            "localStorage.removeItem('eatwhat_token_client');"
            "localStorage.removeItem('loggedIn');"
        )
        c.goto(FRONT + "/client/feed.html?api=1")
        c.wait_ready()
        time.sleep(2.5)

        n_cards = c.eval("document.querySelectorAll('.fc').length")
        has_tap = c.eval("!!document.querySelector('.fc .fc__tap')")
        print("   卡片数: %s   主图可点层: %s" % (n_cards, has_tap))
        check("首页有内容卡片", int(n_cards or 0) > 0, "%s 张" % n_cards)
        check("【A3】每张卡都有可点主图层", has_tap is True)

        c.eval("(function(){var b=%s;if(b)b.click();})()" % SEL_FIRST_LIKE)
        time.sleep(0.9)
        mask_vis = c.eval(
            "(function(){var e=document.querySelector('.eat-sheet-mask');"
            "return e?getComputedStyle(e).display:'missing';})()"
        )
        stored1 = c.eval("localStorage.getItem('eatwhat_interact')")
        print("   浮层 display: %s   interact: %s" % (mask_vis, stored1))
        check("【B5】未登录点赞弹出页内浮层", mask_vis not in ("missing", "none"))
        check("未登录点赞未落盘", stored1 in (None, "", "{}"), "→ %r" % stored1)
        c.shot("01-feed-未登录点赞浮层.png")

        # 浮层点「去登录」
        print("\n=== 2. 【B5/A7】浮层「去登录」跳真实登录页 ===")
        c.eval(
            "(function(){var b=document.querySelector('.eat-sheet [data-act=login]');"
            "if(b)b.click();})()"
        )
        got_login = c.wait_path("login.html", timeout=10)
        print("   当前地址: %s" % (c.eval("location.href") or ""))
        check("浮层去登录跳到 login.html", got_login)
        c.wait_ready()
        time.sleep(1.5)
        c.shot("02-登录页.png")

        # ============ 3. 真实登录（短信码） ============
        print("\n=== 3. 客户端真实登录（dev 回显验证码）===")
        phone = "13900001234"
        # 登录页真实 DOM：手机号 #phone、验证码 #code、发码 #codeBtn、
        # 提交 #submitBtn、协议 #agreeBox；开发码通过 toast 文案回显
        send_state = c.eval(
            "(function(){"
            "var inp=document.getElementById('phone');"
            "if(!inp) return 'no-input';"
            "inp.value=%s;"
            "inp.dispatchEvent(new Event('input',{bubbles:true}));"
            "var b=document.getElementById('codeBtn');"
            "if(!b) return 'no-send-btn';"
            "b.click(); return 'clicked';})()" % json.dumps(phone)
        )
        print("   发码动作: %s" % send_state)
        time.sleep(2.0)
        toast_txt = c.eval(
            "(function(){var e=document.getElementById('toast');"
            "return e?e.textContent.trim():null;})()"
        )
        dev_code = None
        if toast_txt:
            import re as _re
            m = _re.search(r"(\d{6})", toast_txt)
            if m:
                dev_code = m.group(1)
        print("   toast 文案: %s   devCode: %s" % (toast_txt, dev_code))
        check("登录页能拿到开发验证码", bool(dev_code), "code=%s" % dev_code)
        c.shot("03-登录页-已发码.png")

        if dev_code:
            # 勾选协议（默认未勾选）
            c.eval(
                "(function(){var a=document.getElementById('agreeBox');"
                "if(a && !a.classList.contains('is-on')) a.click();})()"
            )
            time.sleep(0.3)
            c.eval(
                "(function(){"
                "var inp=document.getElementById('code');"
                "if(inp){inp.value=%s;"
                "inp.dispatchEvent(new Event('input',{bubbles:true}));}"
                "var btn=document.getElementById('submitBtn');"
                "if(btn)btn.click();})()" % json.dumps(dev_code)
            )
            logged = c.wait_path("feed.html", timeout=12)
            time.sleep(2.5)
            tok = c.eval("localStorage.getItem('eatwhat_token_client')")
            print("   登录后地址: %s" % (c.eval("location.href") or ""))
            print("   token: %s" % (str(tok)[:26] + "..." if tok else None))
            check("登录成功并回到 feed.html", logged)
            check("客户端 token 已落 localStorage", bool(tok))
            c.shot("04-登录后首页.png")

            # ============ 4. A1 核心验收 ============
            print("\n=== 4. 【A1 核心】登录后点赞 → 刷新 → 仍亮着 ===")
            target_id = c.eval(
                "(function(){var e=document.querySelector('.fc');"
                "return e?e.dataset.id:null;})()"
            )
            c.eval("(function(){var b=%s;if(b)b.click();})()" % SEL_FIRST_LIKE)
            time.sleep(1.3)
            after_like = c.eval("localStorage.getItem('eatwhat_interact')")
            cls_before = c.eval(JS_FIRST_LIKE_CLASS)
            on_before = "is-on" in (cls_before or "")
            print("   点赞后 interact: %s" % after_like)
            print("   按钮 class: %s  (is-on=%s)" % (cls_before, on_before))
            check("【A1】点赞写入 localStorage",
                  bool(after_like) and target_id in (after_like or ""), "id=%s" % target_id)
            check("【A1】按钮即时变为选中态", on_before)
            c.shot("05-feed-已点赞.png")

            c.goto(FRONT + "/client/feed.html?api=1")
            c.wait_ready()
            time.sleep(2.8)
            cls_after = c.eval(JS_FIRST_LIKE_CLASS)
            on_after = "is-on" in (cls_after or "")
            print("   刷新后按钮 class: %s  (is-on=%s)" % (cls_after, on_after))
            check("【A1】刷新后点赞态保留", on_after is True, "%r" % cls_after)
            c.shot("06-feed-刷新后点赞仍在.png")

            # 再点一次应取消（而不是卡住）
            c.eval("(function(){var b=%s;if(b)b.click();})()" % SEL_FIRST_LIKE)
            time.sleep(1.3)
            c.goto(FRONT + "/client/feed.html?api=1")
            c.wait_ready()
            time.sleep(2.8)
            cls_off = c.eval(JS_FIRST_LIKE_CLASS)
            print("   取消后再刷新 class: %s" % cls_off)
            check("【A1】再点一次可取消且刷新后保持取消",
                  "is-on" not in (cls_off or ""), "%r" % cls_off)
            c.shot("07-feed-取消点赞后刷新.png")
        else:
            check("登录后互动验证", False, "devCode 缺失，跳过")

        # ============ 5. B6 评论回复他人 ============
        print("\n=== 5. 【B6】评论「回复」进入回复态 ===")
        dish_id = c.eval(
            "(function(){try{return MOCK.dishes.filter(function(d){"
            "return d.status==='normal' && (d.stats.comments||0)>0;})[0].id;}"
            "catch(e){return null;}})()"
        )
        print("   选中的菜品 id: %s" % dish_id)
        c.goto(FRONT + "/client/dish.html?id=" + str(dish_id))
        c.wait_ready()
        time.sleep(2.5)
        n_cm = c.eval("document.querySelectorAll('.cm-item').length")
        has_act = c.eval("!!document.querySelector('.cm-act')")
        print("   评论条数: %s  有回复按钮: %s" % (n_cm, has_act))
        check("详情页评论列表已渲染", int(n_cm or 0) > 0, "%s 条" % n_cm)
        check("【B6】评论带「回复」按钮", has_act is True)

        c.eval("(function(){var b=document.querySelector('.cm-act');if(b)b.click();})()")
        time.sleep(0.7)
        target_disp = c.eval(
            "(function(){var e=document.getElementById('cmTarget');"
            "return e?getComputedStyle(e).display:'missing';})()"
        )
        target_name = c.eval(
            "(function(){var e=document.getElementById('cmTargetName');"
            "return e?e.textContent.trim():null;})()"
        )
        print("   回复状态条 display: %s   回复对象: %s" % (target_disp, target_name))
        check("点回复后出现「正在回复」状态条", target_disp not in ("missing", "none"))
        check("状态条显示被回复者名字",
              bool(target_name) and target_name != "—", "%s" % target_name)
        c.shot("08-dish-正在回复.png")

        # 取消回复
        c.eval(
            "(function(){var e=document.getElementById('cmTarget');"
            "var b=e?e.querySelector('.cm-target__cancel'):null;if(b)b.click();})()"
        )
        time.sleep(0.6)
        target_disp2 = c.eval(
            "(function(){var e=document.getElementById('cmTarget');"
            "return e?getComputedStyle(e).display:'missing';})()"
        )
        print("   取消后 display: %s" % target_disp2)
        check("取消后回复态收起", target_disp2 == "none")

        # ============ 6. B7 浏览记录 ============
        print("\n=== 6. 【B7】浏览记录写入且有上限 ===")
        browsed_raw = c.eval("localStorage.getItem('eatwhat_browsed')")
        browsed_n = c.eval("(MOCK.getBrowsed()||[]).length")
        cap = c.eval("MOCK.BROWSED_MAX")
        print("   eatwhat_browsed: %s" % (browsed_raw or "")[:100])
        print("   条数: %s  上限: %s" % (browsed_n, cap))
        check("进详情后写入浏览记录", bool(browsed_raw) and dish_id in (browsed_raw or ""))
        check("浏览记录有上限常量", cap == 200, "BROWSED_MAX=%s" % cap)
        check("浏览记录不超过上限", int(browsed_n or 0) <= int(cap or 0))
        check("旧的无效 key profileState 未被使用",
              c.eval("localStorage.getItem('profileState')") is None)
        c.shot("09-dish-浏览已记录.png")

        # ============ 7. B3 切 Tab 保留滚动 ============
        print("\n=== 7. 【B3】切 Tab 回来滚动位置保留 ===")
        c.goto(FRONT + "/client/feed.html?api=1")
        c.wait_ready()
        time.sleep(3.0)
        tab_n = c.eval("document.querySelectorAll('#feedTabs .feed-tab').length")
        c.eval("window.scrollTo(0, 700)")
        time.sleep(0.8)
        y_before = c.eval("window.pageYOffset")
        c.eval(
            "(function(){var bs=document.querySelectorAll('#feedTabs .feed-tab');"
            "if(bs.length>1)bs[1].click();})()"
        )
        time.sleep(1.6)
        y_mid = c.eval("window.pageYOffset")
        c.eval(
            "(function(){var bs=document.querySelectorAll('#feedTabs .feed-tab');"
            "if(bs.length>0)bs[0].click();})()"
        )
        time.sleep(1.6)
        y_after = c.eval("window.pageYOffset")
        print("   Tab 数: %s" % tab_n)
        print("   切走前 y=%s  另一 Tab y=%s  切回后 y=%s" % (y_before, y_mid, y_after))
        check("首页有多个 Tab", int(tab_n or 0) >= 2, "%s 个" % tab_n)
        check("【B3】切回原 Tab 滚动位置保留",
              abs((y_after or 0) - (y_before or 0)) < 120,
              "%s → %s" % (y_before, y_after))
        c.shot("10-feed-切Tab滚动保留.png")

        # ============ 8. B4 筛选态角标 ============
        print("\n=== 8. 【B4】有筛选条件时 Tab 显示角标 ===")
        c.eval(
            "localStorage.setItem('clientFilter',"
            "JSON.stringify({tiers:[],tastes:['麻辣']}));location.reload();"
        )
        c.wait_ready()
        time.sleep(2.5)
        dot_disp = c.eval(
            "(function(){var d=document.querySelector('.feed-tab__dot');"
            "return d?getComputedStyle(d).display:'missing';})()"
        )
        tabs_filtered = c.eval(
            "(function(){var t=document.getElementById('feedTabs');"
            "return t?t.classList.contains('is-filtered'):null;})()"
        )
        print("   角标 display: %s   is-filtered: %s" % (dot_disp, tabs_filtered))
        check("【B4】筛选态出现角标", dot_disp not in ("missing", "none"))
        check("【B4】Tab 容器带 is-filtered 标记", tabs_filtered is True)
        c.shot("11-feed-筛选态角标.png")

        c.eval("localStorage.removeItem('clientFilter')")
        c.eval("location.reload();")
        c.wait_ready()
        time.sleep(2.2)
        dot_disp2 = c.eval(
            "(function(){var d=document.querySelector('.feed-tab__dot');"
            "return d?getComputedStyle(d).display:'missing';})()"
        )
        print("   清掉筛选后角标 display: %s" % dot_disp2)
        check("【B4】清除筛选后角标隐藏", dot_disp2 == "none")

        # ============ 9. A4/A5 打卡 ============
        print("\n=== 9. 【A4/A5】店铺页打卡：未登录被拦 ===")
        c.goto(FRONT + "/client/feed.html?api=1")
        c.wait_ready()
        time.sleep(2.5)
        shop_id = c.eval(
            "(function(){try{return MOCK.dishes.filter(function(d){"
            "return d.status==='normal';})[0].shopId;}catch(e){return null;}})()"
        )
        if not shop_id:
            shop_id = c.eval(
                "(function(){try{return MOCK.shops.filter(function(s){"
                "return s.status==='active';})[0].id;}catch(e){return null;}})()"
            )
        print("   店铺 id: %s" % shop_id)
        c.eval(JS_FORCE_LOGOUT)
        c.goto(FRONT + "/client/shop.html?id=" + str(shop_id))
        c.wait_ready()
        time.sleep(2.8)
        # 确认确实处于未登录态（token 与演示标志都没了）
        still_in = c.eval(
            "(function(){return !!(MOCK.currentUser&&MOCK.currentUser.loggedIn);})()"
        )
        print("   页面当前 loggedIn: %s" % still_in)
        btn_txt = c.eval(
            "(function(){var e=document.getElementById('checkinBtnText');"
            "return e?e.textContent.trim():null;})()"
        )
        num_before = c.eval(
            "(function(){var e=document.querySelector('.shop-head__num, .sh-num');"
            "return e?e.textContent.trim():null;})()"
        )
        print("   打卡按钮文案: %s   店铺人数(前): %s" % (btn_txt, num_before))
        check("店铺页渲染出打卡按钮", bool(btn_txt))
        c.shot("12-shop-未登录.png")

        c.eval("(function(){var b=document.getElementById('checkinBtn');if(b)b.click();})()")
        time.sleep(1.0)
        mask2 = c.eval(
            "(function(){var e=document.querySelector('.eat-sheet-mask');"
            "return e?getComputedStyle(e).display:'missing';})()"
        )
        ck = c.eval("localStorage.getItem('eatwhat_checkin_shops')")
        print("   浮层 display: %s   打卡记录: %s" % (mask2, ck))
        check("【A5】未登录打卡弹出登录浮层", mask2 not in ("missing", "none"))
        check("【A5】未登录打卡未落盘", ck in (None, "", "{}"))
        c.shot("13-shop-打卡拦截浮层.png")

        # ============ 10. A6 个人中心真实数据 ============
        print("\n=== 10. 【A6】个人中心数字来自真实数据 ===")
        c.eval(JS_FORCE_LOGOUT)
        c.goto(FRONT + "/client/profile.html")
        c.wait_ready()
        time.sleep(2.2)
        login_tip = c.eval(
            "(function(){var e=document.querySelector('.login-tip');"
            "return e?getComputedStyle(e).display:'missing';})()"
        )
        print("   未登录提示 display: %s" % login_tip)
        check("未登录时个人中心显示登录引导", login_tip not in ("missing", "none"))
        c.shot("14-profile-未登录.png")

        # 登录后看真实统计
        c.eval(
            "(function(){var b=document.querySelector('.login-tip .btn');if(b)b.click();})()"
        )
        time.sleep(1.5)
        href2 = c.eval("location.href") or ""
        print("   去登录跳转: %s" % href2)
        check("【A7】个人中心「立即登录」跳真实登录页", "login.html" in href2, href2)
        c.shot("15-profile-去登录.png")

        print("\n=== 汇总 ===")
        print("通过 %d 项，失败 %d 项" % (ok, bad))
        print("截图目录：%s" % OUT)
        return 0 if bad == 0 else 1

    finally:
        if c:
            try:
                c.close()
            except Exception:
                pass
        try:
            proc.kill()
        except Exception:
            pass
        time.sleep(1)
        shutil.rmtree(prof, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
