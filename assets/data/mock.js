/* ==========================================================
   「吃什么」本地生活 —— 假数据
   三端共用。所有页面从这份数据渲染，保证一致性。
   图片使用 picsum 占位，替换真实素材时只改 url 即可。
   ========================================================== */

const MOCK = {

  /* ---------- 当前登录用户（客户端） ---------- */
  currentUser: {
    id: 'u_001',
    name: '小吃货',
    avatar: 'https://picsum.photos/seed/user1/100/100',
    city: '成都市',
    district: '武侯区',
    loggedIn: false
  },

  /* ---------- 当前商家（商家端） ---------- */
  currentMerchant: {
    id: 'm_001',
    shopId: 's_001',
    account: '13800138000',
    status: 'approved',        // pending | approved | rejected | muted | banned
    rejectReason: ''
  },

  /* ---------- 平台端当前运营 ---------- */
  currentAdmin: {
    id: 'a_001',
    name: '平台运营',
    role: 'super'
  },

  /* ---------- 价格档位（平台配置） ---------- */
  priceTiers: [
    { id: 'tier_mid',   name: '中等',   min: 0,   max: 50     },
    { id: 'tier_high',  name: '高等',   min: 50,  max: 150    },
    { id: 'tier_ultra', name: '超高级', min: 150, max: Infinity }
  ],

  /* ---------- 口味标签库（平台配置） ---------- */
  tasteTags: [
    '麻辣', '清淡', '酸甜', '烧烤', '火锅',
    '日料', '面食', '甜点', '汤类', '小吃'
  ],

  /* ---------- 真实性标签 ---------- */
  realTags: [
    { id: 'real',    name: '实拍认证', cls: 'tag--real'    },
    { id: 'ad',      name: '广告',     cls: 'tag--ad'      },
    { id: 'pending', name: '待核实',   cls: 'tag--pending' }
  ],

  /* ---------- 兴趣点（店铺） ---------- */
  shops: [
    {
      id: 's_001',
      name: '蜀香小馆',
      logo: 'https://picsum.photos/seed/shop1/200/200',
      cover: 'https://picsum.photos/seed/shopcover1/800/600',
      address: '成都市武侯区锦绣路 88 号',
      city: '成都市',
      district: '武侯区',
      lat: 30.6421,
      lng: 104.0432,
      distance: 1.2,
      phone: '028-8888-1111',
      hours: '10:00 - 22:00',
      cuisine: '川菜',
      intro: '开了十年的老川菜馆，师傅是自贡人，重麻重辣。',
      status: 'normal',          // normal | muted | banned
      weight: 10,
      pinned: true,
      canPostToday: false,
      lastPostAt: '2026-09-14 10:30',
      intervalHours: 24,
      dailyLimit: 1,
      stats: { dishes: 24, views: 12800, likes: 3200, checkins: 186 }
    },
    {
      id: 's_002',
      name: '老李炭火烧烤',
      logo: 'https://picsum.photos/seed/shop2/200/200',
      cover: 'https://picsum.photos/seed/shopcover2/800/600',
      address: '成都市武侯区科华北路 12 号',
      city: '成都市',
      district: '武侯区',
      lat: 30.6398,
      lng: 104.0511,
      distance: 0.8,
      phone: '028-8888-2222',
      hours: '17:00 - 02:00',
      cuisine: '烧烤',
      intro: '炭火现烤，每天凌晨四点去市场挑肉。',
      status: 'normal',
      weight: 8,
      pinned: false,
      canPostToday: true,
      lastPostAt: '2026-09-13 18:20',
      intervalHours: 24,
      dailyLimit: 1,
      stats: { dishes: 18, views: 8600, likes: 2100, checkins: 97 }
    },
    {
      id: 's_003',
      name: '川味坊',
      logo: 'https://picsum.photos/seed/shop3/200/200',
      cover: 'https://picsum.photos/seed/shopcover3/800/600',
      address: '成都市锦江区春熙路 5 号',
      city: '成都市',
      district: '锦江区',
      lat: 30.6572,
      lng: 104.0810,
      distance: 3.4,
      phone: '028-8888-3333',
      hours: '11:00 - 21:30',
      cuisine: '川菜',
      intro: '家常口味，分量足，附近上班族的食堂。',
      status: 'normal',
      weight: 5,
      pinned: false,
      canPostToday: true,
      lastPostAt: '2026-09-13 12:05',
      intervalHours: 24,
      dailyLimit: 1,
      stats: { dishes: 31, views: 6200, likes: 1400, checkins: 62 }
    },
    {
      id: 's_004',
      name: '一味面馆',
      logo: 'https://picsum.photos/seed/shop4/200/200',
      cover: 'https://picsum.photos/seed/shopcover4/800/600',
      address: '成都市武侯区人民南路 3 段 20 号',
      city: '成都市',
      district: '武侯区',
      lat: 30.6489,
      lng: 104.0678,
      distance: 2.1,
      phone: '028-8888-4444',
      hours: '07:00 - 14:00',
      cuisine: '面食',
      intro: '只做上午，汤底每天现熬，卖完就关门。',
      status: 'normal',
      weight: 3,
      pinned: false,
      canPostToday: true,
      lastPostAt: '2026-09-13 07:10',
      intervalHours: 24,
      dailyLimit: 1,
      stats: { dishes: 9, views: 4100, likes: 980, checkins: 143 }
    },
    {
      id: 's_005',
      name: '樱町日料',
      logo: 'https://picsum.photos/seed/shop5/200/200',
      cover: 'https://picsum.photos/seed/shopcover5/800/600',
      address: '成都市锦江区太古里 B1',
      city: '成都市',
      district: '锦江区',
      lat: 30.6543,
      lng: 104.0821,
      distance: 3.9,
      phone: '028-8888-5555',
      hours: '11:30 - 22:00',
      cuisine: '日料',
      intro: '主厨从东京回来，坚持每天空运。',
      status: 'normal',
      weight: 6,
      pinned: false,
      canPostToday: true,
      lastPostAt: '2026-09-13 19:40',
      intervalHours: 12,
      dailyLimit: 2,
      stats: { dishes: 15, views: 5400, likes: 1900, checkins: 41 }
    },
    {
      id: 's_006',
      name: '老字号甜水面',
      logo: 'https://picsum.photos/seed/shop6/200/200',
      cover: 'https://picsum.photos/seed/shopcover6/800/600',
      address: '成都市青羊区宽窄巷子旁',
      city: '成都市',
      district: '青羊区',
      lat: 30.6701,
      lng: 104.0523,
      distance: 4.6,
      phone: '028-8888-6666',
      hours: '08:00 - 20:00',
      cuisine: '小吃',
      intro: '三代传承，甜水面只此一家。',
      status: 'normal',
      weight: 4,
      pinned: false,
      canPostToday: true,
      lastPostAt: '2026-09-13 09:00',
      intervalHours: 24,
      dailyLimit: 1,
      stats: { dishes: 12, views: 3200, likes: 760, checkins: 205 }
    }
  ],

  /* ---------- 菜品 / 内容 ---------- */
  dishes: [
    {
      id: 'd_001',
      shopId: 's_001',
      shopName: '蜀香小馆',
      name: '麻婆豆腐',
      desc: '麻、辣、烫、香、酥、嫩，一口下去额头冒汗，但停不下筷子。',
      type: 'image',                       // image | video
      media: [
        'https://picsum.photos/seed/dish1a/800/1200',
        'https://picsum.photos/seed/dish1b/800/1200',
        'https://picsum.photos/seed/dish1c/800/1200'
      ],
      cover: 'https://picsum.photos/seed/dish1a/800/1200',
      price: 28,
      priceTierId: 'tier_mid',
      realTag: 'real',
      tasteTags: ['麻辣'],
      publishedAt: '2026-09-14 10:30',
      status: 'normal',                    // normal | removed
      stats: { views: 3200, likes: 412, favorites: 188, comments: 36, checkins: 24 }
    },
    {
      id: 'd_002',
      shopId: 's_002',
      shopName: '老李炭火烧烤',
      name: '炭烤五花肉',
      desc: '厚切带皮五花，炭火慢烤四十分钟，外焦里嫩。',
      type: 'video',
      media: ['https://picsum.photos/seed/dish2a/800/1200'],
      cover: 'https://picsum.photos/seed/dish2a/800/1200',
      price: 68,
      priceTierId: 'tier_high',
      realTag: 'real',
      tasteTags: ['烧烤'],
      publishedAt: '2026-09-13 18:20',
      status: 'normal',
      stats: { views: 2810, likes: 356, favorites: 142, comments: 28, checkins: 19 }
    },
    {
      id: 'd_003',
      shopId: 's_003',
      shopName: '川味坊',
      name: '回锅肉',
      desc: '',
      type: 'image',
      media: [
        'https://picsum.photos/seed/dish3a/800/1200',
        'https://picsum.photos/seed/dish3b/800/1200'
      ],
      cover: 'https://picsum.photos/seed/dish3a/800/1200',
      price: 42,
      priceTierId: 'tier_mid',
      realTag: 'ad',
      tasteTags: ['麻辣'],
      publishedAt: '2026-09-13 12:05',
      status: 'normal',
      stats: { views: 1420, likes: 168, favorites: 62, comments: 12, checkins: 8 }
    },
    {
      id: 'd_004',
      shopId: 's_005',
      shopName: '樱町日料',
      name: '蓝鳍金枪鱼大腹',
      desc: '每日空运，限量六份。油脂分布像雪花一样。',
      type: 'image',
      media: [
        'https://picsum.photos/seed/dish4a/800/1200',
        'https://picsum.photos/seed/dish4b/800/1200',
        'https://picsum.photos/seed/dish4c/800/1200',
        'https://picsum.photos/seed/dish4d/800/1200'
      ],
      cover: 'https://picsum.photos/seed/dish4a/800/1200',
      price: 288,
      priceTierId: 'tier_ultra',
      realTag: 'real',
      tasteTags: ['日料', '清淡'],
      publishedAt: '2026-09-13 19:40',
      status: 'normal',
      stats: { views: 1960, likes: 288, favorites: 210, comments: 44, checkins: 6 }
    },
    {
      id: 'd_005',
      shopId: 's_004',
      shopName: '一味面馆',
      name: '红油抄手',
      desc: '皮薄如纸，红油是自家舂的辣椒。',
      type: 'image',
      media: ['https://picsum.photos/seed/dish5a/800/1200'],
      cover: 'https://picsum.photos/seed/dish5a/800/1200',
      price: 18,
      priceTierId: 'tier_mid',
      realTag: 'real',
      tasteTags: ['麻辣', '面食'],
      publishedAt: '2026-09-13 07:10',
      status: 'normal',
      stats: { views: 1180, likes: 142, favorites: 58, comments: 9, checkins: 31 }
    },
    {
      id: 'd_006',
      shopId: 's_006',
      shopName: '老字号甜水面',
      name: '甜水面',
      desc: '一根面有拇指粗，酱料甜中带辣。',
      type: 'image',
      media: [
        'https://picsum.photos/seed/dish6a/800/1200',
        'https://picsum.photos/seed/dish6b/800/1200'
      ],
      cover: 'https://picsum.photos/seed/dish6a/800/1200',
      price: 12,
      priceTierId: 'tier_mid',
      realTag: 'real',
      tasteTags: ['小吃', '面食', '甜点'],
      publishedAt: '2026-09-13 09:00',
      status: 'normal',
      stats: { views: 980, likes: 176, favorites: 84, comments: 15, checkins: 47 }
    }
  ],

  /* ---------- 评论 ---------- */
  comments: [
    {
      id: 'c_001',
      dishId: 'd_001',
      userId: 'u_002',
      userName: '隔壁老王',
      avatar: 'https://picsum.photos/seed/user2/100/100',
      content: '昨天去吃了，豆腐嫩得离谱，配米饭绝了。',
      at: '2026-09-14 12:10',
      reply: {
        content: '谢谢支持！下次来给您多加点花椒~',
        at: '2026-09-14 12:40'
      }
    },
    {
      id: 'c_002',
      dishId: 'd_001',
      userId: 'u_003',
      userName: '干饭人小李',
      avatar: 'https://picsum.photos/seed/user3/100/100',
      content: '价格是不是涨了？之前好像 24。',
      at: '2026-09-14 13:22',
      reply: null
    },
    {
      id: 'c_003',
      dishId: 'd_002',
      userId: 'u_004',
      userName: '夜宵战神',
      avatar: 'https://picsum.photos/seed/user4/100/100',
      content: '烤四十分钟是真的，等的时候有点久但值得。',
      at: '2026-09-13 21:05',
      reply: {
        content: '慢工出细活，感谢理解！',
        at: '2026-09-13 21:30'
      }
    }
  ],

  /* ---------- 待审核商家（平台端） ---------- */
  pendingShops: [
    {
      id: 'p_001',
      name: '新开的螺蛳粉',
      logo: 'https://picsum.photos/seed/pending1/200/200',
      cover: 'https://picsum.photos/seed/pendingcover1/800/600',
      address: '成都市成华区建设路 66 号',
      city: '成都市',
      district: '成华区',
      phone: '028-8888-7777',
      hours: '10:00 - 23:00',
      cuisine: '小吃',
      intro: '正宗柳州味道，酸笋每天现发。',
      submittedAt: '2026-09-14 09:15'
    },
    {
      id: 'p_002',
      name: '巷子口串串香',
      logo: 'https://picsum.photos/seed/pending2/200/200',
      cover: 'https://picsum.photos/seed/pendingcover2/800/600',
      address: '成都市金牛区抚琴西路 8 号',
      city: '成都市',
      district: '金牛区',
      phone: '028-8888-8888',
      hours: '16:00 - 03:00',
      cuisine: '火锅',
      intro: '老巷子里的苍蝇馆子，开了七年。',
      submittedAt: '2026-09-14 10:02'
    }
  ],

  /* ---------- 举报（平台端） ---------- */
  reports: [
    {
      id: 'r_001',
      type: 'dish',
      targetId: 'd_003',
      targetName: '回锅肉',
      reason: '图片与实物不符',
      reporter: '干饭人小李',
      at: '2026-09-14 11:20',
      status: 'pending'
    },
    {
      id: 'r_002',
      type: 'comment',
      targetId: 'c_002',
      targetName: '评论：价格是不是涨了',
      reason: '恶意刷差评',
      reporter: '蜀香小馆',
      at: '2026-09-14 11:45',
      status: 'pending'
    }
  ],

  /* ---------- 用户（平台端） ---------- */
  users: [
    { id: 'u_001', name: '小吃货',     avatar: 'https://picsum.photos/seed/user1/100/100', comments: 12, reports: 0,  status: 'normal', at: '2026-08-01' },
    { id: 'u_002', name: '隔壁老王',   avatar: 'https://picsum.photos/seed/user2/100/100', comments: 45, reports: 0,  status: 'normal', at: '2026-07-12' },
    { id: 'u_003', name: '干饭人小李', avatar: 'https://picsum.photos/seed/user3/100/100', comments: 88, reports: 3,  status: 'warned', at: '2026-06-20' },
    { id: 'u_004', name: '夜宵战神',   avatar: 'https://picsum.photos/seed/user4/100/100', comments: 26, reports: 0,  status: 'normal', at: '2026-08-15' }
  ],

  /* ---------- 口味 / 菜系下拉 ---------- */
  cuisines: ['川菜', '火锅', '烧烤', '日料', '面食', '小吃', '西餐', '甜品', '粤菜', '湘菜'],

  /* ==========================================================
     工具函数
     ========================================================== */

  /* 按 id 取店铺 */
  getShop(id) {
    return this.shops.find(s => s.id === id) || null;
  },

  /* 按 id 取菜品 */
  getDish(id) {
    return this.dishes.find(d => d.id === id) || null;
  },

  /* 取某店铺的菜品 */
  getShopDishes(shopId) {
    return this.dishes.filter(d => d.shopId === shopId);
  },

  /* 取某菜品的评论 */
  getDishComments(dishId) {
    return this.comments.filter(c => c.dishId === dishId);
  },

  /* 价格自动归档 */
  getTierByPrice(price) {
    const p = Number(price);
    if (isNaN(p) || p < 0) return null;
    for (const t of this.priceTiers) {
      if (p >= t.min && p < t.max) return t;
    }
    return this.priceTiers[this.priceTiers.length - 1];
  },

  /* 价格档 → 样式类名 */
  tierClass(tierId) {
    return {
      tier_mid: 'tag--price-mid',
      tier_high: 'tag--price-high',
      tier_ultra: 'tag--price-ultra'
    }[tierId] || 'tag--neutral';
  },

  /* 真实性标签 → 样式类名 */
  realClass(realId) {
    return {
      real: 'tag--real',
      ad: 'tag--ad',
      pending: 'tag--pending'
    }[realId] || 'tag--neutral';
  },

  /* 真实性标签 → 显示名 */
  realName(realId) {
    const t = this.realTags.find(r => r.id === realId);
    return t ? t.name : '';
  },

  /* 时间友好化 */
  timeAgo(str) {
    const t = new Date(str.replace(/-/g, '/')).getTime();
    const diff = Date.now() - t;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
    if (diff < 604800000) return Math.floor(diff / 86400000) + '天前';
    return str.slice(5, 10);
  },

  /* 数字格式化 */
  fmtNum(n) {
    if (n >= 10000) return (n / 10000).toFixed(1) + '万';
    return String(n);
  },

  /* 排序：置顶 > 权重 > 距离 > 时间衰减 */
  sortShops(list) {
    return [...list].sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      if (a.weight !== b.weight) return b.weight - a.weight;
      return a.distance - b.distance;
    });
  },

  /* 时间衰减权重（发布 24h 内线性衰减，之后归零） */
  timeDecay(publishedAt) {
    const t = new Date(publishedAt.replace(/-/g, '/')).getTime();
    const hours = (Date.now() - t) / 3600000;
    if (hours >= 24) return 0;
    return 1 - hours / 24;
  },

  /* ---------- 筛选条件（跨页面持久化） ---------- */

  /*
   * 筛选条件存在 localStorage，这样「筛选页 → 首页」能带上，
   * 刷新、从别的页面返回也都记得住，直到用户主动清除。
   */
  filterKey: 'clientFilter',

  getFilter() {
    try {
      const raw = localStorage.getItem('clientFilter');
      if (!raw) return { tiers: [], tastes: [] };
      const o = JSON.parse(raw) || {};
      return { tiers: o.tiers || [], tastes: o.tastes || [] };
    } catch (e) {
      return { tiers: [], tastes: [] };
    }
  },

  setFilter(f) {
    const v = {
      tiers: (f && f.tiers) ? f.tiers.slice() : [],
      tastes: (f && f.tastes) ? f.tastes.slice() : []
    };
    // 两个维度都空 = 没筛选，直接清掉 key，避免残留脏状态
    if (!v.tiers.length && !v.tastes.length) localStorage.removeItem('clientFilter');
    else localStorage.setItem('clientFilter', JSON.stringify(v));
    return v;
  },

  clearFilter() {
    localStorage.removeItem('clientFilter');
    return { tiers: [], tastes: [] };
  },

  /* 当前是否处于筛选状态 */
  filterActive(f) {
    const x = f || this.getFilter();
    return !!(x.tiers.length || x.tastes.length);
  },

  /* 筛选条件的中文描述，给首页顶部那条提示用 */
  filterLabel(f) {
    const x = f || this.getFilter();
    const names = x.tiers.map(id => {
      const t = this.priceTiers.find(p => p.id === id);
      return t ? t.name : id;
    });
    return names.concat(x.tastes).join(' · ');
  },

  /*
   * 单条菜品是否命中筛选条件。
   * 维度内部 OR（选了 3 个口味，命中任一即可）
   * 维度之间 AND（价格档和口味都要满足）
   * —— 与 docs/05-后端接口契约.md 的口径一致。
   */
  matchFilter(d, f) {
    if (!d) return false;
    const x = f || this.getFilter();
    if (!this.filterActive(x)) return true;
    const tierOk = !x.tiers.length || x.tiers.includes(d.priceTierId);
    const tasteOk = !x.tastes.length ||
      (d.tasteTags || []).some(t => x.tastes.includes(t));
    return tierOk && tasteOk;
  },

  /* 时间字符串 → 时间戳（内部比较用） */
  _ts(v) {
    if (!v) return 0;
    if (typeof v === 'number') return v;
    const t = new Date(String(v).replace(/-/g, '/')).getTime();
    return isNaN(t) ? 0 : t;
  },

  /**
   * 按 Tab 生成信息流 —— 筛选、每店一条、内容回退全部收敛在这里，
   * 页面只负责渲染。
   *
   * tab    nearby（附近，按推荐权重排） | random（随心看，随机）
   * filter { tiers, tastes }，不传就读 localStorage
   *
   * 三条关键规则：
   * 1. 首页每店只露一条 —— 所以必须按 shopId 去重。
   * 2. 筛选态下取该店「符合条件的最新一条」，且不限 24h
   *    —— 否则筛"超高级"这类小众条件会直接筛空。
   * 3. 筛选态下不做「不足 3 条回退旧内容」—— 回退会把不符合
   *    条件的菜塞回来，筛选就失效了。
   */
  buildFeed(tab, filter) {
    const f = filter || this.getFilter();
    const active = this.filterActive(f);

    // 1) 候选池：只取上架内容
    let pool = this.dishes.filter(d => d.status === 'normal');

    // 2) 应用筛选
    if (active) pool = pool.filter(d => this.matchFilter(d, f));

    // 3) 每店一条：取发布时间最新的那条
    const byShop = {};
    pool.forEach(d => {
      const cur = byShop[d.shopId];
      if (!cur || this._ts(d.publishedAt) > this._ts(cur.publishedAt)) {
        byShop[d.shopId] = d;
      }
    });
    let items = Object.values(byShop);

    // 4) 内容不足 3 条时回退旧内容补位（决策 D2），只在未筛选时生效。
    //    注意：当前设计下这个分支不会触发 —— 首页是「每店一条」，
    //    而 items 已经覆盖了所有有内容的店铺，没有店可补。
    //    保留它是给将来兜底：若给常态加上时间窗（只推 24h 内新菜），
    //    items 会变小，那时补位才有实际意义。
    if (!active && items.length < 3) {
      const used = {};
      items.forEach(d => { used[d.shopId] = true; });
      const all = this.dishes
        .filter(d => d.status === 'normal')
        .sort((a, b) => this._ts(b.publishedAt) - this._ts(a.publishedAt));
      for (const d of all) {
        if (items.length >= 3) break;
        if (used[d.shopId]) continue;
        used[d.shopId] = true;
        items.push(d);
      }
    }

    // 5) 按 Tab 排序
    if (tab === 'random') {
      const list = [...items];
      for (let i = list.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [list[i], list[j]] = [list[j], list[i]];
      }
      return list;
    }

    // 附近：置顶 > 权重 > 距离 > 时间衰减
    return items.sort((a, b) => {
      const sa = this.getShop(a.shopId);
      const sb = this.getShop(b.shopId);
      if (!sa || !sb) return 0;
      if (sa.pinned !== sb.pinned) return sa.pinned ? -1 : 1;
      if (sa.weight !== sb.weight) return sb.weight - sa.weight;
      if (sa.distance !== sb.distance) return sa.distance - sb.distance;
      return this.timeDecay(b.publishedAt) - this.timeDecay(a.publishedAt);
    });
  },

  /* 价格区间文案 */
  tierRange(tier) {
    if (tier.max === Infinity) return '¥' + tier.min + ' 以上';
    return '¥' + tier.min + ' - ¥' + tier.max;
  },

  /* 打卡计数（内存） */
  _checkins: {},

  doCheckin(dishId) {
    this._checkins[dishId] = (this._checkins[dishId] || 0) + 1;
    const d = this.getDish(dishId);
    if (d) d.stats.checkins += 1;
    return this._checkins[dishId];
  }
};

/* 暴露到全局 */
window.MOCK = MOCK;
