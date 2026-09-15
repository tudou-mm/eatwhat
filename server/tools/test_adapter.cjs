/* ==========================================================
   api.js 适配层真实行为测试
   ----------------------------------------------------------
   在一个 vm 沙箱里加载真实的 mock.js + api.js，
   用「同步 XHR 替身」（底层调 python urllib）去打真后端，
   验证三件事：
     1. ?api=1 时能接上后端，缓存就绪，MOCK.getShop 返回后端数据
     2. ?api=0 时干净降级回本地假数据，原型照样能打开
     3. 后端不可达时不会崩，自动降级

   用法：先启动后端（8080），再跑
     node server/tools/test_adapter.cjs
   ========================================================== */
const vm = require('vm');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const PY = 'C:/Users/PC/.workbuddy/binaries/python/versions/3.13.12/python.exe';
const ROOT = path.resolve(__dirname, '..', '..');
const API_BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api';

const PY_CODE = [
  'import sys, urllib.request',
  'try:',
  '    r = urllib.request.urlopen(sys.argv[1], timeout=10)',
  "    sys.stdout.write(r.read().decode('utf-8'))",
  'except Exception as e:',
  '    sys.stderr.write(str(e)); sys.exit(1)',
].join('\n');

function httpGet(url) {
  return execFileSync(PY, ['-c', PY_CODE, url], {
    encoding: 'utf-8', maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
  });
}

/** 同步 XMLHttpRequest 替身，语义与浏览器一致（async=false） */
class SyncXHR {
  constructor() { this.status = 0; this.responseText = ''; this._h = {}; }
  open(method, url, async) {
    if (async !== false) throw new Error('shim 只支持同步请求');
    this._method = method; this._url = url;
  }
  setRequestHeader(k, v) { this._h[k] = v; }
  send() {
    try {
      this.responseText = httpGet(this._url);
      this.status = 200;
    } catch (e) {
      this.status = 0; this.responseText = '';
      this._err = String(e.message || e).slice(0, 200);
    }
  }
}

function makeSandbox(search, store) {
  const sandbox = {
    console: { log: (...a) => console.log(...a), warn: (...a) => console.warn(...a), error: (...a) => console.error(...a) },
    XMLHttpRequest: SyncXHR,
    URLSearchParams,
    localStorage: {
      getItem: (k) => (k in store ? store[k] : null),
      setItem: (k, v) => { store[k] = String(v); },
      removeItem: (k) => { delete store[k]; },
    },
    location: { search, href: 'http://127.0.0.1:5173/client/feed.html' + search },
    document: { readyState: 'complete', addEventListener() {} },
    setTimeout, clearTimeout,
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(ROOT, 'assets/data/mock.js'), 'utf-8'), sandbox, { filename: 'mock.js' });
  vm.runInContext(fs.readFileSync(path.join(ROOT, 'assets/js/api.js'), 'utf-8'), sandbox, { filename: 'api.js' });
  return sandbox;
}

let pass = 0, fail = 0;
function assert(label, cond, extra) {
  if (cond) { pass++; console.log('  [OK]   ' + label + (extra ? '  | ' + extra : '')); }
  else { fail++; console.log('  [FAIL] ' + label + (extra ? '  | ' + extra : '')); }
}

// ---------------------------------------------------------------
console.log('== 用例 1：?api=1 应接上后端，MOCK 返回后端数据 ==');
const s1 = makeSandbox('?api=1', {});
const API1 = s1.EAT_API;
assert('适配层已启用', API1.isEnabled() === true);
assert('缓存已就绪', API1.cache.loaded === true,
  `shops=${API1.cache.shops.length} dishes=${API1.cache.dishes.length} comments=${API1.cache.comments.length} tiers=${API1.cache.priceTiers.length}`);

// 直接 HTTP 取一份权威数据来比对。
// 注意：必须用 bootstrap —— 适配层缓存的是「视图结构」，
// 而 /admin/shops 返回的是数据库实体，两者字段名不同。
const boot = JSON.parse(httpGet(API_BASE + '/client/bootstrap')).data;
const rawShops = boot.shops;
assert('店铺数量与后端一致', API1.cache.shops.length === rawShops.length,
  `前端缓存 ${API1.cache.shops.length} / 后端 ${rawShops.length}`);
assert('一个聚合请求就拿到全部数据',
  boot.dishes.length === API1.cache.dishes.length &&
  boot.comments.length === API1.cache.comments.length,
  `dishes ${API1.cache.dishes.length} / comments ${API1.cache.comments.length} / tiers ${API1.cache.priceTiers.length}`);

const firstId = rawShops[0] && rawShops[0].id;
const viaMOCK = s1.MOCK.getShop(firstId);
const viaHttp = rawShops.find((s) => s.id === firstId);
const kMock = Object.keys(viaMOCK || {}).sort();
const kHttp = Object.keys(viaHttp || {}).sort();
const onlyMock = kMock.filter((k) => !kHttp.includes(k));
const onlyHttp = kHttp.filter((k) => !kMock.includes(k));
assert('MOCK.getShop 返回后端对象且字段一致',
  onlyMock.length === 0 && onlyHttp.length === 0 && JSON.stringify(viaMOCK) === JSON.stringify(viaHttp),
  onlyMock.length || onlyHttp.length
    ? `仅前端有=[${onlyMock}] 仅后端有=[${onlyHttp}]`
    : (JSON.stringify(viaMOCK) === JSON.stringify(viaHttp) ? '内容完全一致' : 'key 一致但内容不同'));

