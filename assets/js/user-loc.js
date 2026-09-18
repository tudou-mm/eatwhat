/**
 * 用户位置（定位）—— 客户端「附近」的距离基准。
 * ==================================================================
 *
 * ## 为什么单独一个文件
 *
 * 距离有两个口径，历史上一直混在一起，这里把它讲清楚：
 *
 *   ┌─────────────────┬──────────────────────────────────────────┐
 *   │ 基准             │ 用在哪                                    │
 *   ├─────────────────┼──────────────────────────────────────────┤
 *   │ **本市中心点**   │ 后端 `DistanceCalculator` / 审核通过时落库 │
 *   │                 │ → shop.distance 是**平台权威值**，不可改    │
 *   ├─────────────────┼──────────────────────────────────────────┤
 *   │ **用户当前位置** │ 客户端「附近」列表排序（本文件）            │
 *   └─────────────────┴──────────────────────────────────────────┘
 *
 * ⚠️ **两者不能混**：`shop.distance` 是存进数据库的权威字段，谁都改不了；
 * 用户位置是**会话级**的，只影响客户端展示与排序。
 * 想让「附近」按用户位置排，正确做法是：
 *   用用户坐标在客户端**重算**一遍距离（`Distance.userKm`），
 *   而不是去改 `shop.distance`。
 *
 * ## 定位能力矩阵（为什么要做降级）
 *
 * | 场景                    | Geolocation API |
 * |------------------------|-----------------|
 * | iOS Safari (https)     | ✅ 会弹授权框    |
 * | iOS Safari (http)      | ❌ 直接拒绝      |  ← **最大坑**
 * | Android Chrome (https) | ✅              |
 * | Android Chrome (http)  | ⚠️ 仅 localhost |
 * | 桌面 Chrome (http)      | ⚠️ 仅 localhost |
 * | 任何环境 + 用户拒绝授权   | ❌ 永久拒绝      |
 *
 * 结论：**没有 https 就没有定位**。所以本模块的四级降级是必需品，不是优化：
 *
 *   L1 `navigator.geolocation` 成功           → 用真实坐标，距离是「到我的位置」
 *   L2 用户拒绝 / 超时 / 非安全上下文          → 退到**上次定位缓存**
 *   L3 有缓存但过期（> 30min）                → 仍用缓存，但标注「位置可能不准」
 *   L4 什么都没有                             → 退到**市中心**，界面上明确告知
 *
 * ⚠️ 无论哪一级，界面上都必须能看出「这个距离是相对什么算的」——
 *    否则用户会觉得数字在骗人。这是本模块存在的主要理由。
 */
