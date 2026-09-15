/* ==========================================================
   筛选逻辑测试
   ----------------------------------------------------------
   直接跑 assets/data/mock.js 的真实代码（vm 沙箱 + localStorage 替身），
   验证「筛选后首页信息流跟着变」这条链路的边界条件。

   重点覆盖三处最容易写错的地方：
   1. 筛选态下**不能**做「内容不足回退旧内容」——回退会让筛选失效
   2. 筛选后仍要守住「每店一条」
   3. 取的是「符合条件的最新一条」，而不是「最新的那条如果不符合就整店剔除」

   跑法：node test_filter.cjs
   ========================================================== */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const MOCK_PATH = path.join(__dirname, '..', '..', 'assets', 'data', 'mock.js');
const SRC = fs.readFileSync(MOCK_PATH, 'utf8');

let pass = 0, fail = 0;

function assert(name, ok, extra) {
  if (ok) { pass++; console.log('  \u2713 ' + name + (extra ? '  \u2192 ' + extra : '')); }
  else { fail++; console.log('  \u2717 ' + name + (extra ? '  \u2192 ' + extra : '')); }
}

function section(t) { console.log('\n' + t); }

/* ---------- localStorage 替身 ---------- */
function makeStorage(init) {
  const store = Object.assign({}, init || {});
  return {
    getItem: k => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: k => { delete store[k]; },
    dump: () => store
  };
}

/* ---------- 在沙箱里加载真实 mock.js ---------- */
function load(storageInit) {
  const storage = makeStorage(storageInit);
  const sandbox = { localStorage: storage, console: console };
  vm.createContext(sandbox);
  // mock.js 结尾会执行 window.MOCK = MOCK，所以得先给它一个 window
  vm.runInContext('globalThis.window = globalThis;', sandbox);
  vm.runInContext(SRC, sandbox);
  // 把 storage 挂回去，方便断言「是否真的回写了」
  sandbox.MOCK.__storage = storage;
  return sandbox.MOCK;
}

/* ========================================================== */

const M = load();

const normal = M.dishes.filter(d => d.status === 'normal');
const shopCount = new Set(normal.map(d => d.shopId)).size;

section('\u3010\u57fa\u7840\u3011\u5047\u6570\u636e\u6982\u89c8');
console.log('  菜品 ' + M.dishes.length + ' 道（上架 ' + normal.length + ' 道）· 店铺 ' +
  M.shops.length + ' 家 · 有内容的店 ' + shopCount + ' 家');
console.log('  价格档：' + M.priceTiers.map(t => t.id + '(' + t.name + ')').join(' '));
const tierCount = {};
normal.forEach(d => { tierCount[d.priceTierId] = (tierCount[d.priceTierId] || 0) + 1; });
console.log('  各档菜品数：' + JSON.stringify(tierCount));
const tasteCount = {};
normal.forEach(d => (d.tasteTags || []).forEach(t => { tasteCount[t] = (tasteCount[t] || 0) + 1; }));
console.log('  各口味菜品数：' + JSON.stringify(tasteCount));

/* ---------- 1. 无筛选 ---------- */
section('\u3010\u65e0\u7b5b\u9009\u3011\u57fa\u7840\u884c\u4e3a');
M.clearFilter();
const base = M.buildFeed('nearby');
assert('筛选未激活', M.filterActive() === false);
assert('每店只露一条', new Set(base.map(d => d.shopId)).size === base.length,
  base.length + ' 条 / ' + new Set(base.map(d => d.shopId)).size + ' 家店');
assert('条数等于有内容的店铺数', base.length === shopCount, base.length + ' vs ' + shopCount);
assert('全部为上架内容', base.every(d => d.status === 'normal'));
assert('filterLabel 为空串', M.filterLabel() === '', JSON.stringify(M.filterLabel()));

/* ---------- 2. 按价格档筛选 ---------- */
section('\u3010\u5355\u7ef4\u3011\u4ef7\u683c\u6863\u7b5b\u9009');
const midTier = Object.keys(tierCount).find(id => id === 'tier_mid') ? 'tier_mid'
  : M.priceTiers[0].id;
M.setFilter({ tiers: [midTier], tastes: [] });
const byTier = M.buildFeed('nearby');
assert('筛选已激活', M.filterActive() === true);
assert('结果全部命中该档位', byTier.length > 0 && byTier.every(d => d.priceTierId === midTier),
  byTier.length + ' 条');
assert('筛选后仍每店一条', new Set(byTier.map(d => d.shopId)).size === byTier.length);
assert('条件已落 localStorage', M.getFilter().tiers[0] === midTier);

/* ---------- 3. 按口味筛选 ---------- */
section('\u3010\u5355\u7ef4\u3011\u53e3\u5473\u7b5b\u9009');
const taste = Object.keys(tasteCount)[0];
M.setFilter({ tiers: [], tastes: [taste] });
const byTaste = M.buildFeed('nearby');
assert('结果全部命中该口味',
  byTaste.length > 0 && byTaste.every(d => (d.tasteTags || []).includes(taste)),
  taste + ' → ' + byTaste.length + ' 条');

