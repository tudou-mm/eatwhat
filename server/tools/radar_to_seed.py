# -*- coding: utf-8 -*-
"""
radar.db  →  DataSeeder.java 种子数据生成器（路径 C）

把「仪征餐饮雷达」采集到的 2582 家高德 POI，转成本项目可用的演示种子。

## 选题条件（路径 C）
  gone=0 且 有电话 且 有评分  →  约 1642 家
  理由：本产品核心是「店主认领 → 自己发菜」，没电话的店在系统里是死账户，
  扩进去只是数字好看。见 docs 与记忆文件。

## 三个必须处理的字段问题
1. **镇级信息**：radar.db 的 `adname` 全是「仪征市」，没有镇/街道；
   `address` 只有 36.7% 带镇名 → **不能只靠字符串匹配**。
   解法：先用「地址含镇名的样本」算出各镇**坐标重心**，再用最近邻给剩下 63% 归类。
   （所有记录 lat/lng 100% 完整，这是可靠底座。）

2. **品类体系**：radar.db 的 `cuisine` 是高德粗桶（中餐厅 1211 / 餐饮相关 449…），
   直接映射等于没分类。真正细的信息在 **`keytag`** 里
   （淮扬菜 19 / 川菜 19 / 火锅 20 / 面馆 57 / 烧烤 47 …）。
   解法：**以 keytag 为主、cuisine 为辅**做映射表；
   映射不到的进「中餐」并标 `cuisineConfidence=low`。

3. **菜品缺失**：高德只有 POI 层，没有菜品 → 按品类模板生成（见 dish_templates.py）。

## 用法
    python radar_to_seed.py                     # 生成到 _gen/ 供检查
    python radar_to_seed.py --emit-java         # 直接产出 Java 片段
    python radar_to_seed.py --limit 400         # 限量（调试用）
"""
import argparse
import json
import math
import os
import random
import re
import sqlite3
import sys
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))          # 本项目根
RADAR_DB = r"D:\WorkBuddy\2026-09-18-09-31-17\yizheng-food-radar\data\radar.db"
OUT_DIR = os.path.join(HERE, "_gen")

# ---------------------------------------------------------------- 镇级

# 仪征的镇/街道。顺序有意义：长的、更具体的优先匹配
# （「仪化生活区」要在「青山镇」之类前面，避免子串误伤）
TOWNS = [
    "真州镇", "仪化生活区", "胥浦", "十二圩", "新集镇", "新城镇",
    "马集镇", "月塘镇", "青山镇", "陈集镇", "刘集镇", "大仪镇", "枣林湾",
]
FALLBACK_TOWN = "真州镇"      # 归不进去的（市中心周边）落在主城


def _haversine_km(lng1, lat1, lng2, lat2):
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def build_town_centroids(rows):
    """用「地址里明确带镇名的样本」算出各镇坐标重心 + 主城半径。"""
    acc = collections.defaultdict(list)
    for r in rows:
        addr = r["address"] or ""
        for t in TOWNS:
            if t in addr:
                acc[t].append((r["lng"], r["lat"]))
                break                       # 一条地址只归一个镇
    out = {}
    for t, pts in acc.items():
        out[t] = (sum(p[0] for p in pts) / len(pts),
                  sum(p[1] for p in pts) / len(pts),
                  len(pts))
    return out


def main_city_radius(rows, centroids):
    """
    主城（真州镇）的「势力半径」= 真州镇样本到其重心距离的 p95。

    ⚠️ 为什么需要这一步：仪征的城区是**连片**的 ——
    仪化生活区距主城重心仅 2.3km、胥浦 3.7km、新城镇 5.9km。
    如果单纯「取最近的镇中心」，市中心的星巴克/宝能环球汇会被判给新城镇
    （实测：新城镇从 34 家虚增到 157 家），因为镇政府本身就在城边。
    所以必须先承认「主城有个范围」，落在范围内的直接算主城。
    """
    key = centroids.get("真州镇")
    if not key:
        return 3.5, None
    clng, clat, _n = key
    ds = sorted(_haversine_km(r["lng"], r["lat"], clng, clat)
                for r in rows if "真州镇" in (r["address"] or ""))
    if not ds:
        return 3.5, None
    return ds[int(len(ds) * 0.95)], (clng, clat)