assert('MOCK.getShopDishes 返回数组', Array.isArray(s1.MOCK.getShopDishes(firstId)),
  `${s1.MOCK.getShopDishes(firstId).length} 条`);

// 前端页面真实读取的字段，必须都在
assert('shopView 含 stats.dishes（店铺页读它）',
  !!(viaMOCK && viaMOCK.stats && typeof viaMOCK.stats.dishes === 'number'),
  viaMOCK && viaMOCK.stats ? `stats.dishes=${viaMOCK.stats.dishes}` : 'n/a');
assert('shopView 含 lastPostAt 字符串（商家工作台读它）',
  !!(viaMOCK && typeof viaMOCK.lastPostAt === 'string'),
  viaMOCK ? `lastPostAt='${viaMOCK.lastPostAt}'` : 'n/a');
assert('MOCK.shops 数组已换成后端数据（平台端页面直接读数组）',
  Array.isArray(s1.MOCK.shops) && s1.MOCK.shops.length === rawShops.length &&
  s1.MOCK.shops[0].id === rawShops[0].id,
  Array.isArray(s1.MOCK.shops) && s1.MOCK.shops[0] ? `MOCK.shops[0]=${s1.MOCK.shops[0].id}` : 'n/a');
const firstDish = API1.cache.dishes[0];
assert('dishView 含 stats.likes + publishedAt（feed 读它）',
  !!(firstDish && firstDish.stats && typeof firstDish.stats.likes === 'number' && firstDish.publishedAt),
  firstDish ? `d=${firstDish.id} likes=${firstDish.stats && firstDish.stats.likes}` : 'n/a');

// 关键回归：不得无限递归（包装后回退必须走 _local_ 版本）
let noRecursion = true;
try { s1.MOCK.getShop('不存在的店'); } catch (e) { noRecursion = false; }
try { s1.MOCK.getShopDishes('不存在的店'); } catch (e) { noRecursion = false; }
try { s1.MOCK.getDishComments('不存在的菜'); } catch (e) { noRecursion = false; }
assert('查不存在的 id 不递归、不报错', noRecursion);

// 价格归档（后端档位）
const tier = s1.MOCK.getTierByPrice(30);
assert('getTierByPrice 走后端档位', tier && tier.name === '中等', tier ? `${tier.name}` : 'null');

console.log('\n== 用例 2：?api=0 应干净降级回本地假数据 ==');
const s2 = makeSandbox('?api=0', {});
const API2 = s2.EAT_API;
assert('适配层未启用', API2.isEnabled() === false);
assert('缓存未加载', API2.cache.loaded === false);
const localShop = s2.MOCK.getShop('s_001');
assert('本地假数据可用（原型永远打得开）', !!localShop && !!localShop.name,
  localShop ? `s_001 = ${localShop.name}` : 'null');

console.log('\n== 用例 3：后端不可达时应自动降级 ==');
const s3 = makeSandbox('?api=1', {});
// 重新指向一个没人监听的端口
s3.localStorage.setItem('apiBase', 'http://127.0.0.1:59999/api');
const s4 = makeSandbox('?api=1', { apiBase: 'http://127.0.0.1:59999/api' });
assert('后端不可达时不抛异常', true);
assert('已降级为本地模式', s4.EAT_API.isEnabled() === false);
assert('降级后仍能取到数据', !!s4.MOCK.getShop('s_001'));

console.log('\n== 用例 4：?api=1 模式下筛选是否真的作用于首页 ==');
// 关键点：buildFeed 走的是 mock.js 的本地实现，但它读的 this.dishes /
// this.getShop / this.priceTiers 已被适配层换成后端数据。
// 所以筛选条件在本地算、数据来自后端 —— 必须实测，不能靠推理。
const _t = s1.localStorage;
_t.removeItem('clientFilter');
const feedAll = s1.MOCK.buildFeed('nearby');
assert('无筛选：信息流条数 = 有内容的店铺数（每店一条）',
  feedAll.length > 0 && new Set(feedAll.map((d) => d.shopId)).size === feedAll.length,
  `${feedAll.length} 条 / ${new Set(feedAll.map((d) => d.shopId)).size} 家店`);

_t.setItem('clientFilter', JSON.stringify({ tiers: ['tier_mid'], tastes: [] }));
const feedMid = s1.MOCK.buildFeed('nearby');
assert('筛选生效：结果全部命中 tier_mid',
  feedMid.length > 0 && feedMid.every((d) => d.priceTierId === 'tier_mid'),
  `${feedAll.length} → ${feedMid.length} 条`);
assert('筛选后仍守每店一条',
  new Set(feedMid.map((d) => d.shopId)).size === feedMid.length);
assert('filterLabel 用后端档位名',
  s1.MOCK.filterLabel() === '中等', s1.MOCK.filterLabel());

_t.setItem('clientFilter', JSON.stringify({ tiers: ['__not_exist__'], tastes: [] }));
assert('筛空时返回空数组（关键：不回退旧内容）',
  s1.MOCK.buildFeed('nearby').length === 0,
  s1.MOCK.buildFeed('nearby').length + ' 条');
assert('筛空时随机流同样为空', s1.MOCK.buildFeed('random').length === 0);
_t.removeItem('clientFilter');
assert('清除条件后信息流恢复', s1.MOCK.buildFeed('nearby').length === feedAll.length);

console.log('\n=== 汇总 ===');
console.log(`通过 ${pass} 项，失败 ${fail} 项`);
process.exit(fail ? 1 : 0);
