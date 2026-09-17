# -*- coding: utf-8 -*-
"""
从运行中的后端导出数据，重新生成 assets/data/mock.js 的数据段。

为什么要有这个脚本
------------------
mock.js 是离线原型的唯一数据源（后端没启动时页面靠它活），
DataSeeder.java 是后端的数据源。两边各写一份一定会漂移 ——
实际已经漂过一次：后端口味标签 7 个、前端 10 个，谁也没发现。

所以定死一条规矩：**数据只在 DataSeeder.java 里维护**，
改完重跑本脚本把 mock.js 刷一遍。生成的数据段带「自动生成」标注，
手改会被下次导出覆盖。

只重写数据段，不碰：
  - 头部 currentUser / currentMerchant / currentAdmin（属于前端演示状态，不是后端数据）
  - 中间的工具函数（getShop / buildFeed / timeDecay … 是前端逻辑）
  - 末尾的 window.MOCK 暴露

用法
----
    python export_mock.py [后端地址]      # 默认 http://127.0.0.1:8080

鉴权
----
后端加了 JWT 之后，平台端接口（/admin/**）没 token 会直接 401。
三条路，任选其一（脚本按顺序试）：

  1. 环境变量 EATWHAT_ADMIN_TOKEN   —— 已有 token 直接传进来
  2. 环境变量 EATWHAT_ADMIN_PASSWORD —— 账号密码，脚本自己换 token
  3. 都没有 → 用内置的演示账号 admin/admin123 去换

第 3 条是为了「clone 下来就能跑」：演示库的账号是公开的，不算秘密。
真上线了记得显式传 1 或 2，别依赖默认值。
"""
import io
import json
import os
import sys
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
TARGET = os.path.join(ROOT, "assets", "data", "mock.js")

_TOKEN = None