def resolve_town(addr, lng, lat, centroids, main_r, main_c):
    """
    三级判定，权威性从高到低：

    1. **地址里明确写了镇名** → 直接采信（603 家，37%）。这是唯一的一手信息。
    2. **落在主城半径内**（到真州镇重心 < p95≈3.3km）→ 归真州镇。
       没写镇名的地址绝大多数就在城里（本地人写地址会省略「真州镇」）。
    3. **剩下的才做最近邻**（离主城远、且某个镇中心明显更近）。

    ⚠️ 不要退回「纯最近邻」—— 实测会把 400+ 家主城的店分给新城镇/仪化，
    表面看是「更精确」，实际是错的（那些店地址里写的都是解放东路、江城路，
    是市中心主干道）。
    """
    addr = addr or ""
    for t in TOWNS:
        if t in addr:
            return t, "address"

    if main_c and _haversine_km(lng, lat, main_c[0], main_c[1]) <= main_r:
        return FALLBACK_TOWN, "maincity"

    best, bestd = None, 1e9
    for t, (clng, clat, _n) in centroids.items():
        if t == "真州镇":
            continue                        # 主城已在第 2 步处理过
        d = _haversine_km(lng, lat, clng, clat)
        if d < bestd:
            best, bestd = t, d
    if best is None:
        return FALLBACK_TOWN, "fallback"
    # 离得比主城半径还近 → 其实还是城里，别硬塞给远镇
    if main_c and bestd > _haversine_km(lng, lat, main_c[0], main_c[1]):
        return FALLBACK_TOWN, "maincity"
    return best, "geo"


# ---------------------------------------------------------------- 品类