/* ---------- 4. 跨维度 AND ---------- */
section('\u3010\u8de8\u7ef4\u5ea6\u3011AND \u8bed\u4e49');
M.setFilter({ tiers: [midTier], tastes: [taste] });
const both = M.buildFeed('nearby');
assert('两个维度都要满足',
  both.every(d => d.priceTierId === midTier && (d.tasteTags || []).includes(taste)),
  both.length + ' 条');
assert('filterLabel 拼接正确', M.filterLabel() === (M.priceTiers.find(t => t.id === midTier).name + ' · ' + taste),
  M.filterLabel());

/* ---------- 5. 无结果时不能回退（关键） ---------- */
section('\u3010\u5173\u952e\u3011\u7b5b\u7a7a\u65f6\u4e0d\u80fd\u56de\u9000\u65e7\u5185\u5bb9');

// 构造筛空必须用「真实存在但组合必然为空」的条件。
// 不能用不存在的档位 id —— 那种失效条件会被 sanitizeFilter 自动剔除，
// 压根构不成筛选态（该行为另有专门用例覆盖）。
const emptyCombo = (function () {
  for (const t of M.priceTiers.map(x => x.id)) {
    for (const s of M.tasteTags) {
      const f = { tiers: [t], tastes: [s] };
      const hit = M.dishes.filter(d => d.status === 'normal' && M.matchFilter(d, f)).length;
      if (hit === 0) return f;
    }
  }
  return null;
})();
assert('数据里存在组合为空的筛选项（本组测试的前提）', !!emptyCombo,
  emptyCombo ? JSON.stringify(emptyCombo) : '没有空组合');

M.setFilter(emptyCombo || { tiers: ['tier_mid'], tastes: ['日料'] });
const empty = M.buildFeed('nearby');
assert('无匹配时返回空数组（没有偷偷补回旧内容）', empty.length === 0, empty.length + ' 条');
assert('跑 random tab 同样为空', M.buildFeed('random').length === 0);
assert('筛选激活时 filterActive 为真', M.filterActive() === true);

// 对照：只有 2 家店有内容时，条数就是 2。
// 不能凭空生成内容，也不能把同一家店的第二条塞进来充数。
//
// 注：mock 里的"不足 3 条补位旧内容"分支在当前设计下不可达 ——
// 首页是「每店一条」，这个集合本来就覆盖了所有有内容的店，
// 没有第三家店可补。该分支属于防御性代码，留待将来
// 给常态加时间窗（只推 24h 内新菜）时兜底。
const M2 = load();
M2.dishes = M2.dishes.filter(d => ['s_001', 's_002'].includes(d.shopId));
M2.clearFilter();
const thin = M2.buildFeed('nearby');
assert('店铺少时条数就等于店铺数，不重复填充', thin.length === 2, thin.length + ' 条');
assert('仍守住每店一条', new Set(thin.map(d => d.shopId)).size === thin.length);

/* ---------- 6. 取「符合条件的最新一条」（关键） ---------- */
section('\u3010\u5173\u952e\u3011\u53d6\u7b26\u5408\u6761\u4ef6\u7684\u6700\u65b0\u4e00\u6761');
const M3 = load();
M3.setFilter({ tiers: [midTier], tastes: [] });
const hostShop = M3.dishes.find(d => d.priceTierId === midTier).shopId;
const before = M3.buildFeed('nearby').find(d => d.shopId === hostShop);
// 给这家店插一条"更新但不符合筛选"的菜
M3.dishes.push({
  id: 'test_pushed_1', shopId: hostShop, shopName: 'X', name: '测试·超贵新菜',
  desc: '', type: 'image', media: ['x'], cover: null, price: 999,
  priceTierId: 'tier_ultra', realTag: null, tasteTags: [],
  publishedAt: '2099-01-01 12:00', status: 'normal',
  stats: { views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 }
});
const after = M3.buildFeed('nearby').find(d => d.shopId === hostShop);
assert('店没有被整店剔除', !!after, after ? after.name : 'null');
assert('取的是符合条件的那条，不是更新但不匹配的那条',
  after && after.id === before.id && after.id !== 'test_pushed_1',
  'before=' + (before ? before.id : 'null') + ' after=' + (after ? after.id : 'null'));

// 反过来：插一条"更新的且符合筛选"的菜，应该换成它
M3.dishes.push({
  id: 'test_pushed_2', shopId: hostShop, shopName: 'X', name: '测试·新麻辣',
  desc: '', type: 'image', media: ['x'], cover: null, price: 30,
  priceTierId: midTier, realTag: null, tasteTags: [taste],
  publishedAt: '2099-02-01 12:00', status: 'normal',
  stats: { views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 }
});
const latest = M3.buildFeed('nearby').find(d => d.shopId === hostShop);
assert('有更新的匹配内容时切换过去', latest && latest.id === 'test_pushed_2',
  latest ? latest.id : 'null');

