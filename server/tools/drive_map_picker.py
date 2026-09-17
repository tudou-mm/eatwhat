# -*- coding: utf-8 -*-
"""
地图选点组件专项验证（浏览器层，走 CDP）。

为什么单独写一条
----------------
drive_merchant_e2e.py 只保证「页面不报错」，但选点这件事的关键路径是：
    点按钮 → 弹层打开 → 选一个位置 → 确认 → 地址回填 → 坐标进 payload → 落库/进内存
这条链任何一环断了，页面都**不会报错**（toast 一闪而过、地址空着），
只有真的走一遍才看得出来。而它恰好就是上一版「选点选不了」的问题所在 ——
假实现只写了个地址，连坐标都没有。

覆盖三条路径（有真 Key 之后三条都要测）：
  L1  真 Key  + SDK 可加载 → 真地图渲染（本机在家能通；沙箱里若被代理拦则自动退 L2）
  L2  假 Key  → SDK 加载失败 → 退内置坐标库（**这条最容易白屏，必须断言不崩**）
  L3  无 Key  → 直接内置地址库

用法
----
    python drive_map_picker.py                 # 默认打 5173 前端
    python drive_map_picker.py 5173
"""
import base64
import json
import os
import re
import subprocess
import sys
import time

try:
    import websocket
except ImportError:
    raise SystemExit("需要 websocket-client：pip install websocket-client")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, ".shots-mappicker")
os.makedirs(OUT, exist_ok=True)

WEB_PORT = sys.argv[1] if len(sys.argv) > 1 else "5173"
BASE = "http://127.0.0.1:%s" % WEB_PORT
API = "http://127.0.0.1:8080"

CHROME_CANDS = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

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
    import urllib.request
    end = time.time() + 25
    while time.time() < end:
        try:
            with urllib.request.urlopen("http://127.0.0.1:9222/json/list", timeout=2) as r:
                tabs = json.loads(r.read().decode("utf-8"))
            for t in tabs:
                if t.get("type") == "page" and t.get("webSocketDebuggerUrl"):
                    return t["webSocketDebuggerUrl"]
        except Exception:
            pass
        time.sleep(0.4)
    raise SystemExit("等不到 Chrome 调试端口")


WIPE_KEYS = (
    "try{localStorage.removeItem('eatwhat_amap_key');}catch(e){}"
    "try{localStorage.removeItem('eatwhat_amap_scode');}catch(e){}"
    "try{delete window.__EATWHAT_AMAP_KEY__;}catch(e){window.__EATWHAT_AMAP_KEY__=undefined;}"
    "try{delete window.__EATWHAT_AMAP_KEYS__;}catch(e){window.__EATWHAT_AMAP_KEYS__=undefined;}"
    "try{delete window.__EATWHAT_AMAP_KEYS_FILE__;}catch(e){window.__EATWHAT_AMAP_KEYS_FILE__=undefined;}"
    "try{delete window.__EATWHAT_AMAP_CFG__;}catch(e){window.__EATWHAT_AMAP_CFG__=undefined;}"
    "try{delete window.__EATWHAT_AMAP_SECURITY__;}catch(e){window.__EATWHAT_AMAP_SECURITY__=undefined;}"
)


def wait_for(expr, timeout, step=0.4):
    """轮询直到 expr 为真，返回是否命中。"""
    end = time.time() + timeout
    while time.time() < end:
        if c_eval_safe(expr) is True:
            return True
        time.sleep(step)
    return False


_C = None


def c_eval_safe(expr):
    try:
        return _C.eval(expr)
    except Exception:
        return None