# ⚠️ 映射表必须**覆盖 radar.db 里 keytag 的全部取值**（实测 154 种）。
# 覆盖不全的症状：一堆本来能细分的店被塞进「中餐」粗桶 ——
# 实测漏映射时「中餐」占了 56%（917/1642），补全后降到约 20%。
#
# 映射表：radar 的 keytag / cuisine  →  本项目 cuisine
CUISINE_MAP = {
    # ---- 菜系（高置信）----
    "淮扬菜": "淮扬菜", "江浙菜": "淮扬菜", "苏菜": "淮扬菜", "江浙小吃": "淮扬菜",
    "川菜": "川菜", "川味小吃": "川菜", "重庆小面": "川菜", "冒菜": "川菜",
    "江湖菜": "川菜", "酸菜鱼": "川菜", "麻辣烫": "川菜", "重庆火锅": "火锅",
    "湘菜": "湘菜", "徽菜": "徽菜", "粤菜": "粤菜", "东北菜": "东北菜",
    "鲁菜": "鲁菜", "江西菜": "江西菜", "河南菜": "河南菜", "湖北菜": "湖北菜",
    "云南菜": "云南菜", "贵州菜": "贵州菜", "新疆菜": "新疆菜", "北京菜": "北京菜",
    "福建小吃": "福建菜", "客家菜": "客家菜", "清真菜": "清真",
    "日本料理": "日料", "寿司": "日料", "韩国料理": "韩餐",
    # ---- 火锅 / 烧烤（高置信）----
    "火锅": "火锅", "小火锅": "火锅", "牛肉火锅": "火锅", "鱼火锅": "火锅",
    "自助火锅": "火锅", "炭火锅/铜锅": "火锅", "打边炉": "火锅",
    "火锅鸡": "火锅", "串串香": "火锅", "汤锅": "火锅",
    "烧烤": "烧烤", "烤串": "烧烤", "烤肉": "烧烤", "烧鸡": "烧烤",
    "烤鸭": "烧烤", "烤鱼": "烤鱼",
    # ---- 面食 / 小吃（高置信）----
    "面馆": "面食", "米粉": "面食", "牛肉面": "面食", "拉面": "面食",
    "牛肉粉": "面食", "米线": "面食", "锅盖面": "面食", "烩面": "面食",
    "粉丝汤": "面食", "小笼包": "面食", "汤包": "面食", "包子": "面食",
    "煎饼": "小吃", "烧饼": "小吃", "肉夹馍": "小吃", "凉菜": "小吃",
    "黄焖鸡米饭": "快餐", "鸡公煲": "快餐", "煲仔饭": "快餐", "轻食": "快餐",
    "特色小吃": "小吃", "砂锅": "小吃", "油炸": "小吃", "鸭脖": "卤味",
    "熟食": "卤味", "卤味": "卤味", "牛肉汤": "汤类", "羊肉汤": "汤类",
    "汤": "汤类", "猪肚鸡": "汤类", "粥": "粥", "早餐": "早餐",
    "炸鸡炸串": "快餐", "汉堡包": "快餐", "西式快餐": "快餐", "中式快餐": "快餐",
    "小龙虾": "小龙虾", "牛蛙": "小龙虾", "花甲": "小龙虾", "海鲜": "海鲜",
    "水产海鲜": "海鲜", "狗肉": "中餐", "猪肉": "中餐", "牛肉": "中餐",
    "鸡肉": "中餐", "肉类": "中餐", "素菜馆": "素菜", "自助餐": "自助餐",
    "夜宵": "小吃", "大排档": "大排档", "农家菜": "农家菜", "农家乐": "农家菜",
    "私房菜": "私房菜", "家常菜": "家常菜", "融合菜": "融合菜",
    "特色菜系": "中餐", "生态园": "农家菜",
    # ---- 饮品 / 甜点（高置信）----
    "咖啡": "咖啡", "茶座": "茶馆", "茶业": "茶馆", "清吧": "酒吧",
    "酒吧": "酒吧", "奶茶/茶饮": "奶茶", "饮品": "奶茶", "冰淇淋": "奶茶",
    "糖水": "奶茶", "烧仙草": "奶茶", "酸奶": "奶茶",
    "蛋糕店": "烘焙", "面包": "烘焙", "西式糕点": "烘焙", "糕点/烘焙": "烘焙",
    "甜品店": "烘焙", "中式糕点": "烘焙", "零食": "小吃",
    # ---- 西餐 ----
    "西餐": "西餐", "牛排": "西餐",
    # ---- cuisine 粗桶（大部分是低置信，但值得保留原样）----
    "四川菜(川菜)": "川菜",
    "火锅店": "火锅",
    "咖啡厅": "咖啡",
    "茶艺馆": "茶馆",
    "糕饼店": "烘焙",
    "冷饮店": "奶茶",
}

# 明确是「粗桶」的结果 → 标低置信度（产品上会折叠或提示「待店主补充」）
LOW_CONFIDENCE = {"中餐", "快餐"}

# ⚠️ 采集时混进来的**非餐饮 POI**，必须剔除。
# 这些店名里没有餐饮信息，留着会让「附近新菜」列表出现棋牌室、洗衣店。
# 判据：keytag 命中这些词，且 cuisine 也是「餐饮相关」这类兜底桶。
NON_FOOD_TAGS = {
    "美容", "宠物服务", "洗衣店", "旅馆", "住宿服务", "棋牌室", "量贩式KTV",
    "购物服务", "综合超市", "住宅区", "花卉", "动漫周边", "日杂店", "水果店",
    "速冻食品", "食品公司", "特色商业街", "商务酒店", "四星级酒店",
    "专卖店", "土特产专卖店",
}

# 「今天发过菜」的店占比。
#
# ⚠️ 别把它调大（血泪）。后端 `ShopService.isRecentlyChanged` 会把
# 「24h 内发过菜的店」**强制保底进客户端首屏**（为了让商家发完菜
# 去客户端刷新就能看到）。占比一提，保底名额就被吃掉，
# `ClientController.BOOTSTRAP_SHOP_LIMIT` 的限量随之失效
# —— 首屏响应体会从 600KB 涨回 MB 级，正是限量要治的病。
#
# 8% 的依据：1620 家 × 8% ≈ 130 家，落在 200 家的首屏预算内，
# 且「一个城市一天有百来家店上新」本身是合理的。
TODAY_PUBLISH_RATIO = 0.08