(function (global) {
  'use strict';

  var POS_KEY = 'eatwhat_user_pos';        // 缓存：{lat, lng, ts, src}
  var CACHE_TTL_MS = 30 * 60 * 1000;       // 30 分钟
  var TIMEOUT_MS = 8000;                   // 定位超时：手机在室内可能很慢

  // 降级基准：成都天府广场。与后端 DistanceCalculator.CITY_CENTER 保持一致。
  var CITY_CENTER = { lat: 30.6570, lng: 104.0658, name: '成都' };

  var state = {
    pos: null,          // 当前生效坐标 {lat, lng}
    src: '',            // 'gps' | 'cache' | 'city'
    stale: false,       // 缓存是否过期（L3）
    reason: '',         // 降级原因（给界面看的短句）
    requesting: false,
    listeners: []
  };

  function readCache() {
    try {
      var raw = localStorage.getItem(POS_KEY);
      if (!raw) return null;
      var o = JSON.parse(raw);
      if (o && o.lat != null && o.lng != null) return o;
    } catch (e) { /* 隐私模式 / JSON 坏了 —— 都当没有 */ }
    return null;
  }

  function writeCache(lat, lng, src) {
    try {
      localStorage.setItem(POS_KEY, JSON.stringify(
        { lat: lat, lng: lng, ts: Date.now(), src: src || 'gps' }));
    } catch (e) {}
  }

  function isSecure() {
    // localhost 也算安全上下文（Chrome / Firefox 都放行）
    if (global.isSecureContext) return true;
    var h = (global.location && location.hostname) || '';
    return h === 'localhost' || h === '127.0.0.1' || h === '[::1]';
  }

  function emit() {
    for (var i = 0; i < state.listeners.length; i++) {
      try { state.listeners[i](snapshot()); } catch (e) {}
    }
  }

  function snapshot() {
    return {
      lat: state.pos ? state.pos.lat : null,
      lng: state.pos ? state.pos.lng : null,
      src: state.src,
      stale: state.stale,
      reason: state.reason,
      /**
       * 距离基准是不是「用户真实位置」（false = 退到了市中心）。
       *
       * ⚠️ `manual`（用户手动指定的位置）**必须算 true**。它不是「近似值」，
       *    而是用户明确告诉我们的坐标 —— 漏掉它会让界面上明明显示
       *    「已切换到大熊猫基地」，灯却是黄的、标签写「距市中心」，
       *    自相矛盾。踩过一次。
       */
      isUser: state.src === 'gps' || state.src === 'cache' || state.src === 'manual'
    };
  }

  /** 内部：确定性地把 state 设成某个降级级别 */
  function fallback(level, reason) {
    if (level === 'cache') {
      var c = readCache();
      if (c) {
        var age = Date.now() - (c.ts || 0);
        state.pos = { lat: c.lat, lng: c.lng };
        state.src = 'cache';
        state.stale = age > CACHE_TTL_MS;
        state.reason = state.stale
          ? '用的是 ' + Math.round(age / 60000) + ' 分钟前的位置，可能不准'
          : '';
        return true;
      }
      return false;
    }
    // 最低一级：市中心
    state.pos = { lat: CITY_CENTER.lat, lng: CITY_CENTER.lng };
    state.src = 'city';
    state.stale = false;
    state.reason = reason || '无法定位，距离以成都市中心为基准';
    return true;
  }

  /**
   * 请求定位。
   *
   * @param opts.force 跳过缓存，强制重新定位（用户点了「重新定位」）
   * @returns Promise<snapshot> —— **不会 reject**，失败也 resolve 成降级结果。
   *          页面因此永远不用写 .catch，少一大类「没处理导致白屏」的 bug。
   */
  function locate(opts) {
    opts = opts || {};
    if (state.requesting) return state.promise || Promise.resolve(snapshot());

    // 有缓存且不强制刷新 → 先用缓存（L2），同时后台静默更新
    if (!opts.force && !state.pos) {
      if (fallback('cache', '')) {
        state.requesting = true;
        state.promise = new Promise(function (resolve) {
          emit();
          // 有缓存也顺手后台刷新一次，但**不阻塞**界面
          requestGeo(function (ok) { resolve(snapshot()); }, /*silent*/ true);
        });
        return state.promise;
      }
    }

    state.requesting = true;
    state.promise = new Promise(function (resolve) {
      if (!global.navigator || !navigator.geolocation) {
        fallback('cache', '') || fallback('city', '此浏览器不支持定位');
        state.requesting = false; emit(); resolve(snapshot()); return;
      }
      if (!isSecure()) {
        // ⚠️ 这是最常见的一种：手机通过 http 打开页面，浏览器**静默拒绝**，
        //    既不弹授权框也不报错。必须自己判断并给出可操作提示。
        fallback('cache', '') || fallback('city', '需要 https 才能定位');
        state.requesting = false; emit(); resolve(snapshot()); return;
      }
      requestGeo(function () { resolve(snapshot()); }, false);
    });
    return state.promise;
  }

  function requestGeo(done, silent) {
    var finished = false;
    function finish(ok, reason) {
      if (finished) return;
      finished = true;
      state.requesting = false;
      if (!ok) {
        if (!fallback('cache', '')) fallback('city', reason);
      }
      emit();
      done(ok);
    }

    try {
      navigator.geolocation.getCurrentPosition(
        function (p) {
          var lat = p.coords.latitude, lng = p.coords.longitude;
          state.pos = { lat: lat, lng: lng };
          state.src = 'gps';
          state.stale = false;
          state.reason = '';
          writeCache(lat, lng, 'gps');
          finish(true);
        },
        function (err) {
          // 1 = 拒绝授权，2 = 拿不到位置，3 = 超时
          var msg = err && err.code === 1 ? '你拒绝了定位授权，距离以市中心为基准'
                  : err && err.code === 3 ? '定位超时，距离以市中心为基准'
                  : '定位失败，距离以市中心为基准';
          finish(false, msg);
        },
        { enableHighAccuracy: false, timeout: TIMEOUT_MS, maximumAge: silent ? 5 * 60000 : 0 }
      );
    } catch (e) {
      finish(false, '定位调用异常');
    }
  }

  /** 手动把位置设成某个坐标（页面上的「换个区域」用） */
  function setManual(lat, lng, name) {
    state.pos = { lat: Number(lat), lng: Number(lng) };
    state.src = 'manual';
    state.stale = false;
    state.reason = name ? ('已切换到' + name) : '';
    writeCache(state.pos.lat, state.pos.lng, 'manual');
    emit();
    return snapshot();
  }

  /** 清掉缓存与当前状态（调试 / 用户想重新定位） */
  function reset() {
    try { localStorage.removeItem(POS_KEY); } catch (e) {}
    state.pos = null; state.src = ''; state.stale = false; state.reason = '';
    emit();
  }

  /**
   * 两点球面距离（km，一位小数）。
   * 与后端 `DistanceCalculator.haversine` 同口径（R=6371）。
   * ⚠️ 别改成平面近似 —— 成都北纬 30°，东西向会被高估约 15%。
   */
  function km(lat1, lng1, lat2, lng2) {
    if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return null;
    var R = 6371.0, rad = Math.PI / 180;
    var dLat = (lat1 - lat2) * rad;
    var dLng = (lng1 - lng2) * rad;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat2 * rad) * Math.cos(lat1 * rad) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return Math.round(R * 2 * Math.asin(Math.sqrt(a)) * 10) / 10;
  }

  /** 店铺 → 距离（km）。有用户位置就用用户位置，否则用平台存的权威值 */
  function shopKm(shop) {
    if (!shop) return null;
    if (state.pos && shop.lat != null && shop.lng != null) {
      return km(state.pos.lat, state.pos.lng, shop.lat, shop.lng);
    }
    return shop.distance == null ? null : shop.distance;
  }

  function onChange(fn) {
    if (typeof fn === 'function') state.listeners.push(fn);
    return function () {
      var i = state.listeners.indexOf(fn);
      if (i >= 0) state.listeners.splice(i, 1);
    };
  }

  global.UserLoc = {
    locate: locate,
    km: km,
    shopKm: shopKm,
    setManual: setManual,
    reset: reset,
    onChange: onChange,
    state: snapshot,
    cityCenter: CITY_CENTER,
    /** 距离基准的说明文案 —— 界面直接拿去显示，避免各页各写一套 */
    label: function () {
      var s = snapshot();
      if (s.src === 'gps') return '距我的位置';
      if (s.src === 'manual') return '距选定位置';
      if (s.src === 'cache') return s.stale ? '距上次定位（可能不准）' : '距最近一次定位';
      return '距市中心';
    }
  };
})(window);