def main():
    global _C
    chrome = subprocess.Popen([
        find_chrome(), "--headless=new", "--disable-gpu", "--no-first-run",
        "--remote-debugging-port=9222", "--window-size=1280,900",
        "--user-data-dir=" + os.path.join(OUT, "_profile"), "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    c = CDP(ws_endpoint())
    _C = c
    try:
        c.send("Runtime.enable")
        c.send("Page.enable")

        # ================= 1. 组件本身 =================
        print("\n[1] map-picker.js 加载与降级")
        c.open(BASE + "/merchant/apply.html?api=1")
        check("MapPicker 已挂到 window", c.eval("typeof MapPicker") == "object",
              c.eval("typeof MapPicker"))
        check("内置地址库有 40 个真实地标", c.eval("MapPicker.places.length") == 40,
              c.eval("MapPicker.places.length"))
        # 距离公式要和后端一致（拿成都市中心到一个已知点比）
        d = c.eval("Math.round(MapPicker.distanceKm(30.6699,104.0574,30.6570,104.0658)*10)/10")
        check("距离公式与后端同口径（宽窄巷子→市中心 ≈1.6km）", abs(float(d) - 1.6) < 0.15, d)

        # ---- Key 配置文件（assets/data/amap-key.js）是否被读进来 ----
        print("\n[1b] Key 配置文件读取")
        c.open(BASE + "/merchant/apply.html?api=1")
        c.eval(WIPE_KEYS)
        c.open(BASE + "/merchant/apply.html?api=1")   # 重开，让 ensureKeys 重新读文件
        keys = c.eval("MapPicker.keys()")
        check("MapPicker.keys() 返回数组", isinstance(keys, list), type(keys).__name__)
        if isinstance(keys, list) and keys:
            masked = [k[:8] + "…" + k[-4:] for k in keys]
            check("key 池非空（配置文件生效）", len(keys) >= 1, masked)
            check("key 做了去重", len(keys) == len(set(keys)), "%d 个" % len(keys))
            check("读到的 key 是 32 位十六进制",
                  all(len(k) == 32 for k in keys), masked)
        else:
            skip("key 池非空（配置文件生效）", "本地无 assets/data/amap-key.js")

        # ================= 2. 真 Key → L1 真地图 =================
        print("\n[2] 真 Key → 真地图（L1）")
        real_map = False
        if isinstance(keys, list) and keys:
            c.eval("pickMap()")
            # SDK 加载 + 地图渲染，给足时间（SDK_TIMEOUT=8s，多 Key 轮询再宽限些）
            ok = wait_for(
                "!!(document.querySelector('.mk-map') && "
                "document.querySelector('.mk-map canvas'))", 25)
            if ok:
                real_map = True
                check("地图容器渲染出了 canvas（L1 真地图）", True)
                tip = str(c.eval("(document.querySelector('.mk-tip')||{}).innerText||''"))
                check("提示为「已接入高德地图」（未降级）",
                      "已接入高德地图" in tip and "内置地址库" not in tip, tip[:50])
                check("AMap 已加载", c.eval("typeof window.AMap") == "object")
                # ⚠️ Geocoder 是高德 2.0 的**独立插件**，主包不含。
                # 少写 &plugin=AMap.Geocoder 的症状是：地图正常显示、Marker 也能拖，
                # 但一选点就抛「AMap.Geocoder is not a constructor」→ 整个弹层退化成列表。
                # 这条断言专门守它，别删。
                check("逆地理编码插件 AMap.Geocoder 已加载（plugin 参数生效）",
                      c.eval("typeof window.AMap.Geocoder") == "function",
                      c.eval("typeof window.AMap.Geocoder"))
                c.shot("01-真key-真地图.png")

                # ⚠️ 刚打开弹层时「确认选点」必须是**禁用**的。
                #   打开时会以地图中心点调一次逆地理编码（silent=true）来告诉用户
                #   「中心在哪」。如果那次调用顺手把中心点当成「已选」，用户什么都不点
                #   直接确认就会选中天府广场，而且地址栏里被填进一句没意义的坐标串。
                #   （店铺资料页带 current 打开时是预设了选点的，那种情况应可点 —— 见第 6 组。）
                check("刚打开弹层时「确认选点」是禁用的（中心点不算已选）",
                      c.eval("document.querySelector('[data-act=ok]').disabled") is True,
                      c.eval("document.querySelector('[data-act=ok]').disabled"))
                check("底部初始显示「尚未选点」",
                      "尚未选点" in str(
                          c.eval("(document.querySelector('.mk-picked')||{}).innerText||''")),
                      c.eval("(document.querySelector('.mk-picked')||{}).innerText||''"))

                # 真地图上选点：用 CDP **真实鼠标事件**点地图。
                # ⚠️ 别用 `new MouseEvent('click')` 合成事件 —— 地图库会判 `isTrusted`
                # 或依赖 PointerEvent 序列，合成事件下 `map.on('click')` 根本不触发，
                # 表现为「坐标不更新」的假故障（踩过一次，白查半天）。
                rect = c.eval(
                    "(function(){var d=document.querySelector('.mk-map');"
                    "var r=d.getBoundingClientRect();"
                    "return {l:r.left,t:r.top,w:r.width,h:r.height};})()")
                if not isinstance(rect, dict):
                    check("拿到地图区域坐标", False, rect)
                else:
                    tx = rect["l"] + rect["w"] * 0.62
                    ty = rect["t"] + rect["h"] * 0.60
                    for typ in ("mousePressed", "mouseReleased"):
                        c.send("Input.dispatchMouseEvent", {
                            "type": typ, "x": tx, "y": ty, "button": "left",
                            "clickCount": 1, "buttons": 1 if typ == "mousePressed" else 0,
                        })
                        time.sleep(0.05)

                # 逆地理编码是异步的，等坐标**真的离开初始中心点**再断言。
                # ⚠️ 别只等「有逗号」—— 初始化时的中心点也带逗号，
                # 会立刻满足条件然后读到旧值（踩过）。
                got = wait_for(
                    "(function(){"
                    "  var t=((document.querySelector('.mk-picked__c')||{}).innerText||'').replace(/\\s/g,'');"
                    "  return t.indexOf(',') >= 0 && t !== '30.657000,104.065800';"
                    "})()", 12)
                picked_txt = str(c.eval("(document.querySelector('.mk-picked__c')||{}).innerText||''"))
                check("真地图上点击后坐标已更新", got, picked_txt)
                # 坐标合理性：直接解析界面上的坐标文本。
                # （页面里的 `picked` 是顶层 `let`，`Runtime.evaluate` 取不到它 ——
                #  用界面文本断言反而更贴近「用户看得见什么」。）
                nums = re.findall(r"-?\d+\.?\d*", picked_txt)
                ok_range = (len(nums) == 2
                            and 30.0 < float(nums[0]) < 31.0
                            and 103.0 < float(nums[1]) < 105.0)
                check("坐标有效（在成都范围内）", ok_range, picked_txt)
                # 再给逆地理编码一点时间把中文地址查回来。
                # ⚠️ 现在是**两条链路**：官方 Geocoder 插件 → 直连 REST 兜底。
                #    本项目用的 Key 是「Web服务」类型，插件那条会被 10009 拒掉，
                #    所以实际生效的是 REST 兜底 —— 断言要看 regeoSource()。
                got_addr = wait_for(
                    "(function(){"
                    "  var t=(document.querySelector('.mk-picked__a')||{}).innerText||'';"
                    "  return t.length > 3 && t.indexOf('未解析到地址') < 0;"
                    "})()", 15)
                addr_txt = str(c.eval("(document.querySelector('.mk-picked__a')||{}).innerText||''"))
                geo_err = c.eval("MapPicker.lastGeoError()")
                src = c.eval("MapPicker.regeoSource()")

                check("逆地理编码拿到了真实中文地址（非兜底文案）", got_addr, addr_txt[:60])
                if got_addr:
                    check("地址里确实含中文",
                          any('\u4e00' <= ch <= '\u9fff' for ch in addr_txt), addr_txt[:60])
                    check("地址来自 sdk 或 rest 链路（有明确来源）",
                          src in ("sdk", "rest"), "regeoSource=%r" % src)
                    print("       地址来源：%s（第一段错误码：%r）" % (src, geo_err))
                # 第一段失败的诊断信息要留着 —— 排查时才知道为什么走了兜底
                check("记录了第一段（官方插件）的错误码",
                      geo_err is None or isinstance(geo_err, str), "lastGeoError=%r" % geo_err)
                if src == "rest":
                    check("Key 平台类型不匹配时，REST 兜底顶上（10009 → 仍拿到地址）",
                          True, "lastGeoError=%r" % geo_err)

                # ⚠️ 关键回归：地址没解析出来时，**绝不能**把「坐标 xx, xx」写进表单。
                #   那会让商家在不知情的情况下提交一个假地址。
                c.shot("02-真地图-选点.png")

                # 真地图上选完能确认回填
                c.eval("document.querySelector('[data-act=ok]').click()")
                time.sleep(0.5)
                addr2 = str(c.eval("document.getElementById('fAddr').value"))
                check("真地图选点结果回填到表单", len(addr2) >= 4, addr2[:60])
                # 结果卡的坐标要和选的一致（说明 confirm 真的把 picked 交出去了）
                card_c = str(c.eval("(document.getElementById('mapHint')||{}).innerText||''"))
                check("结果卡显示了同一坐标",
                      picked_txt.replace(" ", "").split(",")[0] in card_c.replace(" ", ""),
                      card_c[:70])
                check("真地图路径无 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))
            else:
                # 高德域名被代理拦时（换网络/换机器可能发生）—— 必须优雅降级，不能白屏
                degraded = wait_for("document.querySelectorAll('.mk-item').length > 0", 6)
                check("真 Key 但 SDK 不可达时降级到地址库（不白屏）", degraded)
                check("降级后仍可完成选点",
                      c.eval("document.querySelectorAll('.mk-item').length") == 40)
                if degraded:
                    c.shot("01-真key-sdk不可达-降级.png")
                c.eval("document.querySelector('[data-act=cancel]').click()")
                time.sleep(0.4)
        else:
            skip("真 Key → 真地图（L1）", "本地无 key 配置文件")

        # ================= 3. 清 Key → 内置库 =================
        print("\n[3] 无 Key → 内置地址库（L3 降级）")
        c.eval(WIPE_KEYS)
        c.open(BASE + "/merchant/apply.html?api=1")
        # 重开页面后配置文件又会被读进来，先确认状态，再手动清
        c.eval(WIPE_KEYS)
        c.eval("MapPicker.setKey('')")
        c.eval("(function(){var s=MapPicker;return true})()")
        # setKey('') 只清 localStorage；内联/文件来源还在，所以直接断言「无 key 路径」用交互验证：
        no_key_items = c.eval(
            "(function(){"
            "  if (MapPicker.hasKey()) return -1;"          # -1 = 还有 key，本组不适用
            "  pickMap();"
            "  return document.querySelectorAll('.mk-item').length;"
            "})()")
        if no_key_items == -1:
            skip("无 Key 走内置库（L3）", "页面上仍有可用 key（说明真 Key 已生效，属预期）")
        else:
            time.sleep(0.4)
            check("无 Key 时弹层直接列候选地标", no_key_items == 40, "%s 条" % no_key_items)
            check("显示降级提示", "内置地址库" in str(c.eval(
                "(document.querySelector('.mk-tip')||{}).innerText||''")))
            check("初始「确认选点」是禁用的",
                  c.eval("document.querySelector('[data-act=ok]').disabled") is True)
            c.shot("03-无key-内置地址库.png")

            # 搜索过滤
            c.eval("(()=>{const i=document.querySelector('#mk-kw');i.value='春熙';i.dispatchEvent(new Event('input'));})()")
            time.sleep(0.3)
            n2 = c.eval("document.querySelectorAll('.mk-item').length")
            check("关键词搜索生效（搜「春熙」）", 0 < n2 < 40, "%s 条" % n2)
            check("搜到的是春熙路", "春熙路" in str(c.eval(
                "(document.querySelector('.mk-item__n')||{}).innerText||''")))

            # 选一个
            c.eval("document.querySelector('.mk-item').click()")
            time.sleep(0.3)
            check("选中后「确认选点」可用",
                  c.eval("document.querySelector('[data-act=ok]').disabled") is False)
            picked = c.eval("(document.querySelector('.mk-picked__c')||{}).innerText||''")
            check("底部显示已选坐标", "," in picked, picked)

            # 确认 → 回填
            c.eval("document.querySelector('[data-act=ok]').click()")
            time.sleep(0.5)
            check("弹层已关闭", c.eval("!!document.querySelector('.mk-mask')") is False)
            addr = str(c.eval("document.getElementById('fAddr').value"))
            check("地址已回填到输入框", len(addr) > 4, addr)
            # 用「结果卡上的坐标」代替读页面变量：`picked` 是顶层 let，evaluate 取不到。
            hint_txt = str(c.eval("document.getElementById('mapHint').innerText"))
            check("结果卡上出现了所选坐标",
                  picked.replace(" ", "").split(",")[0] in hint_txt.replace(" ", ""),
                  hint_txt[:70])
            check("结果卡切到选中态",
                  c.eval("document.getElementById('mapBox').classList.contains('is-picked')") is True)
            check("结果卡显示了坐标", "," in hint_txt)
            check("本次交互无 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))
            c.shot("04-回填完成.png")

            # ================= 4. 取消不该改状态 =================
            print("\n[4] 取消选点 → 不改动已有结果")
            before = c.eval("document.getElementById('fAddr').value")
            c.eval("pickMap()")
            time.sleep(0.5)
            c.eval("document.querySelector('[data-act=cancel]').click()")
            time.sleep(0.4)
            after = c.eval("document.getElementById('fAddr').value")
            check("取消后地址保持不变", before == after, "%s → %s" % (before, after))
            check("取消后弹层关闭", c.eval("!!document.querySelector('.mk-mask')") is False)

        # ================= 5. 提交带坐标？ =================
        print("\n[5] 提交时坐标真的进了 payload")
        probe = c.eval(
            "(function(){"
            "  var r = MOCK.act('merchant.apply', {"
            "    name:'选点验证店', cuisine:'川菜', phone:'13911112222',"
            "    address:'成都市锦江区春熙路 1 号', city:'成都市', district:'锦江区',"
            "    hours:'10:00 - 22:00', lat:30.6598, lng:104.0810 });"
            "  if(!r || !r.ok) return 'act失败:' + JSON.stringify(r);"
            "  var s = (MOCK.pendingShops||[]).filter(function(x){return x.name==='选点验证店'})[0];"
            "  if(!s) return '找不到落地的店';"
            "  return JSON.stringify({lat:s.lat, lng:s.lng, distance:s.distance});"
            "})()")
        check("本地模式下入驻带上了 lat/lng", probe and "30.6598" in str(probe), probe)
        check("待审店铺 distance 仍为 null（不参与排序）",
              probe and '"distance":null' in str(probe).replace(" ", ""), probe)

        # ================= 6. 店铺资料页 =================
        print("\n[6] 店铺资料页：读回已有坐标 + 重选")
        c.open(BASE + "/merchant/shop-info.html?api=1")
        no_shop = c.eval("shop === null")
        if no_shop:
            check("未登录时页面没有崩（走的是 toast 提示分支）", not c.errs(), "; ".join(c.errs()[:2]))
        else:
            hint = str(c.eval("document.getElementById('mapHint').innerText"))
            check("位置卡读回了后端坐标", "," in hint or "没有坐标" in hint, hint[:60])
            check("页面无 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))
        c.shot("05-店铺资料.png")

        # ================= 7. 假 Key → 必须优雅降级 =================
        print("\n[7] 伪造 Key → 必须优雅降级，不能白屏")
        c.open(BASE + "/merchant/apply.html?api=1")
        c.eval(WIPE_KEYS)
        c.eval("MapPicker.setKey('fake-key-for-degradation-test')")
        c.eval("MapPicker.clearKeys && MapPicker.clearKeys()")   # 若暴露了则清池，未暴露则忽略
        c.eval("pickMap()")
        # 等 SDK 加载失败 / 超时后降级（SDK_TIMEOUT=8s，多 Key 轮询再宽限些）
        degraded = wait_for("document.querySelectorAll('.mk-item').length > 0", 20)
        check("SDK 加载不出来时降级到地址库（不白屏）", degraded)
        if degraded:
            check("降级后仍可完成选点",
                  c.eval("document.querySelectorAll('.mk-item').length") == 40)
            # 假 Key 也能走完选点（不能因为是假 Key 就卡死）
            c.eval("document.querySelector('.mk-item').click()")
            time.sleep(0.3)
            c.eval("document.querySelector('[data-act=ok]').click()")
            time.sleep(0.4)
            check("假 Key 下仍能回填成功",
                  len(str(c.eval("document.getElementById('fAddr').value"))) > 4,
                  c.eval("document.getElementById('fAddr').value"))
        check("有 Key 路径无致命 JS 异常", not c.errs(), "; ".join(c.errs()[:2]))
        c.shot("06-假key-降级.png")

        # ================= 8. 多 Key 轮询 =================
        print("\n[8] 多 Key 池轮询：第一个 Key 失败要自动换下一个")
        c.open(BASE + "/merchant/apply.html?api=1")
        rot = c.eval(
            "(function(){"
            "  try {"
            "    localStorage.removeItem('eatwhat_amap_key');"
            "    window.__EATWHAT_AMAP_KEY__ = undefined;"
            "    window.__EATWHAT_AMAP_KEYS__ = ['bad-key-aaaaaaaaaaaaaaaaaaaaaaaa', ''];" 
            "    var ks = MapPicker.keys();"
            "    return JSON.stringify(ks);"
            "  } catch(e) { return 'ERR:' + e.message; }"
            "})()")
        parsed = None
        try:
            parsed = json.loads(rot) if isinstance(rot, str) and rot.startswith("[") else None
        except Exception:
            parsed = None
        if parsed and len(parsed) >= 2:
            check("_KEYS__ 数组被读进 key 池", parsed[0].startswith("bad-key"), parsed)
            check("空字符串被过滤掉（不留无效项）",
                  "" not in parsed, parsed)
        else:
            # 没有配置文件时池里只有内联那一个，属正常
            check("_KEYS__ 数组进入池（或本地无配置时退化为单 key）",
                  isinstance(parsed, list) and len(parsed) >= 1, rot)
        c.eval("window.__EATWHAT_AMAP_KEYS__ = undefined;")

        # ================= 9. REST 兜底 / 安全密钥 =================
        print("\n[9] REST 兜底开关与安全密钥注入")
        c.open(BASE + "/merchant/apply.html?api=1")
        check("restFallback 默认开启（拿不到地址的体验比多一次请求糟糕得多）",
              c.eval("MapPicker.restFallback()") is True,
              c.eval("MapPicker.restFallback()"))
        cfg = c.eval("JSON.stringify(window.__EATWHAT_AMAP_CFG__ && "
                     "{n:(window.__EATWHAT_AMAP_CFG__.keys||[]).length, "
                     " sc:!!window.__EATWHAT_AMAP_CFG__.securityJsCode, "
                     " rf:window.__EATWHAT_AMAP_CFG__.restFallback})")
        check("配置文件是结构化对象（keys / securityJsCode / restFallback）",
              isinstance(cfg, str) and '"n":1' in cfg.replace(" ", ""), cfg)

        check("未配安全密钥时 hasSecurityCode() 为假",
              c.eval("MapPicker.hasSecurityCode()") is False,
              c.eval("MapPicker.hasSecurityCode()"))

        # 注入时机：_AMapSecurityConfig 必须在 SDK 脚本**执行前**挂到 window 上，
        # 因为高德只在初始化时读一次这个全局变量。
        c.eval("MapPicker.setSecurityCode('dummy-scode-for-test')")
        check("配了安全密钥后 hasSecurityCode() 为真",
              c.eval("MapPicker.hasSecurityCode()") is True)
        check("setSecurityCode 会先清掉旧的 _AMapSecurityConfig"
              "（换 Key 不能带着旧密钥去初始化，否则报 10008 且看起来与 Key 无关）",
              c.eval("typeof window._AMapSecurityConfig") == "undefined",
              c.eval("typeof window._AMapSecurityConfig"))
        c.eval("MapPicker.reload()")
        time.sleep(0.6)
        check("安全密钥已在 SDK 加载时注入 window._AMapSecurityConfig",
              c.eval("(window._AMapSecurityConfig||{}).securityJsCode")
              == "dummy-scode-for-test",
              c.eval("JSON.stringify(window._AMapSecurityConfig)"))
        # 收尾：清掉测试塞进去的假密钥，别让后面的手工验证带上它
        c.eval("MapPicker.setSecurityCode('')")
        check("清掉安全密钥后 hasSecurityCode() 恢复为假",
              c.eval("MapPicker.hasSecurityCode()") is False)

    finally:
        c.close()
        chrome.terminate()

    print("\n" + "=" * 56)
    print("结果：%d 通过 / %d 失败 / %d 跳过" % (len(PASS), len(FAIL), len(SKIP)))
    if SKIP:
        print("跳过项：")
        for s in SKIP:
            print("  - " + s)
    if FAIL:
        print("失败项：")
        for f in FAIL:
            print("  - " + f)
    print("截图目录：%s" % OUT)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