def _login():
    """换一个平台端 token。优先环境变量，其次内置演示账号。"""
    pwd = os.environ.get("EATWHAT_ADMIN_PASSWORD") or "admin123"
    body = json.dumps({"role": "admin", "account": "admin", "password": pwd}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        j = json.loads(r.read().decode("utf-8"))
    if j.get("code") != 0:
        raise SystemExit("登录失败：%s（可设 EATWHAT_ADMIN_PASSWORD 覆盖密码）" % j.get("msg"))
    return j["data"]["token"]

# 切分点用标记而不是行号，脚本改动后行号会漂。
# 数据段 / 平台段的起点都要「既认原始手写标记、又认本脚本自己写出去的标记」，
# 否则第二次运行找不到切分点、或者把上一次的头部注释又包一层（两种都踩过）。
DATA_HEADER = ("  /* ==========================================================\n"
               "     数据段（自动生成）\n"
               "     ----------------------------------------------------------\n"
               "     由 server/tools/export_mock.py 生成，请勿手改。\n"
               "     改数据请改 server/.../config/DataSeeder.java，然后重跑导出脚本。\n"
               "     ========================================================== */\n")
DATA_START_CANDIDATES = [
    "  /* ==========================================================\n     数据段（自动生成）",
    "  /* ---------- 价格档位（平台配置） ---------- */",
]
HELPER_START = "  /* ==========================================================\n     工具函数"
PLATFORM_HEADER = ("/* ==========================================================\n"
                   "   平台端专用数据（自动生成）\n"
                   "   ----------------------------------------------------------\n"
                   "   后端在线时由 /admin/bootstrap 覆盖；这里是离线演示数据。\n"
                   "   整段由 server/tools/export_mock.py 生成，请勿手改。\n"
                   "   ========================================================== */\n")
PLATFORM_START_CANDIDATES = [
    "/* ==========================================================\n   平台端专用数据（自动生成）",
    "/* ==========================================================\n   平台端专用数据",
]
TAIL = "/* 暴露到全局 */\nwindow.MOCK = MOCK;\n"

WIDTH = 116


def api(path):
    global _TOKEN
    if _TOKEN is None:
        _TOKEN = os.environ.get("EATWHAT_ADMIN_TOKEN") or _login()
    req = urllib.request.Request(BASE + "/api" + path,
                                 headers={"Authorization": "Bearer " + _TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            j = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise SystemExit("接口 %s 返回 401：token 失效或不匹配当前 data 段。\n"
                             "  后端重启过（H2 内存库 = 重启即换库）会导致旧 token 作废，重跑一次即可。"
                             % path)
        raise
    if j.get("code") != 0:
        raise SystemExit("接口 %s 返回异常：%s" % (path, j.get("msg")))
    return j["data"]


# ---------- JS 字面量 ----------

def js_str(s):
    if s is None:
        return "null"
    s = (str(s).replace("\\", "\\\\").replace("'", "\\'")
         .replace("\r", "").replace("\n", "\\n"))
    return "'" + s + "'"


def js_val(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        if isinstance(v, float) and v.is_integer():
            return str(int(v))
        return repr(v)
    if isinstance(v, str):
        return js_str(v)
    if isinstance(v, list):
        return "[" + ", ".join(js_val(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{ " + ", ".join("%s: %s" % (k, js_val(x)) for k, x in v.items()) + " }"
    raise TypeError(type(v))


def emit_obj(segments, indent=4, comma=False):
    """把 ['k: v', ...] 打包成不超过 WIDTH 的若干行，返回行列表。

    comma=True 时在收尾的 } 后补一个逗号 —— 数组元素必须补，
    否则相邻两个对象之间没有分隔符（这是第一次跑就踩到的坑）。
    JS 允许尾逗号，所以最后一个元素补上也安全。
    """
    pad = " " * indent
    lines = []
    cur = pad + "{ "
    cont = pad + "  "
    for i, seg in enumerate(segments):
        token = seg + ("," if i < len(segments) - 1 else "")
        if cur == pad + "{ " or len(cur) + len(token) + 1 <= WIDTH:
            cur += token + " "
        else:
            lines.append(cur.rstrip())
            cur = cont + token + " "
    lines.append(cur.rstrip() + " }" + ("," if comma else ""))
    return lines


def emit_array(name, items, comment=None, build=None):
    """items 是原始 dict 列表；build(item) 返回 ['k: v', ...]"""
    out = []
    if comment:
        out.append("  /* ---------- %s ---------- */" % comment)
    if not items:
        out.append("  %s: []," % name)
        return out
    out.append("  %s: [" % name)
    for it in items:
        out.extend(emit_obj(build(it), comma=True))
    out.append("  ],")
    return out


# ---------- 各类对象的字段顺序（对齐 mock.js 原有顺序，方便 diff） ----------

def shop_segs(s):
    return [
        "id: %s" % js_val(s.get("id")),
        "name: %s" % js_val(s.get("name")),
        "logo: %s" % js_val(s.get("logo")),
        "cover: %s" % js_val(s.get("cover")),
        "address: %s" % js_val(s.get("address")),
        "city: %s" % js_val(s.get("city")),
        "district: %s" % js_val(s.get("district")),
        "lat: %s" % js_val(s.get("lat")),
        "lng: %s" % js_val(s.get("lng")),
        "distance: %s" % js_val(s.get("distance")),
        "phone: %s" % js_val(s.get("phone")),
        "hours: %s" % js_val(s.get("hours")),
        "cuisine: %s" % js_val(s.get("cuisine")),
        "intro: %s" % js_val(s.get("intro")),
        "status: %s" % js_val(s.get("status")),
        "weight: %s" % js_val(s.get("weight")),
        "pinned: %s" % js_val(s.get("pinned")),
        "canPostToday: %s" % js_val(s.get("canPostToday")),
        "intervalHours: %s" % js_val(s.get("intervalHours")),
        "dailyLimit: %s" % js_val(s.get("dailyLimit")),
        "lastPostAt: %s" % js_val(s.get("lastPostAt")),
        "submittedAt: %s" % js_val(s.get("submittedAt")),
        "reviewedAt: %s" % js_val(s.get("reviewedAt")),
        "reviewer: %s" % js_val(s.get("reviewer")),
        "rejectReason: %s" % js_val(s.get("rejectReason")),
        "stats: %s" % js_val(s.get("stats")),
    ]


def dish_segs(d):
    return [
        "id: %s" % js_val(d.get("id")),
        "shopId: %s" % js_val(d.get("shopId")),
        "shopName: %s" % js_val(d.get("shopName")),
        "name: %s" % js_val(d.get("name")),
        "desc: %s" % js_val(d.get("desc")),
        "type: %s" % js_val(d.get("type")),
        "media: %s" % js_val(d.get("media")),
        "cover: %s" % js_val(d.get("cover")),
        "price: %s" % js_val(d.get("price")),
        "priceTierId: %s" % js_val(d.get("priceTierId")),
        "realTag: %s" % js_val(d.get("realTag")),
        "tasteTags: %s" % js_val(d.get("tasteTags")),
        "publishedAt: %s" % js_val(d.get("publishedAt")),
        "status: %s" % js_val(d.get("status")),
        # 刻意不导出后端算的 decay：它是「请求那一刻」的瞬时值，
        # 写进静态文件就成了冻结的假数据。前端一律用本地
        # timeDecay(publishedAt) 现算（见 mock.js 的工具函数）。
        "stats: %s" % js_val(d.get("stats")),
    ]


def comment_segs(c):
    return [
        "id: %s" % js_val(c.get("id")),
        "dishId: %s" % js_val(c.get("dishId")),
        "userId: %s" % js_val(c.get("userId")),
        "userName: %s" % js_val(c.get("userName")),
        "avatar: %s" % js_val(c.get("avatar")),
        "content: %s" % js_val(c.get("content")),
        "at: %s" % js_val(c.get("at")),
        "reply: %s" % js_val(c.get("reply")),
    ]


def user_segs(u):
    return [
        "id: %s" % js_val(u.get("id")),
        "name: %s" % js_val(u.get("name")),
        "avatar: %s" % js_val(u.get("avatar")),
        "comments: %s" % js_val(u.get("comments")),
        "reports: %s" % js_val(u.get("reports")),
        "status: %s" % js_val(u.get("status")),
        "at: %s" % js_val(u.get("at")),
    ]


def report_segs(r):
    return [
        "id: %s" % js_val(r.get("id")),
        "type: %s" % js_val(r.get("type")),
        "targetId: %s" % js_val(r.get("targetId")),
        "targetName: %s" % js_val(r.get("targetName")),
        "reason: %s" % js_val(r.get("reason")),
        "reporter: %s" % js_val(r.get("reporter")),
        "at: %s" % js_val(r.get("at")),
        "status: %s" % js_val(r.get("status")),
    ]


def tier_segs(t):
    mx = t.get("max")
    return [
        "id: %s" % js_val(t.get("id")),
        "name: %s" % js_val(t.get("name")),
        "min: %s" % js_val(t.get("min")),
        # 后端用 -1 表示"无上限"（JSON 存不了 Infinity），前端要换回来
        "max: %s" % ("Infinity" if (mx is None or mx < 0) else js_val(mx)),
    ]


def main():
    admin = api("/admin/bootstrap")
    client = api("/client/bootstrap")
    cfg = admin.get("config") or {}

    # 平台端专用数据（离线时也能把 8 个页面看全）
    extra = []

    # 已通过审核的商家：直接取后端算好的（normal/muted/banned）
    approved = admin.get("approvedShops") or []
    extra.append("/* 已通过审核的商家：后端按 status ∈ {normal, muted, banned} 算好，这里直接用 */")
    extra.append("MOCK.approvedShops = [")
    for s in approved:
        extra.extend(emit_obj(shop_segs(s), indent=2, comma=True))
    extra.append("];")
    extra.append("")

    rejected = admin.get("rejectedShops") or []
    extra.append("/* 已驳回的商家：没有上线，所以 distance 为 null（给 0 会插到推荐榜首） */")
    extra.append("MOCK.rejectedShops = [")
    for s in rejected:
        extra.extend(emit_obj(shop_segs(s), indent=2, comma=True))
    extra.append("];")
    extra.append("")

    removed = admin.get("removed") or []
    extra.append("/* 已下架内容：下架不是物理删除，记录仍在，只是客户端看不到 */")
    extra.append("MOCK.removedDishes = [")
    for d in removed:
        extra.extend(emit_obj(dish_segs(d), indent=2, comma=True))
    extra.append("];")
    extra.append("")

    pr = cfg.get("publishRule") or {"intervalHours": 24, "dailyLimit": 1}
    extra.append("/* 全局默认发布规则（平台端「发布规则」页） */")
    extra.append("MOCK.publishRule = %s;" % js_val(pr))
    extra.append("")

    ov = admin.get("overview") or {}
    extra.append("/* 数据概览：导出时的快照，供离线模式首屏直接显示 */")
    extra.append("MOCK.overview = %s;" % js_val(ov))
    extra.append("")

    # ---- 组装数据段 ----
    data = []

    data.append("  /* ---------- 价格档位（平台配置） ---------- */")
    data.append("  priceTiers: [")
    for t in (cfg.get("priceTiers") or []):
        data.extend(emit_obj(tier_segs(t), comma=True))
    data.append("  ],")
    data.append("")

    data.append("  /* ---------- 口味标签库（平台配置） ---------- */")
    data.append("  tasteTags: %s," % js_val(cfg.get("tasteTags") or []))
    data.append("")

    data.append("  /* ---------- 真实性标签 ---------- */")
    data.append("  realTags: [")
    data.append("    { id: 'real',    name: '实拍认证', cls: 'tag--real'    },")
    data.append("    { id: 'ad',      name: '广告',     cls: 'tag--ad'      },")
    data.append("    { id: 'pending', name: '待核实',   cls: 'tag--pending' }")
    data.append("  ],")
    data.append("")

    # shops 用「全量」（含待审核 / 已驳回），与平台端在线模式一致。
    # 客户端页面不遍历 shops（feed 是从 dishes 推的），所以不会把未上线的店刷出来。
    data.extend(emit_array("shops", admin.get("shops") or [], "兴趣点（店铺 · 全量，含待审核 / 已驳回）", shop_segs))
    data.append("")
    data.extend(emit_array("dishes", admin.get("dishes") or [], "菜品（仅上架 normal）", dish_segs))
    data.append("")
    data.extend(emit_array("comments", client.get("comments") or [], "评论（含商家回评 reply）", comment_segs))
    data.append("")
    data.extend(emit_array("pendingShops", admin.get("pendingShops") or [], "待审核商家", shop_segs))
    data.append("")
    data.extend(emit_array("reports", admin.get("reports") or [], "举报", report_segs))
    data.append("")
    data.extend(emit_array("users", admin.get("users") or [], "用户（按被举报次数倒序）", user_segs))
    data.append("")
    data.append("  /* ---------- 口味 / 菜系下拉 ---------- */")
    data.append("  cuisines: %s," % js_val(cfg.get("cuisines") or []))
    data.append("")

    # ---- 拼回整份文件 ----
    src = io.open(TARGET, encoding="utf-8").read()
    hits_d = [src.index(m) for m in DATA_START_CANDIDATES if m in src]
    hits_p = [src.index(m) for m in PLATFORM_START_CANDIDATES if m in src]
    if not hits_d:
        raise SystemExit("找不到数据段切分点，mock.js 结构可能被改过")
    if not hits_p:
        raise SystemExit("找不到平台段切分点，mock.js 结构可能被改过")
    i_data, i_platform = min(hits_d), min(hits_p)
    i_helper = src.index(HELPER_START)
    if not (i_data < i_helper < i_platform):
        raise SystemExit("mock.js 三段顺序不对，拒绝写入以免损坏文件")

    head = src[:i_data]
    middle = src[i_helper:i_platform]

    out = (head + DATA_HEADER + "\n".join(data) + "\n\n"
           + middle.rstrip("\n") + "\n\n"
           + PLATFORM_HEADER + "\n".join(extra).rstrip("\n") + "\n\n" + TAIL)
    io.open(TARGET, "w", encoding="utf-8", newline="\n").write(out)

    print("已重新生成 %s" % TARGET)
    print("  店铺 %d（在线 %d / 待审 %d / 驳回 %d）" % (
        len(admin.get("shops") or []), len(approved),
        len(admin.get("pendingShops") or []), len(rejected)))
    print("  菜品 %d（已下架 %d）· 评论 %d · 用户 %d · 举报 %d" % (
        len(admin.get("dishes") or []), len(removed),
        len(client.get("comments") or []), len(admin.get("users") or []),
        len(admin.get("reports") or [])))
    print("  价格档 %d · 口味 %d · 菜系 %d" % (
        len(cfg.get("priceTiers") or []), len(cfg.get("tasteTags") or []),
        len(cfg.get("cuisines") or [])))
    print("  文件行数 %d" % (out.count("\n") + 1))


if __name__ == "__main__":
    main()