def is_food_poi(keytag, cuisine, name):
    """
    过滤非餐饮 POI。

    radar.db 是**按坐标+品类词**采集的，商户本身就混进了非餐饮
    （实测有棋牌室、洗衣店、旅馆、超市、花卉）。
    不过滤的话客户端会刷出「XX棋牌室 今日新菜」这种荒谬内容。

    ⚠️ 判据必须是「**命中非餐饮词**」，不能是「没命中餐饮词」——
    后者会把「华新老鹅馆」「南门羊肉汤」「宴福楼」这类正经餐馆误杀
    （它们 keytag 是「餐饮相关」兜底桶，但店名里没有「餐/菜/饭」等字）。
    实测两版差距：误判版剔了 365 家，其中 300+ 是真餐馆。
    """
    kt = (keytag or "").strip()
    cu = (cuisine or "").strip()
    blob = kt + "|" + cu
    for bad in NON_FOOD_TAGS:
        if bad in blob:
            return False
    return True


def map_cuisine(keytag, cuisine):
    """
    返回 (本项目 cuisine, 置信度)。
    置信度：high = 从 keytag 明确映射到具体品类；low = 只能落到粗桶「中餐」。
    """
    kt = (keytag or "").strip()
    if kt and kt in CUISINE_MAP:
        got = CUISINE_MAP[kt]
        return got, ("low" if got in LOW_CONFIDENCE else "high")
    cu = (cuisine or "").strip()
    if cu and cu in CUISINE_MAP:
        got = CUISINE_MAP[cu]
        return got, ("low" if got in LOW_CONFIDENCE else "high")
    # keytag 是复合串（如「餐饮服务,中餐厅,中餐厅」）→ 拆开再试一次
    for part in re.split(r"[,，|]", kt):
        part = part.strip()
        if part in CUISINE_MAP:
            got = CUISINE_MAP[part]
            return got, ("low" if got in LOW_CONFIDENCE else "high")
    return "中餐", "low"


# ---------------------------------------------------------------- 文案

def norm_hours(raw):
    """高德 opentime 形如 '09:00-13:30 16:30-20:30' → 本项目 '周一至周日 09:00-13:30,16:30-20:30'"""
    if not raw:
        return "周一至周日 10:00-21:00"
    s = raw.strip().replace("；", " ").replace(";", " ")
    s = re.sub(r"\s+", ",", s.strip())
    s = s.strip(",")
    if not s:
        return "周一至周日 10:00-21:00"
    if s.startswith("周一"):
        return s
    return "周一至周日 " + s


def make_intro(name, cuisine, keytag, rating, cost, cuisine_conf):
    """intro 保持『品类 + 评分 + 人均』的一致格式（前端多处按此展示）。"""
    parts = []
    label = (keytag or "").strip() or cuisine
    parts.append(label)
    try:
        if rating:
            parts.append("地图评分 %.1f" % float(rating))
    except (TypeError, ValueError):
        pass
    try:
        if cost:
            parts.append("人均约 %d 元" % int(float(cost)))
    except (TypeError, ValueError):
        pass
    text = "，".join(parts) + "。"
    if cuisine_conf == "low":
        text += "（品类待店主认领后补充）"
    return text


# ---------------------------------------------------------------- 权重/互动

def make_weight(rating, pin_rank):
    """
    weight 要**刻意并列**（并列才会走到距离/时间衰减两级排序）。
    用评分分档而不是连续值，天然产生并列。
    前 6 家 pinned 置顶，权重拉高一档。
    """
    try:
        r = float(rating or 0)
    except (TypeError, ValueError):
        r = 0.0
    if pin_rank is not None and pin_rank < 6:
        return 90 - pin_rank * 5          # 90/85/80/75/70/65
    if r >= 4.7:
        return 70
    if r >= 4.5:
        return 60
    if r >= 4.3:
        return 50
    if r >= 4.0:
        return 40
    return 30


