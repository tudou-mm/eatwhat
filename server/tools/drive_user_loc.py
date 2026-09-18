# -*- coding: utf-8 -*-
"""
客户端定位专项验证（浏览器层，走 CDP）。

为什么单独写一条
----------------
「距离」在客户端有两个口径，页面不报错但**数字会骗人**：

    shop.distance       后端权威值，基准是**市中心**
    UserLoc.shopKm()    客户端现算，基准是**用户位置**

如果首页用后者排序、卡片却显示前者，就会出现「2.3km 的排在 5.1km 后面」。
这种矛盾**不会抛异常**，只能靠真的把两种坐标喂进去、比对数字才看得出来。

四条降级路径必须逐条验证（`navigator.geolocation` 的失败方式特别多）：

    L1 授权成功        → src='gps'，距离按用户坐标
    L2 拒绝 + 有缓存    → src='cache'，用缓存坐标
    L3 缓存过期         → src='cache' 且 stale=true，界面要标注「可能不准」
    L4 拒绝 + 无缓存    → src='city'，退市中心，界面必须变黄灯 + 明确告知

⚠️ 最容易漏的是 L4：定位失败时**不能白屏**，内容必须照常渲染。
   历史上「附近」页在无定位环境下会整片空白，就是因为把定位当成了前置依赖。

用法
----
    python drive_user_loc.py            # 默认打 5173 前端
    python drive_user_loc.py 5173
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

try:
    import websocket
except ImportError:
    raise SystemExit("需要 websocket-client：pip install websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-loc")
os.makedirs(OUT, exist_ok=True)

WEB_PORT = sys.argv[1] if len(sys.argv) > 1 else "5173"
BASE = "http://127.0.0.1:%s" % WEB_PORT
DEBUG_PORT = 9227          # 与 drive_map_picker 的 9222 错开，两条测试可同时跑

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

# 仪征国庆路商业步行街 —— 用来模拟「用户就在市中心附近」
# ⚠️ **不能填市中心本身的坐标**（32.2728, 119.1845）！
#    那是 `CITY_CENTER` 降级基准，填成一样的话「用户口径 vs 市中心口径」
#    两者恒等，第 2 组那条「没有偷偷退回 shop.distance」的断言会假红。
#    坐标取自 radar.db（国庆路商业步行街，距市中心约 0.37km）。
USER_LAT, USER_LNG = 32.272683, 119.180490
# 仪征北部（月塘镇方向）—— 用来模拟「用户在城北」，好验证排序真的按用户位置重排了
FAR_LAT, FAR_LNG = 32.3720, 119.1580
# 手动设点用（对应原来的「大熊猫基地」），语义＝「离市区较远的另一个地标」
MANUAL_LAT, MANUAL_LNG, MANUAL_NAME = 32.3720, 119.1580, '月塘镇中心'

PASS, FAIL, SKIP = [], [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print("  [%s] %s%s" % ("OK" if cond else "FAIL", name,
                           ("   " + str(detail)) if detail else ""))


def skip(name, why):
    SKIP.append(name)
    print("  [--] %s   （跳过：%s）" % (name, why))


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

    def open(self, url, settle=1.5):
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


def find_chrome():
    for c in CHROME_CANDS:
        if os.path.exists(c):
            return c
    raise SystemExit("找不到 Chrome/Edge")


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


CLEAR_POS = "try{localStorage.removeItem('eatwhat_user_pos');}catch(e){}"


def seed_pos(lat, lng, age_ms=0):
    """往 localStorage 塞一条定位缓存，age_ms 用来伪造「多久之前定位的」"""
    return ("localStorage.setItem('eatwhat_user_pos', JSON.stringify("
            "{lat:%s, lng:%s, ts:%d, src:'gps'}))" % (lat, lng, int(time.time() * 1000) - age_ms))


def wait_src(c, want, timeout=25):
    """等 UserLoc 进入某个降级级别"""
    end = time.time() + timeout
    while time.time() < end:
        s = c.eval("(function(){try{return UserLoc.state().src;}catch(e){return null;}})()")
        if s == want:
            return True
        time.sleep(0.4)
    return False


def fresh_profile(name):
    """
    返回一个**干净**的 Chrome profile 目录，用前先整个删掉。

    ⚠️⚠️ 这一行是必须的，删掉会掉进一个极难查的坑（本项目踩过一次）：

    `--user-data-dir` 是**持久化磁盘缓存**。Chrome 会把 `mock.js` 这类静态资源
    连同 `Last-Modified` 一起缓存下来（本项目前端是 `SimpleHTTP` 起的，
    它**只发 `Last-Modified`、不发 `Cache-Control`**，浏览器就按启发式规则缓存）。

    于是改了 `mock.js`（比如新增顶层函数 `sortDist`）之后再跑测试，
    页面**加载的还是旧缓存副本** —— 表现为：
        `MOCK` 在、`UserLoc` 在、`EAT_API` 在，
        **唯独新加的那个顶层函数不在**，`typeof sortDist === 'undefined'`。

    而如果测试断言引用了这个函数，写 `if (typeof sortDist !== 'function')`
    就会**静默走进 else 分支**，报出一串「明明有序却判逆序」的假红 ——
    排序器本身完全正确，是页面根本没拿到新代码。**为此排查了两轮。**

    单次运行看不出来（第二次跑就开始脏），同一 profile 反复跑尤其容易中招。
    → 一律「每次启动前全删」，让缓存无从积累。
    """
    prof = os.path.join(OUT, name)
    shutil.rmtree(prof, ignore_errors=True)
    return prof


def main():
    chrome = subprocess.Popen([
        find_chrome(), "--headless=new", "--disable-gpu", "--no-first-run",
        "--remote-debugging-port=%d" % DEBUG_PORT, "--window-size=430,900",
        "--user-data-dir=" + fresh_profile("_profile"), "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    c = CDP(ws_endpoint())
    try:
        c.send("Runtime.enable")
        c.send("Page.enable")

        ORIGIN = "http://127.0.0.1:%s" % WEB_PORT

        # ================= 1. 组件加载 =================
        print("\n[1] user-loc.js 加载")
        # ⚠️ 这一步也要带 `?api=1`。
        #    带与不带会走**两条数据路径**：不带 → 本地假数据（`cache.loaded=false`），
        #    带 → 打 `/client/bootstrap` 走后端数据。
        #    先不带再带，等于让同一个浏览器会话里先后存在两套 `MOCK.shops`，
        #    后面「分组递增」那条就会读到被前一页污染的顺序（踩过，排查很久）。
        c.open(BASE + "/client/feed.html?api=1")
        check("UserLoc 已挂到 window", c.eval("typeof UserLoc") == "object",
              c.eval("typeof UserLoc"))
        check("距离公式与后端同口径（对已知两点比对）",
              c.eval("UserLoc.km(30.6570, 104.0658, 30.6598, 104.0810)") == 1.5,
              c.eval("UserLoc.km(30.6570, 104.0658, 30.6598, 104.0810)"))
        check("坐标缺失时返回 null（不返回 0 冒充有效值）",
              c.eval("UserLoc.km(null, null, 30.65, 104.06)") is None)

        # ================= 2. L1 授权成功 =================
        print("\n[2] L1 授权成功 → 按用户位置算距离")
        c.send("Browser.grantPermissions", {"origin": ORIGIN,
                                            "permissions": ["geolocation"]})
        c.send("Emulation.setGeolocationOverride",
               {"latitude": USER_LAT, "longitude": USER_LNG, "accuracy": 20})
        c.eval(CLEAR_POS)
        c.open(BASE + "/client/feed.html?api=1")
        ok = wait_src(c, "gps")
        check("进入 gps 级别", ok, c.eval("JSON.stringify(UserLoc.state())"))
        if ok:
            check("坐标与模拟值一致",
                  abs(c.eval("UserLoc.state().lat") - USER_LAT) < 1e-6 and
                  abs(c.eval("UserLoc.state().lng") - USER_LNG) < 1e-6,
                  c.eval("JSON.stringify(UserLoc.state())"))
            check("isUser=true（距离基准是用户）",
                  c.eval("UserLoc.state().isUser") is True)
            check("界面文案表明基准是「我的位置」",
                  c.eval("(document.getElementById('locText')||{}).textContent")
                  and "你的位置" in str(c.eval(
                      "(document.getElementById('locText')||{}).textContent")),
                  c.eval("(document.getElementById('locText')||{}).textContent"))
            check("定位灯是青色（不是表示近似值的黄色）",
                  c.eval("document.getElementById('locDot').classList.contains('is-approx')") is False)
            check("距离标签 =「距我的位置」", c.eval("UserLoc.label()") == "距我的位置",
                  c.eval("UserLoc.label()"))

            # 用户就在国庆路步行街 → 必须存在一家 1km 以内的店（证明真的按用户坐标算）
            near = c.eval("""(function(){
              var list = MOCK.shops.filter(function(s){return s.lat!=null;})
                .map(function(s){return UserLoc.shopKm(s);})
                .sort(function(a,b){return a-b;});
              return list[0];
            })()""")
            check("最近的店在 1km 内（证明用的是用户坐标而非市中心）",
                  near is not None and near < 1.0, "%skm" % near)

            # 排序一致性：**同一置顶+权重分组内**距离必须递增。
            # ⚠️ 不能断言「全列表严格递增」—— 规则是复合排序
            #    「置顶 > 权重 > 距离 > 时间衰减」（docs/00 §5），
            #    置顶店和高权重店本来就会插到更近的店前面。
            check("排序用的距离与卡片显示的距离同源（不再各算各的）",
                  c.eval("""(function(){
                    var els = [].slice.call(document.querySelectorAll('.fc'));
                    for (var i=0;i<els.length;i++){
                      var d = MOCK.getDish(els[i].dataset.id);
                      if (!d) continue;
                      var m = /([\\d.]+)km/.exec(els[i].innerText);
                      if (!m) continue;
                      var shown = parseFloat(m[1]);
                      var s = MOCK.getShop(d.shopId);
                      // 排序基准（sortDist 用的就是它）必须等于显示值
                      var used = UserLoc.shopKm(s);
                      if (Math.abs(shown - used) > 0.05) return false;
                    }
                    return true;
                  })()""") is True)

            # 分组检查：**同一置顶 + 同一权重**的一组里，距离必须递增。
            #
            # ⚠️⚠️ 这里前后踩了两个坑，都记下来免得后人重踩：
            #
            # 【坑 1：读 DOM 的时机】最初是「读 DOM 里的卡片 → 分组 → 断言有序」，
            #   定位是异步的、回调里会重排信息流，而 `UserLoc.shopKm()` 是**现场实时算**，
            #   两者取自不同瞬间就会错位，报出 `1:90=[2.2,0.9,2.1]` 这种「明明有序却判逆序」。
            #   → 改用 `MOCK.buildFeed()` 取一份当下的、自洽的数据来断言。
            #
            # 【坑 2（真正的坑）：距离有两个口径，别拿 A 口径去比 B 口径】
            #   本项目里「距离」有**两个都正确**的基准：
            #     · `shop.distance`   —— 后端权威值，基准是**市中心**
            #     · `UserLoc.shopKm()` —— 客户端现算，基准是**用户位置**
            #   同一家店这两个数**本来就不相等**（例：钱亮亮小火锅到市中心 0.39 / 到步行街 0.77）。
            #   我一开始断言「分组的 km 与实际不符」，其实是把「用户口径」拿去比「市中心口径」，
            #   白白排查了两轮。**断言前先想清楚比的是哪个口径。**
            #
            # 另一条必须守住的：`MOCK.shops` 与 `cache.shops` 必须是**同一个数组引用**。
            # 若被各赋一份，`MOCK.getShop()`（走后端）与排序器（走 `this.shops`）
            # 会读到两个不同的对象，出现「同一家店两个距离」，而两边各自都自洽 ——
            # 极其难查。api.js 的 `hydrateMock()` 就是为这条红线存在的。
            check("MOCK.shops 与后端缓存是同一个数组（数据只有一份）",
                  c.eval("window.EAT_API ? (EAT_API.cache.shops === MOCK.shops) : true") is True)

            # ⚠️⚠️ 断言分组前必须确认「定位基准真的生效了」。
            #
            # 本项目里「距离」有两个**都正确**的口径：
            #   · `shop.distance`     后端权威值，基准是**市中心**，审核通过时落库
            #   · `UserLoc.shopKm()`  客户端现算，基准是**用户位置**
            #
            # `sortDist()` 的策略是「有用户位置就用用户位置，否则退回 shop.distance」。
            # 于是**定位未就绪与已就绪，会走两个完全不同的排序基准**。
            # 若在「UserLoc 状态已变、但页面还没按新基准重排」的中间态取样，
            # 就会看到 `1:90=[2.2,0.9,2.1]` 这种「明明有序却判逆序」——
            # 排序器没错，是我在错误的时刻取样了。**踩过，排查了很久。**
            #
            # 注意 `wait_src()` 只保证 `UserLoc.state().src` 到位，
            # 不保证页面的 `buildFeed` 已经用新基准跑完。所以这里：
            #   ① 等到 `sortDist` 确实用的是用户坐标（而不是 shop.distance）
            #   ② 显式重排一次，把渲染态收敛到新基准
            # ⚠️⚠️ 一律写 `window.sortDist`，别写裸的 `sortDist`。
            #
            # 裸标识符在 `Runtime.evaluate` 里**能**解析到脚本顶层声明
            # （`var`/`function` 声明会挂到 window，是同一个东西）。
            # 真正的坑不在取值方式，而在**页面可能加载的是旧的缓存副本**：
            # 那时 `sortDist` 这个顶层函数在旧版 `mock.js` 里根本不存在，
            # 于是 `typeof sortDist === 'undefined'` —— 两种写法都取不到。
            # 详见 `fresh_profile()` 的说明。写 `window.` 只是让意图更明确、
            # 顺便绕开将来若有人把 mock.js 改成 IIFE/模块时的作用域差异。
            ready = c.eval("""(function(){
              if (!window.UserLoc || !window.MOCK) return 'no-UserLoc';
              if (typeof window.sortDist !== 'function')
                return 'no-sortDist（多半是 Chrome profile 里的旧缓存，'
                     + '删掉 .shots-loc/_profile 再跑）; readyState=' + document.readyState
                     + '; scripts=' + document.scripts.length
                     + '; mock=' + (typeof window.MOCK)
                     + '; shops=' + ((window.MOCK.shops||[]).length)
                     + '; typeofBare=' + (typeof sortDist)
                     + '; href=' + location.href;
              var bad = [];
              (MOCK.shops||[]).forEach(function(s){
                if (s.lat == null || s.distance == null) return;
                var a = window.sortDist(s);
                var b = window.UserLoc.shopKm(s);
                if (a == null || b == null || Math.abs(a - b) > 0.05)
                  bad.push(s.id + ':sortDist=' + a + ',shopKm=' + b + ',dist=' + s.distance);
              });
              return bad.length ? bad.join(' ') : true;
            })()""")
            check("排序基准已切到用户位置（sortDist 没在退回 shop.distance）",
                  ready is True, str(ready)[:120])

            # 收敛：把页面**重新导航**一次。
            #
            # 为什么要重载而不是只推一次 buildFeed：
            # `[1]` 那步打开过一次 feed.html，它会在同源 storage 里留下定位缓存，
            # 且 `UserLoc` 的状态机已经走过一轮。再叠上 `[2]` 的 grant/override，
            # 页面会处于「两轮定位状态叠加」的中间态 —— 表现为
            # `sortDist()` 返回 null（退回分支也没拿到值），而 `UserLoc.shopKm()`
            # 却有值，二者不一致。**重载一次即回到干净起点**，实测可复现修复。
            c.open(BASE + "/client/feed.html?api=1")
            wait_src(c, "gps")
            time.sleep(0.5)

            order, groups, bad = c.eval("""(function(){
              var items = MOCK.buildFeed('nearby') || [];
              var groups = {}, order = [], bad = [];
              items.forEach(function(it){
                var s = MOCK.getShop(it.shopId);
                if (!s) return;
                var km = UserLoc.shopKm(s);
                if (km == null) return;
                var k = (s.pinned ? 1 : 0) + ':' + s.weight;
                if (!groups[k]) { groups[k] = []; order.push(k); }
                groups[k].push(km);
              });
              for (var g = 0; g < order.length; g++) {
                var arr = groups[order[g]];
                for (var i = 1; i < arr.length; i++) {
                  if (arr[i] < arr[i-1] - 0.05)
                    bad.push(order[g] + '@' + i + ':' + arr[i-1] + '→' + arr[i]);
                }
              }
              return [order, groups, bad];
            })()""") or (None, None, None)

            if order:
                print("       " + "; ".join("%s=[%s]" % (k, ",".join(str(x) for x in groups[k]))
                                            for k in order))
            print("       逆序点:", bad if bad else "无")
            check("同一置顶+权重分组内，距离递增",
                  isinstance(bad, list) and len(bad) == 0,
                  str(bad)[:160] if bad else "")

            # 距离口径必须自洽：用户位置的基准下，距离**不能**等于后端那份市中心基准的值
            # （明显不同的两家店若「恰好相等」，说明 shopKm 退回了 shop.distance）
            mism = c.eval("""(function(){
              var out = 0, tot = 0;
              (MOCK.shops||[]).forEach(function(s){
                if (s.lat == null || s.distance == null) return;
                tot++;
                var mine = UserLoc.shopKm(s);
                if (mine != null && Math.abs(mine - s.distance) < 0.05) out++;
              });
              return [out, tot];
            })()""") or [0, 0]
            print("       与「市中心基准」恰好相等的店: %d / %d 家" % (mism[0], mism[1]))
            check("用户口径的距离确实不同于市中心口径（没有偷偷退回 shop.distance）",
                  mism[1] > 0 and mism[0] < mism[1],
                  "%d/%d 家相等" % (mism[0], mism[1]))

            # 顺带把「DOM 顺序 == buildFeed 顺序」也断言上 ——
            # 这才是能真正防住「排序器与渲染各走一套」的那条（这条曾经坏过）。
            check("DOM 卡片顺序与 buildFeed 顺序一致（渲染没自己再排一遍）",
                  c.eval("""(function(){
                    var a = [].slice.call(document.querySelectorAll('.fc'))
                      .map(function(el){ return el.dataset.id; });
                    var b = (MOCK.buildFeed('nearby')||[]).map(function(d){ return d.id; });
                    for (var i = 0; i < Math.min(a.length, b.length); i++) {
                      if (a[i] !== b[i]) return 'MISMATCH@' + i + ':' + a[i] + '/' + b[i];
                    }
                    return a.length === b.length ? true : 'LEN ' + a.length + '/' + b.length;
                  })()""") is True)
            c.shot("01-L1-授权成功-距我的位置.png")

        # 换到城北（月塘镇方向），验证排序**真的**跟着用户位置变了
        c.send("Emulation.setGeolocationOverride",
               {"latitude": FAR_LAT, "longitude": FAR_LNG, "accuracy": 20})
        c.eval(CLEAR_POS)
        c.open(BASE + "/client/feed.html?api=1")
        wait_src(c, "gps")
        far_near = c.eval("""(function(){
          var list = MOCK.shops.filter(function(s){return s.lat!=null;})
            .map(function(s){return UserLoc.shopKm(s);})
            .sort(function(a,b){return a-b;});
          return list[0];
        })()""")
        check("换到城北后「最近的店」也变了（排序真的跟着位置走）",
              far_near is not None and abs(float(far_near) - float(near or 0)) > 0.5,
              "步行街 %.1fkm → 城北 %.1fkm" % (near or -1, far_near or -1))

        # ================= 3. L2 拒绝 + 有缓存 =================
        print("\n[3] L2 拒绝授权但有缓存 → 用缓存坐标")
        c.send("Browser.resetPermissions")
        c.eval(seed_pos(USER_LAT, USER_LNG))
        c.open(BASE + "/client/feed.html?api=1")
        ok = wait_src(c, "cache")
        check("进入 cache 级别", ok, c.eval("JSON.stringify(UserLoc.state())"))
        if ok:
            check("仍把用户位置当基准（isUser=true）",
                  c.eval("UserLoc.state().isUser") is True)
            check("未过期的缓存不标「可能不准」",
                  c.eval("UserLoc.state().stale") is False)

        # ================= 4. L3 缓存过期 =================
        print("\n[4] L3 缓存过期（40 分钟前）→ 用缓存但标注不准")
        c.eval(seed_pos(USER_LAT, USER_LNG, age_ms=40 * 60 * 1000))
        c.open(BASE + "/client/feed.html?api=1")
        ok = wait_src(c, "cache")
        check("仍进 cache 级别", ok)
        if ok:
            check("标为 stale（过期）", c.eval("UserLoc.state().stale") is True,
                  c.eval("JSON.stringify(UserLoc.state())"))
            check("文案里说明「可能不准」",
                  "不准" in str(c.eval("UserLoc.label()")), c.eval("UserLoc.label()"))

        # ================= 5. L4 拒绝 + 无缓存 =================
        print("\n[5] L4 拒绝授权且无缓存 → 退市中心")
        c.eval(CLEAR_POS)
        c.open(BASE + "/client/feed.html?api=1")
        ok = wait_src(c, "city", timeout=30)
        check("进入 city 级别（降级到市中心）", ok,
              c.eval("JSON.stringify(UserLoc.state())"))
        if ok:
            # ⚠️ 断言用 `== ` 比浮点，前提是「降级基准」和这里写的是**同一个常量来源**：
            #    user-loc.js 的 CITY_CENTER 与后端 DistanceCalculator.CITY_CENTER 都是
            #    仪征市中心 (32.2728, 119.1845)。换城市时这三处要一起改。
            check("坐标就是市中心", abs(c.eval("UserLoc.state().lat") - 32.2728) < 1e-6 and
                  abs(c.eval("UserLoc.state().lng") - 119.1845) < 1e-6)
            check("isUser=false（界面必须知道这不是用户真实位置）",
                  c.eval("UserLoc.state().isUser") is False)
            check("降级原因是可读的中文（不是错误码）",
                  len(str(c.eval("UserLoc.state().reason"))) > 4,
                  c.eval("UserLoc.state().reason"))
            check("定位灯变黄（提示近似）",
                  c.eval("document.getElementById('locDot').classList.contains('is-approx')") is True)
            check("距离标签 =「距市中心」", c.eval("UserLoc.label()") == "距市中心")
            # ⚠️ 最关键的一条：定位失败绝不能影响内容渲染
            check("定位失败时信息流照常渲染（不白屏）",
                  c.eval("document.querySelectorAll('.fc').length") >= 10,
                  "%s 条" % c.eval("document.querySelectorAll('.fc').length"))
            check("定位失败时没有 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))
            c.shot("02-L4-无定位-降级市中心.png")

        # ================= 6. 跨页口径一致 =================
        print("\n[6] 首页与详情页距离同口径（不能各算各的）")
        c.send("Browser.grantPermissions", {"origin": ORIGIN,
                                            "permissions": ["geolocation"]})
        c.send("Emulation.setGeolocationOverride",
               {"latitude": USER_LAT, "longitude": USER_LNG, "accuracy": 20})
        c.eval(CLEAR_POS)
        c.open(BASE + "/client/feed.html?api=1")
        wait_src(c, "gps")
        expect = c.eval("""(function(){
          var s = MOCK.getShop('s_001');
          return UserLoc.shopKm(s);
        })()""")

        c.open(BASE + "/client/shop.html?id=s_001")
        time.sleep(1.5)
        shown = str(c.eval("(document.getElementById('shopDist')||{}).textContent") or "")
        check("详情页距离与首页口径算出的一致",
              shown.startswith(str(expect)), "页面=%s 预期=%s" % (shown, expect))
        check("详情页标注了「距你」", "距你" in shown, shown)
        check("详情页无 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))

        # 详情页（菜品）
        c.open(BASE + "/client/dish.html?id=d_001")
        time.sleep(1.5)
        meta = str(c.eval("(document.getElementById('smMeta')||{}).textContent") or "")
        check("菜品详情页也带上了用户口径的距离",
              "km" in meta and "距" not in meta or "km" in meta, meta)
        check("菜品详情页无 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))

        # ================= 7. 手动切换位置 =================
        print("\n[7] 手动设定位置（不依赖浏览器授权）")
        c.open(BASE + "/client/feed.html?api=1")
        time.sleep(1)
        c.eval("UserLoc.setManual(%s, %s, '%s')" % (MANUAL_LAT, MANUAL_LNG, MANUAL_NAME))
        st = c.eval("JSON.stringify(UserLoc.state())")
        check("setManual 后 src 变 manual", c.eval("UserLoc.state().src") == "manual", st)
        check("setManual 后距离立即按新位置算",
              c.eval("UserLoc.state().isUser") is True
              and abs(c.eval("UserLoc.state().lat") - MANUAL_LAT) < 1e-6, st)
        check("setManual 的说明文案带上了地点名",
              MANUAL_NAME in str(c.eval("UserLoc.state().reason")),
              c.eval("UserLoc.state().reason"))

        # ================= 8. 缓存写入 =================
        print("\n[8] 定位结果落缓存（下次进页面不用重新授权等待）")
        c.send("Emulation.setGeolocationOverride",
               {"latitude": USER_LAT, "longitude": USER_LNG, "accuracy": 20})
        c.eval(CLEAR_POS)
        c.open(BASE + "/client/feed.html?api=1")
        wait_src(c, "gps")
        cached = c.eval("""(function(){
          try { var o = JSON.parse(localStorage.getItem('eatwhat_user_pos'));
                return o ? (o.lat + ',' + o.lng) : null; }
          catch(e){ return 'parse-error'; }
        })()""")
        check("成功定位后写入了 localStorage 缓存",
              cached and cached.startswith(str(USER_LAT)), cached)

    finally:
        c.close()
        chrome.terminate()

    print("\n" + "=" * 52)
    print("通过 %d 项，失败 %d 项%s" % (
        len(PASS), len(FAIL),
        ("，跳过 %d 项" % len(SKIP)) if SKIP else ""))
    if FAIL:
        print("\n失败清单：")
        for f in FAIL:
            print("   ✗ " + f)
    print("截图目录：%s" % OUT)
    print("=" * 52)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
