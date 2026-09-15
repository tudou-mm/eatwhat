/* ==========================================================
   API 适配层
   ----------------------------------------------------------
   作用：把原型的同步假数据（MOCK）平滑换成真后端。
   三端（客户端 / 商家端 / 平台端）共用这一个文件。

   设计原则（很重要）：
   1. 保持与 MOCK 完全一致的函数签名，所以三端 22 个页面
      的**读取**代码一行都不用改。
   2. 采用「同步预加载 + 内存缓存」：本文件加载时先把后端数据
      拉下来塞进缓存，之后页面里任何 MOCK.getShop(id) 这类
      **同步调用**都直接从缓存读。
   3. 写操作用 MOCK.act(动作, 参数) 统一入口：
      - 后端在线 → 同步 XHR 落库，成功后用返回值回写内存
      - 后端没开 → 直接改内存，原型照样能完整演示
      两种模式下页面代码完全一样。
   4. 未开启开关或后端未启动时，自动降级回本地假数据，
      原型永远能打开。

   怎么切换到后端：
     - 地址栏加参数  ?api=1     （推荐，方便演示）
     - 或控制台执行  localStorage.setItem('useApi','1'); location.reload()
   切回假数据：
     - ?api=0  或  localStorage.removeItem('useApi')

   为什么用同步 XHR？
     页面的业务脚本是**同步执行**的，会在 DOMContentLoaded 之前就
     调 MOCK.xxx。异步预加载会让首页永远先渲染假数据，之后也不会
     自动重绘 —— 那样「零改动」就是假的。同步 XHR 会阻塞主线程
     一两百毫秒，但换来真正的零改动，对原型演示是划算的取舍。
     ⚠️ 代价：若 apiBase 指向一个不可达的**远程**主机，同步请求会
     卡到 TCP 超时。默认 localhost 连不上会立刻失败，无需担心。
   ========================================================== */