def stratify(rows, n):
    """
    分层抽样：按「镇 × 品类」分层，每层按比例取，层内按评分倒序。

    这样保证：
    - 每个镇都有店（客户端按距离刷，附近总有内容）
    - 每个品类都有店（筛选器点什么都刷得出东西）
    """
    if n >= len(rows):
        return rows
    buckets = collections.defaultdict(list)
    for x in rows:
        buckets[(x["town"], x["cuisine"])].append(x)
    total = len(rows)
    quota = {}
    for k, v in buckets.items():
        # 至少给 1，再按比例分配
        quota[k] = max(1, int(round(n * len(v) / total)))
    # 配额可能超出 n，按「超额比例」削减
    while sum(quota.values()) > n:
        k = max(quota, key=lambda k: quota[k] - 1)
        if quota[k] <= 1:
            break
        quota[k] -= 1
    out = []
    for k, v in buckets.items():
        out.extend(v[: quota[k]])
    # 还不够就按评分补（picked 本身已按 rating desc 排序）
    if len(out) < n:
        got = {id(x) for x in out}
        for x in rows:
            if len(out) >= n:
                break
            if id(x) not in got:
                out.append(x)
    return out[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="限量（调试）")
    ap.add_argument("--emit-java", action="store_true", help="产出 Java 片段")
    ap.add_argument("--top", type=int, default=0,
                    help="取前 N 家（分层抽样）；0=全量（默认）")
    args = ap.parse_args()

    con = sqlite3.connect(RADAR_DB)
    con.row_factory = sqlite3.Row
    rows = con.execute("""
        select poi_id, name, cuisine, keytag, address, lng, lat, tel,
               business_area, rating, cost, opentime, photos, photo_count
        from shops
        where gone=0 and tel is not null and tel<>''
          and rating is not null and rating<>''
        order by cast(rating as real) desc, name
    """).fetchall()

    if not rows:
        print("没有匹配的店铺", file=sys.stderr)
        return 1

    centroids = build_town_centroids(rows)
    main_r, main_c = main_city_radius(rows, centroids)
    print("=== 镇级坐标重心（用于最近邻）===")
    for t, (lng, lat, n) in sorted(centroids.items(), key=lambda x: -x[1][2]):
        print("  %-10s n=%-4d (%.4f, %.4f)" % (t, n, lng, lat))
    print("主城（真州镇）势力半径 p95 = %.2f km" % main_r)

    picked = []
    town_stat = collections.Counter()
    geo_stat = collections.Counter()
    cui_stat = collections.Counter()
    conf_stat = collections.Counter()
    dropped = []

    for i, r in enumerate(rows):
        # 先剔非餐饮 POI（棋牌室/洗衣店/旅馆/超市…）
        if not is_food_poi(r["keytag"], r["cuisine"], r["name"]):
            dropped.append(r["name"])
            continue
        town, how = resolve_town(r["address"], r["lng"], r["lat"],
                                 centroids, main_r, main_c)
        cui, conf = map_cuisine(r["keytag"], r["cuisine"])
        town_stat[town] += 1
        geo_stat[how] += 1
        cui_stat[cui] += 1
        conf_stat[conf] += 1
        picked.append({
            "poi_id": r["poi_id"],
            "name": r["name"],
            "town": town,
            "town_src": how,
            "cuisine": cui,
            "cuisine_conf": conf,
            "amap_cuisine": (r["keytag"] or r["cuisine"] or "").strip(),
            "address": r["address"] or "",
            "lng": r["lng"], "lat": r["lat"],
            "tel": r["tel"],
            "rating": r["rating"], "cost": r["cost"],
            "hours": norm_hours(r["opentime"]),
            "photos": r["photos"] or "",
            "photo_count": r["photo_count"] or 0,
        })

    print()
    print("=== 剔除的非餐饮 POI（%d 家）===" % len(dropped))
    for n in dropped[:25]:
        print("  " + n)
    if len(dropped) > 25:
        print("  … 另有 %d 家" % (len(dropped) - 25))
    print()
    print("=== 镇级归类依据 ===")
    for k, v in geo_stat.most_common():
        print("  %-10s %d" % (k, v))
    print()
    print("=== 品类分布（映射后）top20 ===")
    for k, v in cui_stat.most_common(20):
        print("  %-10s %d" % (k, v))
    print()
    print("=== 置信度 ===")
    for k, v in conf_stat.most_common():
        print("  %-6s %d (%.1f%%)" % (k, v, 100.0 * v / len(picked)))
    print()
    print("=== 镇级分布 ===")
    for k, v in town_stat.most_common():
        print("  %-10s %d" % (k, v))

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, "_radar_seed.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(picked, f, ensure_ascii=False, indent=1)
    print()
    print("已写出 %s（%d 家）" % (out, len(picked)))

    # ---- 生成 Java 用的资源文件（数据外置，别塞进 DataSeeder.java）----
    emit_resource(picked, args)
    return 0


