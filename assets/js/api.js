/* ==========================================================
   API 适配层
   ----------------------------------------------------------
   作用：把原型的同步假数据（MOCK）平滑换成真后端。

   设计原则（很重要）：
   1. 保持与 MOCK 完全一致的函数签名，所以三端 22 个页面
      **一行代码都不用改**。
   2. 采用「同步预加载 + 内存缓存」：本文件加载时先把后端数据
      拉下来塞进缓存，之后页面里任何 MOCK.getShop(id) 这类
      **同步调用**都直接从缓存读。
   3. 未开启开关或后端未启动时，自动降级回本地假数据，
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

  // ================= 同步请求（见文首说明） =================

  function syncGet(path) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', API_BASE + path, false);   // false = 同步
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.send(null);
    if (xhr.status < 200 || xhr.status >= 300) {
      throw new Error('HTTP ' + xhr.status);
    }
    var j = JSON.parse(xhr.responseText);
    if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
    return j.data;
  }

  /**
   * 同步预加载：把后端数据灌进缓存。
   * 返回 true 表示成功接上后端；false 表示已降级回本地假数据。
   */
  function preloadSync() {
    var t0 = Date.now();
    try {
      // 只打一个聚合接口，请求数直接等于白屏时长，能少则少
      var d = syncGet('/client/bootstrap') || {};
      var cfg = d.config || {};

      cache.shops = d.shops || [];
      cache.dishes = d.dishes || [];
      cache.comments = d.comments || [];
      cache.priceTiers = cfg.priceTiers || [];
      cache.tasteTags = cfg.tasteTags || [];
      cache.cuisines = cfg.cuisines || [];
      cache.loaded = true;

      // 页面里除了调 MOCK.getXxx()，还有直接读 MOCK.shops / MOCK.dishes
      // 数组的地方（平台端数据概览、店铺列表等），这里一并换成后端数据。
      if (window.MOCK) {
        window.MOCK.shops = cache.shops;
        window.MOCK.dishes = cache.dishes;
        window.MOCK.comments = cache.comments;
        if (cache.priceTiers.length) window.MOCK.priceTiers = cache.priceTiers;
        if (cache.tasteTags.length) window.MOCK.tasteTags = cache.tasteTags;
      }

      console.log('%c[api] 后端已接上 ' + API_BASE +
        '  →  %d 店 / %d 菜 / %d 评论，耗时 %dms',
        'color:#FE2C55;font-weight:bold',
        cache.shops.length, cache.dishes.length, cache.comments.length, Date.now() - t0);
      return true;
    } catch (e) {
      console.warn('[api] 后端未就绪，已降级到本地假数据：' + e.message);
      enabled = false;
      cache.loaded = false;
      return false;
    }
  }

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
  function put(path, body) { return send('PUT', path, body); }

  /** 异步预加载：页面若愿意 await，可以拿到最新的后端数据 */
  function preload() {
    if (!enabled) return Promise.resolve(false);
    return Promise.all([get('/admin/shops'), get('/admin/config')])
      .then(function (res) {
        cache.shops = res[0] || [];
        var cfg = res[1] || {};
        cache.priceTiers = cfg.priceTiers || [];
        cache.tasteTags = cfg.tasteTags || [];
        cache.cuisines = cfg.cuisines || [];
        cache.loaded = true;
        return true;
      })
      .catch(function () { return false; });
  }

  // ================= 同步 API（签名与 MOCK 保持一致） =================
  // 注意：回退时一律调 window.MOCK['_local_xxx']，
  // 不能再调 window.MOCK.xxx —— 那会绕回这里形成无限递归。

  var API = {
    /** 是否已接后端 */
    isEnabled: function () { return enabled; },
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
    console.log('%c[api] 本地假数据模式。切后端：地址栏加 ?api=1，或执行 localStorage.setItem("useApi","1");location.reload()',
      'color:#999');
  }
})();