(function () {
  'use strict';

  // ---------- 开关来源：URL 参数优先，其次 localStorage ----------
  var qs = new URLSearchParams(location.search);
  if (qs.get('api') === '1') localStorage.setItem('useApi', '1');
  else if (qs.get('api') === '0') localStorage.removeItem('useApi');
  if (qs.get('apiBase')) localStorage.setItem('apiBase', qs.get('apiBase'));

  var API_BASE = localStorage.getItem('apiBase') || 'http://localhost:8080/api';
  var enabled = localStorage.getItem('useApi') === '1';

  // ---------- 端识别 ----------
  // 同一份 api.js 被三个端引用，要拉的数据不一样：
  //   平台端  → /admin/bootstrap（全量店铺，含待审核和封禁）
  //   其他    → /client/bootstrap（只给客户端可见的数据）
  var END = (function () {
    var p = (location.pathname || '').replace(/\\/g, '/');
    if (/\/admin\//i.test(p)) return 'admin';
    if (/\/merchant\//i.test(p)) return 'merchant';
    return 'client';
  })();

  // 缓存区
  var cache = {
    shops: [],
    dishes: [],
    comments: [],
    removed: [],
    users: [],
    reports: [],
    pendingShops: [],
    approvedShops: [],
    rejectedShops: [],
    overview: {},
    priceTiers: [],
    tasteTags: [],
    cuisines: [],
    loaded: false
  };

  // ================= 同步请求（见文首说明） =================

  function syncRequest(method, path, body) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, API_BASE + path, false);   // false = 同步
    xhr.setRequestHeader('Accept', 'application/json');
    if (body !== undefined && body !== null) {
      xhr.setRequestHeader('Content-Type', 'application/json');
    }
    xhr.send(body === undefined || body === null ? null : JSON.stringify(body));
    if (xhr.status < 200 || xhr.status >= 300) {
      throw new Error('HTTP ' + xhr.status);
    }
    var j = JSON.parse(xhr.responseText);
    if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
    return j.data;
  }

  function syncGet(path) { return syncRequest('GET', path, null); }
  function syncPost(path, body) { return syncRequest('POST', path, body || {}); }

  /** 把配置（价格档 / 口味 / 菜系 / 全局发布规则）灌进内存与 MOCK */
  function applyConfig(cfg) {
    cfg = cfg || {};
    cache.priceTiers = cfg.priceTiers || [];
    cache.tasteTags = cfg.tasteTags || [];
    cache.cuisines = cfg.cuisines || [];
    if (cfg.publishRule) cache.publishRule = cfg.publishRule;

    if (!window.MOCK) return;
    if (cache.priceTiers.length) window.MOCK.priceTiers = cache.priceTiers;
    if (cache.tasteTags.length) window.MOCK.tasteTags = cache.tasteTags;
    if (cache.cuisines.length) window.MOCK.cuisines = cache.cuisines;
    if (cache.publishRule) window.MOCK.publishRule = cache.publishRule;
  }

  /** 客户端 / 商家端数据 */
  function applyClientData(d) {
    d = d || {};
    cache.shops = d.shops || [];
    cache.dishes = d.dishes || [];
    cache.comments = d.comments || [];
    applyConfig(d.config);

    if (!window.MOCK) return;
    window.MOCK.shops = cache.shops;
    window.MOCK.dishes = cache.dishes;
    window.MOCK.comments = cache.comments;
  }

  /**
   * 平台端数据。
   * 平台端要用的全局对象比客户端多得多 —— 待审核、举报、用户、已下架，
   * 少接一个，对应页面就会继续显示写死的假数据，而且不报错。
   * 这里一次全部对齐。
   */
  function applyAdminData(d) {
    d = d || {};
    cache.shops = d.shops || [];
    cache.dishes = d.dishes || [];
    cache.removed = d.removed || [];
    cache.reports = d.reports || [];
    cache.users = d.users || [];
    cache.pendingShops = d.pendingShops || [];
    cache.approvedShops = d.approvedShops || [];
    cache.rejectedShops = d.rejectedShops || [];
    cache.overview = d.overview || {};
    applyConfig(d.config);

    if (!window.MOCK) return;
    var M = window.MOCK;
    M.shops = cache.shops;
    M.dishes = cache.dishes;
    M.removedDishes = cache.removed;
    M.reports = cache.reports;
    M.users = cache.users;
    M.pendingShops = cache.pendingShops;
    M.approvedShops = cache.approvedShops;
    M.rejectedShops = cache.rejectedShops;
    M.overview = cache.overview;
  }

  /**
   * 同步预加载：把后端数据灌进缓存。
   * 返回 true 表示成功接上后端；false 表示已降级回本地假数据。
   */
  function preloadSync() {
    var t0 = Date.now();
    try {
      // 每端只打一个聚合接口，请求数直接等于白屏时长，能少则少
      if (END === 'admin') {
        applyAdminData(syncGet('/admin/bootstrap') || {});
      } else {
        applyClientData(syncGet('/client/bootstrap') || {});
      }
      cache.loaded = true;

      console.log('%c[api] 后端已接上 ' + API_BASE +
        '  →  %d 店 / %d 菜 / 待审核 %d / 举报 %d，耗时 %dms',
        'color:#FE2C55;font-weight:bold',
        cache.shops.length, cache.dishes.length,
        cache.pendingShops.length, cache.reports.length, Date.now() - t0);
      return true;
    } catch (e) {
      console.warn('[api] 后端未就绪，已降级到本地假数据：' + e.message);
      enabled = false;
      cache.loaded = false;
      return false;
    }
  }

  var online = function () { return enabled && cache.loaded; };

  // ================= 异步请求（写操作 / 未来用） =================

  function get(path) {
    return fetch(API_BASE + path, { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  function send(method, path, body) {
    return fetch(API_BASE + path, {
      method: method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  function post(path, body) { return send('POST', path, body); }

  /** 异步预加载：页面若愿意 await，可以拿到最新的后端数据 */
  function preload() {
    if (!enabled) return Promise.resolve(false);
    var path = END === 'admin' ? '/admin/bootstrap' : '/client/bootstrap';
    return get(path)
      .then(function (d) {
        if (END === 'admin') applyAdminData(d); else applyClientData(d);
        cache.loaded = true;
        return true;
      })
      .catch(function () { return false; });
  }

  // ================= 同步读取 API（签名与 MOCK 保持一致） =================
  // 注意：回退时一律调 window.MOCK['_local_xxx']，
  // 不能再调 window.MOCK.xxx —— 那会绕回这里形成无限递归。

  var API = {
    isEnabled: function () { return enabled; },
    isOnline: online,
    end: END,
    preload: preload,
    preloadSync: preloadSync,
    cache: cache,

    // ---- 基础读取 ----
    getShop: function (id) {
      if (cache.loaded) {
        return cache.shops.find(function (s) { return s.id === id; }) || null;
      }
      return window.MOCK ? window.MOCK['_local_getShop'](id) : null;
    },
    getDish: function (id) {
      if (cache.loaded) {
        return cache.dishes.find(function (d) { return d.id === id; }) || null;
      }
      return window.MOCK ? window.MOCK['_local_getDish'](id) : null;
    },
    getShopDishes: function (shopId) {
      if (cache.loaded) {
        return cache.dishes.filter(function (d) { return d.shopId === shopId; });
      }
      return window.MOCK ? window.MOCK['_local_getShopDishes'](shopId) : [];
    },
    getDishComments: function (dishId) {
      if (cache.loaded) {
        return cache.comments.filter(function (c) { return c.dishId === dishId; });
      }
      return window.MOCK ? window.MOCK['_local_getDishComments'](dishId) : [];
    },

    // ---- 纯计算类：本地算即可，不必打接口 ----
    getTierByPrice: function (price) {
      var p = Number(price);
      if (isNaN(p) || p < 0) return null;
      var tiers = cache.priceTiers.length ? cache.priceTiers
                : (window.MOCK ? window.MOCK.priceTiers : []);
      for (var i = 0; i < tiers.length; i++) {
        var t = tiers[i];
        var max = (t.max === -1 || t.max === null || t.max === undefined) ? Infinity : t.max;
        if (p >= t.min && p < max) return t;
      }
      return tiers[tiers.length - 1] || null;
    },
    timeDecay: function (publishedTs) {
      // 兼容字符串日期与时间戳
      var t = typeof publishedTs === 'number'
        ? publishedTs
        : new Date(String(publishedTs).replace(/-/g, '/')).getTime();
      var hours = (Date.now() - t) / 3600000;
      if (hours >= 24) return 0;
      if (hours < 0) return 1;
      return 1 - hours / 24;
    },

    // ---- 写操作：直打后端（异步版，给愿意 await 的页面用） ----
    doCheckin: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'checkin' }); },
    like: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'like' }); },
    favorite: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'favorite' }); },
    addComment: function (dishId, userId, content) {
      return post('/client/dish/' + dishId + '/comment', { userId: userId, content: content });
    }
  };

  // ================= 写操作统一入口 =================
  /*
   * 每个动作三段：
   *   path   后端地址（后端在线时用）
   *   body   请求体
   *   local  后端不在时的本地实现 —— 直接改内存，让原型还能演示
   *   apply  后端返回后的回写 —— 用服务端的权威数据覆盖本地内存
   *
   * 为什么要 apply 回写而不是本地"猜"一个结果：
   * 服务端可能算出了派生字段（比如改价格档后重算出的所有菜品档位、
   * 审核后写入的审核人和时间）。本地猜的结果会和真实结果越走越偏。
   */

  /** 用最新对象替换数组里的同 id 项；找不到就 push */
  function upsert(list, item) {
    if (!list || !item || !item.id) return;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === item.id) { list[i] = item; return; }
    }
    list.push(item);
  }

  function removeById(list, id) {
    if (!list) return null;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === id) return list.splice(i, 1)[0];
    }
    return null;
  }

  function findShop(id) {
    var M = window.MOCK;
    return (cache.shops || []).find(function (s) { return s.id === id; })
        || (M && M.shops || []).find(function (s) { return s.id === id; })
        || null;
  }

  function findDish(id) {
    var M = window.MOCK;
    var lists = [cache.dishes, cache.removed, M && M.dishes, M && M.removedDishes];
    for (var i = 0; i < lists.length; i++) {
      var hit = (lists[i] || []).find(function (d) { return d.id === id; });
      if (hit) return hit;
    }
    return null;
  }

  function findUser(id) {
    return (cache.users || []).find(function (u) { return u.id === id; }) || null;
  }

  function findReport(id) {
    return (cache.reports || []).find(function (r) { return r.id === id; }) || null;
  }

  /** 把一份菜品数组按 status 拆到「在架」和「已下架」两个桶里 */
  function reconcileDishLists() {
    var M = window.MOCK;
    if (!M) return;
    var all = (M.dishes || []).concat(M.removedDishes || []);
    var seen = {};
    var normal = [], removed = [];
    all.forEach(function (d) {
      if (!d || seen[d.id]) return;
      seen[d.id] = 1;
      (d.status === 'removed' ? removed : normal).push(d);
    });
    M.dishes = normal;
    M.removedDishes = removed;
    cache.dishes = normal;
    cache.removed = removed;
  }

  var ACTIONS = {

    // ---------- 商家审核 ----------
    'audit.approve': {
      path: function (p) { return '/admin/audit/' + p.id + '/approve'; },
      body: function (p) { return { reviewer: p.reviewer || '平台运营' }; },
      local: function (p) {
        var M = window.MOCK;
        var s = removeById(M.pendingShops, p.id);
        if (!s) return false;
        s.status = 'normal';
        s.reviewer = p.reviewer || '平台运营';
        s.reviewedAt = nowStr();
        upsert(M.approvedShops, s);
        upsert(M.shops, s);
        return true;
      },
      apply: function (data) {
        var M = window.MOCK;
        removeById(M.pendingShops, data.id);
        upsert(M.approvedShops, data);
        upsert(M.shops, data);
      }
    },

    'audit.reject': {
      path: function (p) { return '/admin/audit/' + p.id + '/reject'; },
      body: function (p) { return { reason: p.reason, reviewer: p.reviewer || '平台运营' }; },
      local: function (p) {
        var M = window.MOCK;
        var s = removeById(M.pendingShops, p.id);
        if (!s) return false;
        s.status = 'rejected';
        s.rejectReason = p.reason;
        s.reviewer = p.reviewer || '平台运营';
        s.reviewedAt = nowStr();
        upsert(M.rejectedShops, s);
        return true;
      },
      apply: function (data) {
        var M = window.MOCK;
        removeById(M.pendingShops, data.id);
        upsert(M.rejectedShops, data);
      }
    },

    // ---------- 店铺状态 ----------
    'shop.mute':    shopStatusAction('mute', 'muted'),
    'shop.ban':     shopStatusAction('ban', 'banned'),
    'shop.restore': shopStatusAction('restore', 'normal'),

    // ---------- 发布规则 / 权重 / 置顶 ----------
    'shop.rule': {
      path: function (p) { return '/admin/shop/' + p.id + '/rule'; },
      body: function (p) { return { intervalHours: p.intervalHours, dailyLimit: p.dailyLimit }; },
      local: function (p) {
        var s = findShop(p.id);
        if (!s) return false;
        if (p.intervalHours != null) s.intervalHours = p.intervalHours;
        if (p.dailyLimit != null) s.dailyLimit = p.dailyLimit;
        return true;
      },
      apply: function (data) { upsert(cache.shops, data); upsert(window.MOCK.shops, data); }
    },

    'shop.pinned': {
      path: function (p) { return '/admin/shop/' + p.id + '/pinned'; },
      body: function (p) { return { pinned: !!p.pinned }; },
      local: function (p) {
        var s = findShop(p.id);
        if (!s) return false;
        s.pinned = !!p.pinned;
        return true;
      },
      apply: function (data) { upsert(cache.shops, data); upsert(window.MOCK.shops, data); }
    },

    'shop.weight': {
      path: function (p) { return '/admin/shop/' + p.id + '/weight'; },
      body: function (p) { return { weight: p.weight }; },
      local: function (p) {
        var s = findShop(p.id);
        if (!s) return false;
        s.weight = p.weight;
        return true;
      },
      apply: function (data) { upsert(cache.shops, data); upsert(window.MOCK.shops, data); }
    },

    // 拖拽排序：本地按顺序写回权重，与后端 applyOrder 的口径一致 —— 后一项权重依次递减
    'ranking.order': {
      path: function () { return '/admin/ranking/order'; },
      body: function (p) { return { shopIds: p.shopIds }; },
      local: function (p) {
        // 权重取 100 递减，保证排序结果稳定可复现
        p.shopIds.forEach(function (id, i) {
          var s = findShop(id);
          if (s) s.weight = 100 - i;
        });
        return true;
      },
      apply: function (data) {
        if (Array.isArray(data)) {
          cache.shops = data;
          window.MOCK.shops = data;
        }
      }
    },

    // ---------- 内容管理 ----------
    'dish.status': {
      path: function (p) { return '/admin/dish/' + p.id + '/status'; },
      body: function (p) { return { status: p.status }; },
      local: function (p) {
        var d = findDish(p.id);
        if (!d) return false;
        d.status = p.status;
        reconcileDishLists();
        return true;
      },
      apply: function (data) {
        upsert(window.MOCK.dishes, data);
        upsert(window.MOCK.removedDishes, data);
        reconcileDishLists();
      }
    },

    'dish.realTag': {
      path: function (p) { return '/admin/dish/' + p.id + '/real-tag'; },
      body: function (p) { return { realTag: p.realTag }; },
      local: function (p) {
        var d = findDish(p.id);
        if (!d) return false;
        d.realTag = p.realTag;
        return true;
      },
      apply: function (data) {
        upsert(window.MOCK.dishes, data);
        upsert(window.MOCK.removedDishes, data);
      }
    },

    // 举报处理：确认违规时后端会连带下架内容 / 隐藏评论，这里同步联动
    'report.handle': {
      path: function (p) { return '/admin/report/' + p.id + '/handle'; },
      body: function (p) { return { action: p.action }; },
      local: function (p) {
        var M = window.MOCK;
        var r = findReport(p.id);
        if (!r) return false;
        r.status = p.action;
        if (p.action === 'confirmed') {
          if (r.type === 'dish') {
            var d = findDish(r.targetId);
            if (d) { d.status = 'removed'; reconcileDishLists(); }
          } else {
            removeById(M.comments, r.targetId);
          }
        }
        return true;
      },
      apply: function (data, p) {
        var M = window.MOCK;
        upsert(M.reports, data);
        upsert(cache.reports, data);
        // 后端已经把内容下架了，本地跟着走一遍，避免两边不一致
        if (p.action === 'confirmed') {
          if (data.type === 'dish') {
            var d = findDish(data.targetId);
            if (d) { d.status = 'removed'; reconcileDishLists(); }
          } else {
            removeById(M.comments, data.targetId);
          }
        }
      }
    },

    // ---------- 用户管理 ----------
    'user.status': {
      path: function (p) { return '/admin/user/' + p.id + '/status'; },
      body: function (p) { return { status: p.status }; },
      local: function (p) {
        var u = findUser(p.id);
        if (!u) return false;
        u.status = p.status;
        return true;
      },
      apply: function (data) { upsert(window.MOCK.users, data); upsert(cache.users, data); }
    },

    // ---------- 平台配置 ----------
    // 保存价格档是「商家只填价格、系统自动归档」的兑现点：
    // 档位一变，所有菜品的档位必须跟着重算，否则会留下指向旧档位的脏数据。
    'config.priceTiers': {
      path: function () { return '/admin/config/price-tiers'; },
      body: function (p) { return { tiers: p.tiers }; },
      local: function (p) {
        window.MOCK.priceTiers = p.tiers;
        cache.priceTiers = p.tiers;
        recalcTiersLocally();
        return true;
      },
      apply: function (data) {
        window.MOCK.priceTiers = data.priceTiers;
        cache.priceTiers = data.priceTiers;
        if (data.dishes) {
          window.MOCK.dishes = data.dishes;
          cache.dishes = data.dishes;
        }
      }
    },

    'config.tasteTags': {
      path: function () { return '/admin/config/taste-tags'; },
      body: function (p) { return { tags: p.tags }; },
      local: function (p) {
        window.MOCK.tasteTags = p.tags;
        cache.tasteTags = p.tags;
        return true;
      },
      apply: function (data) {
        window.MOCK.tasteTags = data;
        cache.tasteTags = data;
      }
    },

    'config.cuisines': {
      path: function () { return '/admin/config/cuisines'; },
      body: function (p) { return { cuisines: p.cuisines }; },
      local: function (p) {
        window.MOCK.cuisines = p.cuisines;
        cache.cuisines = p.cuisines;
        return true;
      },
      apply: function (data) {
        window.MOCK.cuisines = data;
        cache.cuisines = data;
      }
    },

    // 全局默认发布规则：决定新店铺的初始值，也是单店「自定义」标记的比较基准
    'config.publishRule': {
      path: function () { return '/admin/config/publish-rule'; },
      body: function (p) { return { intervalHours: p.intervalHours, dailyLimit: p.dailyLimit }; },
      local: function (p) {
        window.MOCK.publishRule = { intervalHours: p.intervalHours, dailyLimit: p.dailyLimit };
        return true;
      },
      apply: function (data) {
        window.MOCK.publishRule = {
          intervalHours: data.intervalHours, dailyLimit: data.dailyLimit
        };
        cache.publishRule = window.MOCK.publishRule;
      }
    }
  };

  /** 店铺状态类动作的工厂：mute / ban / restore 三者只差状态值 */
  function shopStatusAction(name, status) {
    return {
      path: function (p) { return '/admin/shop/' + p.id + '/' + name; },
      local: function (p) {
        var s = findShop(p.id);
        if (!s) return false;
        s.status = status;
        return true;
      },
      apply: function (data) { upsert(cache.shops, data); upsert(window.MOCK.shops, data); }
    };
  }

  /** 本地模式下重算所有菜品档位，对齐后端 recalcAllTiers */
  function recalcTiersLocally() {
    var tiers = window.MOCK.priceTiers || [];
    (window.MOCK.dishes || []).forEach(function (d) {
      if (d.price == null) return;
      for (var i = 0; i < tiers.length; i++) {
        var t = tiers[i];
        var max = (t.max === -1 || t.max === null || t.max === undefined) ? Infinity : t.max;
        if (d.price >= t.min && d.price < max) { d.priceTierId = t.id; return; }
        if (i === tiers.length - 1) d.priceTierId = t.id;
      }
    });
  }

  function nowStr() {
    var d = new Date();
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
           ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  /**
   * 执行一个写操作。
   *
   * 返回 { ok, data?, msg? }
   *   后端在线：真落库，成功后回写内存，返回 { ok:true, data:服务端最新对象 }
   *   后端不在：改内存，返回 { ok:true, local:true }
   *   出错    ：{ ok:false, msg:'...' } —— 页面拿 msg 弹 toast 即可
   *
   * 页面统一写法：
   *   var r = MOCK.act('shop.ban', { id: id });
   *   if (!r.ok) return toast(r.msg || '操作失败');
   *   render();
   */
  function act(name, payload) {
    payload = payload || {};
    var def = ACTIONS[name];
    if (!def) return { ok: false, msg: '未知操作：' + name };

    if (!online()) {
      try {
        var done = def.local ? def.local(payload) : true;
        if (done === false) return { ok: false, msg: '本地数据里找不到目标对象' };
        return { ok: true, local: true };
      } catch (e) {
        return { ok: false, msg: e.message };
      }
    }

    try {
      var data = syncRequest('POST', def.path(payload), def.body ? def.body(payload) : payload);
      if (def.apply) def.apply(data, payload);
      return { ok: true, data: data };
    } catch (e) {
      return { ok: false, msg: e.message };
    }
  }

  /**
   * 把 API 挂到 window.MOCK 上：页面一律写 MOCK.getShop(id) / MOCK.act(...)，
   * 适配层负责内部路由到后端或本地，页面代码无需感知后端是否存在。
   *
   * 注意：必须「立即」执行，不能等 DOMContentLoaded ——
   * 因为页面脚本在 mock.js 之后同步执行，可能马上就调 MOCK.xxx。
   */
  function install() {
    if (!window.MOCK) return;

    window.MOCK.act = act;
    window.MOCK.isOnline = online;

    var readMethods = ['getShop', 'getDish', 'getShopDishes', 'getDishComments', 'getTierByPrice'];
    readMethods.forEach(function (m) {
      if (typeof window.MOCK[m] !== 'function') return;
      if (window.MOCK['_local_' + m]) return;           // 防止重复包装
      window.MOCK['_local_' + m] = window.MOCK[m];
      window.MOCK[m] = function () {
        // 后端模式且缓存已就绪 → 走后端数据；否则回退本地
        if (online()) return API[m].apply(API, arguments);
        return window.MOCK['_local_' + m].apply(window.MOCK, arguments);
      };
    });
  }

  window.EAT_API = API;
  window.__API_BASE__ = API_BASE;
  window.__API_END__ = END;

  // 关键顺序：先同步预加载把后端数据备好，再包装 MOCK 方法。
  if (enabled) preloadSync();
  install();

  // 若 mock.js 尚未加载完，DOM 就绪后再补一次
  if (!window.MOCK) {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', install);
    } else {
      install();
    }
  }

  // 打印提示，方便在控制台确认状态
  if (!enabled) {
    console.log('%c[api] 本地假数据模式（端：' + END + '）。切后端：地址栏加 ?api=1，或执行 localStorage.setItem("useApi","1");location.reload()',
      'color:#999');
  }
})();