# 这几个 id 是被测试和演示**硬依赖**的，扩数据时必须原样保留：
# - s_001 / 13800138000 → 商家端登录、full_flow、merchant_e2e、auth、login_security
# - s_002 ~ s_022 是原有演示数据，平台端图表/排序演示依赖其分布
PINNED = {
    "s_001": ("永安水煮活鱼", "13800138000"),
    "s_002": ("钱亮亮小火锅", None),
    "s_003": ("望江渔村", None),
    "s_004": ("迎春酒楼", None),
    "s_005": ("真湘园(迎江东路店)", None),
    "s_006": ("鼓蘭庭院", None),
    "s_007": ("市井川菜(东亚御景湾店)", None),
    "s_008": ("重庆江湖菜(化纤大排档店)", None),
    "s_009": ("真香园·现炒淮扬菜(博览家店)", None),
    "s_010": ("醉真州私房菜馆", None),
    "s_011": ("川平饭店", None),
    "s_012": ("大楼烧烤(二店)", None),
}


def emit_resource(picked, args):
    """
    产出 `server/src/main/resources/seed/shops.json`。

    ⚠️ 为什么数据外置而不是写进 DataSeeder.java：
    1620 家店 + 1.1 万道菜，写成 Java 字面量会让 DataSeeder.java 涨到 1.5 万行 ——
    编辑器打不开、编译变慢、git diff 每次都是万行级变更。
    外置成 JSON 后 Java 侧只留「读文件 + 落库」的循环，数据怎么涨都不影响代码。
    """
    import dish_templates as dt

    rng = random.Random(20260918)     # 固定种子 → 每次生成结果一致（测试要可复现）

    # 先把 PINNED 的店按原名原电话覆盖（扩数据不能动它们）
    by_name = {x["name"]: x for x in picked}
    ordered = []
    for sid, (nm, tel) in PINNED.items():
        x = by_name.get(nm)
        if x:
            x["id"] = sid
            if tel:
                x["tel"] = tel
            x["pinned"] = True
            ordered.append(x)
    pinned_names = {v[0] for v in PINNED.values()}

    top = args.top if args.top else len(picked)
    rest = [x for x in picked if x["name"] not in pinned_names]

    if top < len(picked):
        # ⚠️ 限量时必须**分层抽样**，不能直接取评分 top N。
        # 实测「按评分取前 400」的结果：真州镇占 69%、中餐占 48% ——
        # 因为高分店集中在主城、且主城多是大酒楼。
        # 分层后各镇各品类都有代表，演示的多样性才出来。
        rest = stratify(rest, top - len(ordered))
    else:
        rest = rest[: max(0, top - len(ordered))]

    used_ids = set(PINNED.keys())
    n = len(ordered)
    for x in rest:
        n += 1
        sid = "s_%03d" % n
        while sid in used_ids:
            n += 1
            sid = "s_%03d" % n
        x["id"] = sid
        used_ids.add(sid)
        x["pinned"] = False
        ordered.append(x)

    # 生成菜品：每店最多 7 道（对应 7 天，遵守「每 24h 1 条」冻结规则）
    #
    # ⚠️⚠️ 「每家店第一道菜都是今天发的」是个**必须在生成期避免的陷阱**（血泪）。
    #
    # 最初的写法是 `"daysAgo": i`（i 从 0 起），于是 1620 家店**全部**有
    # 「daysAgo=0」的菜 —— 即全城同一天集体发菜，现实里不可能。
    # 单看种子数据看不出来，但它会连锁毁掉两处：
    #
    #   ① 后端 `ShopService.isRecentlyChanged` 的「刚发过菜 → 保底进首屏」
    #      会把**全部 1620 家**都判定为「刚发过」，限量直接失效，
    #      bootstrap 又涨回 5MB（正是限量要解决的那个问题）。
    #   ② 前端 `buildFeed` 的排序在此之上被孤儿数据打乱（见 ClientController）。
    #
    # 正解：**按店错开发布日**。用一个固定的「今天该发菜」的店占比
    # （TODAY_PUBLISH_RATIO），其余店的菜从「昨天 / 前天…」往前排。
    # 这样既保住「每天有新菜可看」的演示效果，又让「今天发过菜的店」
    # 是一个合理的少数派（真实场景里，一个城市一天也就一小部分店上新）。
    shops_out = []
    dish_id = 0
    for idx, x in enumerate(ordered):
        shop = {
            "id": x["id"], "name": x["name"], "cuisine": x["cuisine"],
            "cost": x.get("cost"), "rating": x.get("rating"),
        }
        # 按评分定内容量：评分高的店内容多一点（更真实）
        try:
            r = float(x.get("rating") or 0)
        except (TypeError, ValueError):
            r = 0.0
        cnt = 7 if r >= 4.5 else (5 if r >= 4.0 else 3)
        dishes = dt.gen_dishes(shop, cnt, rng)
        # 这家店「最新一道菜」是几天前发的。
        # 用 rng 抽，保证可复现（种子固定）；同时让少数店落在今天。
        offset = 0 if rng.random() < TODAY_PUBLISH_RATIO else rng.randint(1, 6)
        dish_list = []
        for i, d in enumerate(dishes):
            dish_id += 1
            dish_list.append({
                "id": "d_%04d" % dish_id,
                "name": d["name"], "desc": d["desc"],
                "type": "video" if (i == 0 and r >= 4.5) else "image",
                "price": d["price"], "taste": d["taste"],
                # 第 i 天的内容 = 最新那条再往前 i 天
                "daysAgo": offset + i,
            })
        shops_out.append({
            "id": x["id"], "name": x["name"], "cuisine": x["cuisine"],
            "cuisineConfidence": x["cuisine_conf"],
            "amapCuisine": x["amap_cuisine"],
            "city": "仪征市", "district": x["town"],
            "address": x["address"], "phone": x["tel"],
            "hours": x["hours"],
            "intro": make_intro(x["name"], x["cuisine"], None,
                                x.get("rating"), x.get("cost"), x["cuisine_conf"]),
            "lat": x["lat"], "lng": x["lng"],
            "weight": make_weight(x.get("rating"), None),
            "pinned": x["pinned"],
            "intervalHours": 24, "dailyLimit": 1,
            "reviewDaysAgo": 5,
            "dishes": dish_list,
        })

    res_dir = os.path.join(ROOT, "server", "src", "main", "resources", "seed")
    os.makedirs(res_dir, exist_ok=True)
    out_path = os.path.join(res_dir, "shops.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(shops_out, f, ensure_ascii=False, separators=(",", ":"))

    total_dishes = sum(len(s["dishes"]) for s in shops_out)
    size_kb = os.path.getsize(out_path) / 1024.0
    print()
    print("=== 资源文件 ===")
    print("  %s" % out_path)
    print("  店铺 %d 家 · 菜品 %d 道 · %.0f KB" % (len(shops_out), total_dishes, size_kb))
    print("  其中 pinned（保测试兼容）: %d 家" % sum(1 for s in shops_out if s["pinned"]))
    c = collections.Counter(s["district"] for s in shops_out)
    print("  镇级分布:", dict(c.most_common(6)))
    c2 = collections.Counter(s["cuisine"] for s in shops_out)
    print("  品类 top8:", dict(c2.most_common(8)))
    print("  low 置信度: %d 家 (%.1f%%)" % (
        sum(1 for s in shops_out if s["cuisineConfidence"] == "low"),
        100.0 * sum(1 for s in shops_out if s["cuisineConfidence"] == "low") / len(shops_out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
