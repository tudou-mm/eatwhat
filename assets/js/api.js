/* ==========================================================
   API 适配层
   ----------------------------------------------------------
   作用：把原型的同步假数据（MOCK）平滑换成真后端。

   设计原则（很重要）：
   1. 保持与 MOCK 完全一致的函数签名，所以三端 22 个页面
      **一行代码都不用改**。
   2. 采用"预加载 + 内存缓存"：页面启动时先把数据拉下来塞进缓存，
      之后 MOCK.getShop(id) 这类同步调用直接从缓存读。
   3. 未开启开关或后端未启动时，自动降级回本地假数据，
      原型永远能打开。
   ========================================================== */
(function () {
  'use strict';

  var API_BASE = localStorage.getItem('apiBase') || 'http://localhost:8080/api';
  var enabled = localStorage.getItem('useApi') === '1';

  // 缓存区
  var cache = {
    shops: [],
    dishes: [],
    comments: [],
    users: [],
    priceTiers: [],
    tasteTags: [],
    cuisines: [],
    reports: [],
    loaded: false
  };

  function get(path) {
    return fetch(API_BASE + path, { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  function post(path, body) {
    return fetch(API_BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  function put(path, body) {
    return fetch(API_BASE + path, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  /**
   * 预加载：把后端数据灌进本地缓存，之后同步 API 才能工作。
   * 返回 Promise。各页面应在渲染前 await 它。
   */
  function preload() {
    if (!enabled) return Promise.resolve(false);

    return Promise.all([
      get('/admin/shops'),
      get('/admin/config')
    ]).then(function (res) {
      var shops = res[0] || [];
      var cfg = res[1] || {};

      cache.shops = shops;
      cache.priceTiers = cfg.priceTiers || [];
      cache.tasteTags = cfg.tasteTags || [];
      cache.cuisines = cfg.cuisines || [];

      // 菜品需要逐店拉（或调 feed 拿全量）
      var dishJobs = shops.map(function (s) {
        return get('/client/shop/' + s.id + '/dishes').catch(function () { return []; });
      });

      return Promise.all(dishJobs).then(function (lists) {
        var all = [];
        lists.forEach(function (l) { if (Array.isArray(l)) all = all.concat(l); });
        cache.dishes = all;

        // 评论按需加载太碎，这里用各菜品的评论接口并发拉
        var cmtJobs = all.slice(0, 50).map(function (d) {
          return get('/client/dish/' + d.id + '/comments').catch(function () { return []; });
        });
        return Promise.all(cmtJobs).then(function (cs) {
          var ac = [];
          cs.forEach(function (l) { if (Array.isArray(l)) ac = ac.concat(l); });
          cache.comments = ac;
          cache.loaded = true;
          return true;
        });
      });
    }).catch(function (e) {
      console.warn('[api] 预加载失败，降级到本地假数据：', e.message);
      enabled = false;
      return false;
    });
  }

  // ================= 同步 API（签名与 MOCK 保持一致） =================

  var API = {
    /** 是否已接后端 */
    isEnabled: function () { return enabled; },
    preload: preload,
    cache: cache,

    // ---- 基础读取 ----
    getShop: function (id) {
      return cache.shops.find(function (s) { return s.id === id; }) ||
             (window.MOCK ? window.MOCK.getShop(id) : null);
    },
    getDish: function (id) {
      return cache.dishes.find(function (d) { return d.id === id; }) ||
             (window.MOCK ? window.MOCK.getDish(id) : null);
    },
    getShopDishes: function (shopId) {
      var r = cache.dishes.filter(function (d) { return d.shopId === shopId; });
      return r.length ? r : (window.MOCK ? window.MOCK.getShopDishes(shopId) : []);
    },
    getDishComments: function (dishId) {
      var r = cache.comments.filter(function (c) { return c.dishId === dishId; });
      return r.length ? r : (window.MOCK ? window.MOCK.getDishComments(dishId) : []);
    },

    // ---- 纯计算类：本地算即可，不必打接口 ----
    getTierByPrice: function (price) {
      var p = Number(price);
      if (isNaN(p) || p < 0) return null;
      var tiers = cache.priceTiers.length ? cache.priceTiers
                : (window.MOCK ? window.MOCK.priceTiers : []);
      for (var i = 0; i < tiers.length; i++) {
        var t = tiers[i];
        var max = (t.max === -1 || t.max === null) ? Infinity : t.max;
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

    // ---- 写操作：直打后端 ----
    doCheckin: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'checkin' }); },
    like: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'like' }); },
    favorite: function (dishId) { return post('/client/dish/' + dishId + '/action', { type: 'favorite' }); },
    addComment: function (dishId, userId, content) {
      return post('/client/dish/' + dishId + '/comment', { userId: userId, content: content });
    }
  };

  /**
   * 把 API 挂到 window.MOCK 上：页面一律写 MOCK.getShop(id)，
   * 适配层负责内部路由到后端或本地，页面代码无需感知后端是否存在。
   *
   * 注意：必须「立即」执行，不能等 DOMContentLoaded ——
   * 因为页面脚本在 mock.js 之后同步执行，可能马上就调 MOCK.xxx。
   * 缓存未就绪时自动走本地假数据，preload 完成后自动切到后端。
   */
  function install() {
    if (!window.MOCK) return;

    var readMethods = ['getShop', 'getDish', 'getShopDishes', 'getDishComments', 'getTierByPrice'];
    readMethods.forEach(function (m) {
      if (typeof window.MOCK[m] !== 'function') return;
      if (window.MOCK['_local_' + m]) return;           // 防止重复包装
      window.MOCK['_local_' + m] = window.MOCK[m];
      window.MOCK[m] = function () {
        // 后端模式且缓存已就绪 → 走后端数据；否则回退本地
        if (enabled && cache.loaded) {
          return API[m].apply(API, arguments);
        }
        return window.MOCK['_local_' + m].apply(window.MOCK, arguments);
      };
    });
  }

  window.EAT_API = API;
  window.__API_BASE__ = API_BASE;

  // 立即安装（mock.js 已在其之前加载）
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
  if (enabled) {
    console.log('%c[api] 已启用后端模式 → ' + API_BASE, 'color:#FE2C55');
  } else {
    console.log('%c[api] 本地假数据模式。切后端执行：localStorage.setItem("useApi","1");location.reload()',
      'color:#999');
  }
})();