/* ---------- 7. 随心看也受筛选影响 ---------- */
section('\u3010\u53cc Tab\u3011\u968f\u5fc3\u770b\u540c\u6837\u53d7\u7b5b\u9009\u5f71\u54cd');
M.clearFilter();
M.setFilter({ tiers: [midTier], tastes: [] });
const rnd = M.buildFeed('random');
assert('random tab 结果同样命中档位',
  rnd.length > 0 && rnd.every(d => d.priceTierId === midTier), rnd.length + ' 条');
assert('random tab 也每店一条', new Set(rnd.map(d => d.shopId)).size === rnd.length);

/* ---------- 8. 持久化 ---------- */
section('\u3010\u6301\u4e45\u5316\u3011\u8de8\u9875\u9762\u4fdd\u7559');
const M4 = load({ clientFilter: JSON.stringify({ tiers: [midTier], tastes: [taste] }) });
assert('重新加载后条件仍在', M4.filterActive() === true &&
  M4.getFilter().tiers[0] === midTier && M4.getFilter().tastes[0] === taste);
assert('重新加载后筛选立即生效',
  M4.buildFeed('nearby').every(d => d.priceTierId === midTier));

/* ---------- 9. 清除与边界 ---------- */
section('\u3010\u6e05\u9664\u4e0e\u8fb9\u754c\u3011');
M4.clearFilter();
assert('清除后筛选失效', M4.filterActive() === false && M4.getFilter().tiers.length === 0);
assert('清除后恢复完整信息流', M4.buildFeed('nearby').length === shopCount);

M.setFilter({ tiers: [], tastes: [] });
assert('两维全空时不写脏 key', M.getFilter().tiers.length === 0);
assert('matchFilter 空条件命中一切', M.matchFilter(M.dishes[0], { tiers: [], tastes: [] }) === true);
assert('matchFilter 对 null 安全', M.matchFilter(null, { tiers: [midTier], tastes: [] }) === false);
assert('matchFilter 不传参数时读当前条件', typeof M.matchFilter(M.dishes[0]) === 'boolean');
assert('getFilter 容忍损坏的 JSON', (function () {
  const M5 = load({ clientFilter: '{{{坏数据' });
  return M5.getFilter().tiers.length === 0 && M5.filterActive() === false;
})());

/* ---------- 失效条件的自动收敛 ---------- */
/* 场景：平台端删掉 / 改了一个价格档，用户浏览器里还存着旧 id。
   不校正的话首页会显示「筛选：tier_old」这种看不懂的原始 id，
   而且永久筛空，用户都不知道该清哪个条件。 */
section('失效条件自动收敛（平台改档位后的遗留状态）');

(function () {
  const M6 = load({ clientFilter: JSON.stringify({ tiers: ['tier_ghost'], tastes: ['麻辣'] }) });

  const f = M6.getFilter();
  assert('失效的档位 id 被剔除', f.tiers.length === 0, JSON.stringify(f.tiers));
  assert('仍有效的口味被保留', f.tastes.indexOf('麻辣') > -1, JSON.stringify(f.tastes));
  assert('校正结果已回写 localStorage',
    (M6.getFilter().tiers || []).length === 0 &&
    JSON.parse(M6.__storage.getItem('clientFilter')).tiers.length === 0);

  // 这次不筛价格档，但口味还在 —— 应该还能筛出东西，而不是筛空
  const list = M6.buildFeed('nearby');
  assert('校正后仍能筛出口味匹配的内容', list.length > 0, list.length + ' 条');

  // 标签文案里不能出现原始 id
  const label = M6.filterLabel({ tiers: ['tier_ghost'], tastes: ['麻辣'] });
  assert('标签不显示失效档位的原始 id', label.indexOf('tier_ghost') === -1, '→ ' + label);
})();

(function () {
  const M7 = load({ clientFilter: JSON.stringify({ tiers: ['tier_mid', 'tier_ghost'], tastes: [] }) });
  const f = M7.getFilter();
  assert('有效档位保留、失效档位剔除',
    f.tiers.length === 1 && f.tiers[0] === 'tier_mid', JSON.stringify(f.tiers));
})();

(function () {
  // 数据源还没就绪（适配层未把后端档位灌进来）时，绝不能把用户条件清空
  const M8 = load({ clientFilter: JSON.stringify({ tiers: ['tier_mid'], tastes: [] }) });
  const savedTiers = M8.priceTiers;
  M8.priceTiers = [];
  const f = M8.getFilter();
  assert('数据源为空时跳过校验，不清空用户条件',
    f.tiers.length === 1 && f.tiers[0] === 'tier_mid', JSON.stringify(f.tiers));
  M8.priceTiers = savedTiers;
})();

/* ---------- 汇总 ---------- */
console.log('\n' + '='.repeat(52));
console.log(fail === 0
  ? '\u5168\u90e8\u901a\u8fc7\uff1a' + pass + ' / ' + pass
  : '\u5931\u8d25 ' + fail + ' \u9879\uff0c\u901a\u8fc7 ' + pass + ' \u9879');
console.log('='.repeat(52));
process.exit(fail === 0 ? 0 : 1);
