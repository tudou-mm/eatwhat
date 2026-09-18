/**
 * 地图选点组件（高德 JS API 2.0）。
 *
 * ============================ 为什么是「弹层选点」而不是「内嵌地图」 ============================
 * 页面上原来那块 .map-box 是原型期的**灰底占位图**（CSS 画的网格 + 一个 📍）。
 * 真接 SDK 时有两种做法：
 *   A. 把内嵌的 .map-box 换成真地图容器 —— 好看，但地图会跟着页面滚动一起动，
 *      拖拽图钉时页面也在滚，手机上基本没法用；而且页面首屏就付加载成本。
 *   B. 点「地图选点」弹一个全屏遮罩层，层内是真地图 —— 层锁住滚动，
 *      选完关掉、把结果写回页面。
 * 选 B。页面上的 .map-box 保留，改成**选点结果卡**（显示地址 + 坐标 + 改一下），
 * 这样「没选点」和「选了点」在视觉上立刻分得出来。
 *
 * ================================ 关于 Key：没有 Key 也能用 ================================
 * 高德 SDK 需要**用户自己去申请**一个 Web 端 Key（免费，见 README）。
 * 但这是个演示项目，不能让「没配 Key」等于「页面打不开」，所以有三层降级：
 *
 *   L1  配了 Key 且 SDK 加载成功  → 真地图，拖拽 / 点击选点 + 逆地理编码出地址
 *   L2  SDK 加载失败（网络 / 代理阻断 / 8s 超时）→ 退到**内置坐标库 + 关键词搜索**
 *   L3  啥都没有                  → 退到**候选地址列表**（仪征 40 个真实地标）
 *
 * L2/L3 都基于同一份内置坐标库，选出来的 lat/lng 是**真实坐标**，
 * 能真的参与后端距离计算 —— 不是「假数据假装能用」。
 *
 * ================================ 怎么配 Key ================================
 *
 * 读取优先级（高 → 低），任何一条命中即可：
 *   1. window.__EATWHAT_AMAP_KEY__   页面内联注入，**启动最早**，无闪烁
 *   2. localStorage['eatwhat_amap_key']  控制台一句 MapPicker.setKey('xxx') 即可
 *   3. window.__EATWHAT_AMAP_KEYS__   多 Key 轮询池（见下）
 *   4. window.__EATWHAT_AMAP_CFG__.keys  配置文件 assets/data/amap-key.js（不随 git 走）
 *   5. DEFAULT_KEY                   本文件内置兜底
 *
 * ⚠️ **Key 不要提交到公开仓库** —— 高德会按 Key 维度统计配额，泄露等于把额度送人。
 * 所以正式 Key 放 `assets/data/amap-key.js`（已在 .gitignore 里），本文件只留占位。
 *
 * ⚠️ **Key 的平台类型必须对**，否则症状极其迷惑：
 *   「Web服务」类型的 Key → 地图瓦片照渲染（不走平台校验），
 *   但 JS API 的逆地理编码走 `platform=JS` 会被拒 → `10009 USERKEY_PLAT_NOMATCH`。
 *   现象就是「**地图好好的，就是拿不到地址**」。
 *   这不是域名白名单问题（很多人会这么误判），是 Key 类型不匹配。
 *
 *   ✅ **本项目现状**：已配好「Web端(JS API)」Key + 安全密钥，官方链路直通
 *      （`regeoSource()` 返回 `'sdk'`）。REST 兜底因此关掉了。
 *      `restFallback` 仅在使用「Web服务」类型 Key 时才有意义，别搞混。
 *
 *   🔑 **两个值别搞反**：`keys[]` 是控制台的「Key」，`securityJsCode` 是**另**一个
 *      「安全密钥」，两者不同。安全密钥**不是**申请 Key 时填的「应用私钥」。
 *      填错 → 10008 INVALID_USER_SCODE。
 *
 * 多 Key 池（`__EATWHAT_AMAP_KEYS__`）：高德个人免费 Key 有日配额上限，
 * 演示时可能被打满。配成数组后，某个 Key 加载失败会自动换下一个重试：
 *   window.__EATWHAT_AMAP_KEYS__ = ['key1', 'key2'];
 */
(function (global) {
  'use strict';

  var DEFAULT_KEY = '';           // 留空 = 不预置 Key（正式 Key 放 assets/data/amap-key.js）
  /**
   * 相对站点**根**的配置文件路径。
   * ⚠️ 必须是根绝对路径（前导 `/`）。写成 'assets/data/amap-key.js' 的话，
   * 在 /merchant/apply.html 这种子目录页面里会解析成
   * /merchant/assets/data/amap-key.js → 404，兜底加载**永远静默失败**。
   * 页面里的 <script src="../assets/..."> 掩盖了这个问题，所以特别难发现。
   */
  var KEY_FILE = '/assets/data/amap-key.js';
  var KEY_STORAGE = 'eatwhat_amap_key';
  var SCODE_STORAGE = 'eatwhat_amap_scode';   // 安全密钥（JS API 类型 Key 才有）
  var SDK_TIMEOUT = 8000;         // SDK 加载超时：沙箱里 https://webapi.amap.com 常被拦，
                                  // 8 秒还没 onload 就降级，不能无限等下去
  var DEFAULT_CENTER = [119.1845, 32.2728];   // 仪征市中心（注意高德是 [lng, lat]！）
  var DEFAULT_ZOOM = 15;

  /**
   * 内置真实坐标库 —— L2/L3 降级时的数据源。
   *
   * 全部是仪征真实地标 / 商圈，坐标取自本平台的 POI 采集库
   * （{@code yizheng-food-radar}，高德 GCJ-02，与店铺坐标同源），
   * 不是从别处抄来的近似值。
   *
   * 用真实坐标而不是「随便编一个数」的原因：这些值会被提交到后端当 lat/lng，
   * 后端拿它算 distance 并落到排序里 —— 编的坐标会让「附近」排序看起来是坏的。
   *
   * ⚠️ 换城市时这张表要整体换掉，别只改 DEFAULT_CENTER ——
   * 那样地图飞到仪征、候选列表却全在别的城市，是最容易被忽略的分叉。
   */
  var PLACES = [
    { name: '仪征市中心（鼓楼）',   addr: '仪征市真州镇鼓楼广场',             lat: 32.2728, lng: 119.1845 },
    { name: '国庆路商业步行街',     addr: '仪征市真州镇国庆路',               lat: 32.2755, lng: 119.1802 },
    { name: '时代广场',            addr: '仪征市真州镇时代广场',             lat: 32.2748, lng: 119.1811 },
    { name: '宝能环球汇',          addr: '仪征市真州镇解放东路 828 号',       lat: 32.2784, lng: 119.2074 },
    { name: '怡华广场',            addr: '仪征市长江东路 59 号',             lat: 32.2808, lng: 119.1552 },
    { name: '浦东商场',            addr: '仪征市环南路 59 号',               lat: 32.2837, lng: 119.1508 },
    { name: '扬子公园',            addr: '仪征市人民街与园林巷交叉口',        lat: 32.2692, lng: 119.1776 },
    { name: '仪征捺山地质公园',     addr: '仪征市月塘镇环山路 1 号',           lat: 32.4179, lng: 119.1525 },
    { name: '梨香园',              addr: '仪征市谢集尹山村',                 lat: 32.4294, lng: 119.1203 },
    { name: '园博园',              addr: '仪征市铜长路与汉金路交叉口',        lat: 32.3363, lng: 119.0941 },
    { name: '月塘镇中心',          addr: '仪征市月塘镇雨花路',               lat: 32.4086, lng: 119.0826 },
    { name: '新集镇中心',          addr: '仪征市新集镇人民南路',             lat: 32.3263, lng: 119.2994 },
    { name: '马集镇中心（岔镇）',   addr: '仪征市马集镇岔镇村',               lat: 32.2533, lng: 119.0713 },
    { name: '大仪镇中心',          addr: '仪征市大仪镇',                     lat: 32.4240, lng: 119.2050 },
    { name: '陈集镇中心',          addr: '仪征市陈集镇',                     lat: 32.3900, lng: 119.2450 },
    { name: '刘集镇中心',          addr: '仪征市刘集镇',                     lat: 32.3350, lng: 119.2150 },
    { name: '新城镇中心',          addr: '仪征市新城镇',                     lat: 32.2950, lng: 119.2400 },
    { name: '青山镇中心',          addr: '仪征市青山镇',                     lat: 32.2450, lng: 119.1200 },
    { name: '十二圩街道',          addr: '仪征市十二圩',                     lat: 32.2350, lng: 119.2200 },
    { name: '胥浦街道',            addr: '仪征市胥浦街',                     lat: 32.2880, lng: 119.1450 },
    { name: '仪化生活区',          addr: '仪征市仪化生活区',                 lat: 32.2860, lng: 119.1560 },
    { name: '真州镇人民政府',       addr: '仪征市真州镇',                     lat: 32.2720, lng: 119.1830 },
    { name: '仪征市人民医院',       addr: '仪征市真州镇',                     lat: 32.2760, lng: 119.1900 },
    { name: '仪征中学',            addr: '仪征市江城路',                     lat: 32.2604, lng: 119.2126 },
    { name: '精诚高级中学',         addr: '仪征市北城河东路',                 lat: 32.2769, lng: 119.1846 },
    { name: '仪征市实验小学',       addr: '仪征市真州镇',                     lat: 32.2759, lng: 119.1810 },
    { name: '仪征市第二中学',       addr: '仪征市真州镇',                     lat: 32.2710, lng: 119.1780 },
    { name: '仪征市图书馆',         addr: '仪征市真州镇',                     lat: 32.2740, lng: 119.1850 },
    { name: '仪征市体育中心',       addr: '仪征市真州镇',                     lat: 32.2690, lng: 119.1920 },
    { name: '万年集贸市场',         addr: '仪征市真州镇万年村',               lat: 32.2784, lng: 119.1568 },
    { name: '红叶农贸市场',         addr: '仪征市沿山河东路 6 号',             lat: 32.2857, lng: 119.1793 },
    { name: '四方城',              addr: '仪征市环南路与滨河东路交叉口',      lat: 32.2836, lng: 119.1491 },
    { name: '龙山风景区',          addr: '仪征市青山镇龙山',                 lat: 32.2680, lng: 119.0950 },
    { name: '铜山森林公园',         addr: '仪征市铜山',                       lat: 32.3060, lng: 119.1080 },
    { name: '石桥河公园',          addr: '仪征市石桥河沿岸',                 lat: 32.2810, lng: 119.1690 },
    { name: '仪扬河景观带',         addr: '仪征市仪扬河沿岸',                 lat: 32.2700, lng: 119.1750 },
    { name: '仪征汽车客运站',       addr: '仪征市真州镇',                     lat: 32.2790, lng: 119.1990 },
    { name: '红山体育公园',         addr: '仪征市铜山',                       lat: 32.3120, lng: 119.1150 },
    { name: '登月湖',              addr: '仪征市谢集',                       lat: 32.4400, lng: 119.1350 },
    { name: '仪征市广电站',         addr: '仪征市真州镇解放东路',             lat: 32.2721, lng: 119.1871 }
  ];

  var state = {
    sdkLoading: null,      // Promise | null —— 缓存加载中/已完成的 Promise，避免重复插脚本
    sdkReady: false,
    sdkFailed: false,
    el: null,              // 弹层根节点
    map: null,
    marker: null,
    geocoder: null,
    onPick: null,          // 当前弹层的确认回调
    picked: null,          // 当前弹层里已选的 {lat, lng, address, addressResolved}
    mode: 'list',          // 'map' | 'search' | 'list'
    keys: [],              // 可用 Key 池（去重后）
    key: '',               // 当前使用的 Key
    keyIndex: 0,           // 当前 Key 在池中的下标（失败后 +1 轮询）
    keyFileTried: false,   // 配置文件只尝试补载一次
    keyLocked: false,      // setKey() 明确指定后不再从配置回填（见 setKey）
    securityCode: '',      // 会话内 setSecurityCode() 的覆盖值
    lastGeoError: '',      // 上一段失败链路的错误码（诊断用）
    lastRestError: '',     // REST 兜底那条链路的错误码
    regeoSource: ''        // 'sdk' | 'rest' | '' —— 地址是从哪条链路拿到的
  };

  // ============================ Key ============================

  /**
   * 多 Key 池 —— 高德免费 Key 有日配额，某个 Key 打满了就换下一个。
   * 收集顺序：内联数组 → 配置文件 → 单 Key 各处来源。
   */
  function readKeys() {
    var out = [];

    function push(v) {
      v = String(v == null ? '' : v).trim();
      if (v && out.indexOf(v) < 0) out.push(v);
    }

    if (global.__EATWHAT_AMAP_KEYS__ && global.__EATWHAT_AMAP_KEYS__.length) {
      for (var i = 0; i < global.__EATWHAT_AMAP_KEYS__.length; i++) {
        push(global.__EATWHAT_AMAP_KEYS__[i]);
      }
    }
    if (global.__EATWHAT_AMAP_KEYS_FILE__ && global.__EATWHAT_AMAP_KEYS_FILE__.length) {
      for (var j = 0; j < global.__EATWHAT_AMAP_KEYS_FILE__.length; j++) {
        push(global.__EATWHAT_AMAP_KEYS_FILE__[j]);
      }
    }
    // 结构化配置（assets/data/amap-key.js 现在写这个）—— keys 只是其中一个字段
    var cfg = global.__EATWHAT_AMAP_CFG__;
    if (cfg && cfg.keys && cfg.keys.length) {
      for (var m = 0; m < cfg.keys.length; m++) push(cfg.keys[m]);
    }
    push(global.__EATWHAT_AMAP_KEY__);
    try { push(localStorage.getItem(KEY_STORAGE)); } catch (e) { /* 隐私模式禁用，继续兜底 */ }
    push(DEFAULT_KEY);

    return out;
  }

  function readKey() {
    var ks = readKeys();
    return ks.length ? ks[0] : '';
  }

  /**
   * 补载配置文件 `assets/data/amap-key.js`（若存在）。
   *
   * **为什么要费这个劲**：正式 Key 不能提交到公开仓库，但又要「拉下来就能用」。
   * 所以 Key 单独放一个文件、由 .gitignore 排除，运行时补一个 <script> 把它读进来。
   * 文件内容形如：
   *   window.__EATWHAT_AMAP_KEYS_FILE__ = ['3cb3a5...', '...'];
   *
   * 同步 XHR 而非动态 <script>：同源本地文件，几毫秒就回，
   * 而且能保证「打开弹层前 Key 已就位」，不会先闪一下降级态。
   * 文件不存在 → 静默跳过（这正是没配 Key 的正常情况）。
   */
  function ensureKeyFile() {
    if (state.keyFileTried) return;
    state.keyFileTried = true;
    if (global.__EATWHAT_AMAP_KEYS_FILE__) return;    // 已在 <head> 里同步引过了

    try {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', KEY_FILE, false);               // 同步：见上面的理由
      xhr.send(null);
      if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
        // 用 new Function 而不是 eval：作用域干净，且不会误改本函数局部变量
        (new Function(xhr.responseText))();
      }
    } catch (e) { /* 文件不存在 / file:// 协议禁用同步 XHR —— 都属正常，继续走降级 */ }
  }

  /**
   * 运行时配 Key。配完会重置 SDK 状态，下次打开弹层重新尝试加载地图。
   * 让用户在控制台里一句 MapPicker.setKey('xxx') 就能用上真地图，
   * 不用改文件、不用重新打包 —— 演示前临时补 Key 很方便。
   *
   * ⚠️ 调用后进入「锁定」状态：即使配置文件里还有 Key 也不会再被回填。
   *   所以 `setKey('')` = 明确表示「我要无 Key 模式」（走内置地址库降级），
   *   而不是「清一下 localStorage，然后被文件里的 Key 再接回来」。
   *   需要解锁就 `MapPicker.setKey(null)` 或刷新页面。
   */
  function setKey(key) {
    if (key === null || key === undefined) {           // 显式传 null = 解锁
      try { localStorage.removeItem(KEY_STORAGE); } catch (e) {}
      state.keyLocked = false;
      state.keys = [];
      state.keyIndex = 0;
      state.key = '';
      resetSdk();
      return '';
    }
    var v = String(key).trim();
    try {
      if (v) localStorage.setItem(KEY_STORAGE, v);
      else localStorage.removeItem(KEY_STORAGE);
    } catch (e) {}
    state.keys = [];
    state.keyIndex = 0;
    state.key = v;
    state.keyLocked = true;
    resetSdk();
    return state.key;
  }

  /** 重置 SDK 加载状态，让下一次 open 重新尝试（换 Key 后必须清） */
  function resetSdk() {
    state.sdkLoading = null;
    state.sdkReady = false;
    state.sdkFailed = false;
    state.geocoder = null;
    state.marker = null;
    state.map = null;
    state.lastGeoError = '';
    state.lastRestError = '';
    state.regeoSource = '';
    // 安全密钥挂在全局上，换 Key 时必须一并清掉 —— 否则新 Key 会带着旧 Key 的
    // securityJsCode 去初始化，报 10008，而且看起来跟 Key 本身无关，极难排查。
    if (global._AMapSecurityConfig) {
      try { delete global._AMapSecurityConfig; } catch (e) { global._AMapSecurityConfig = undefined; }
    }
    if (global.AMap) { try { delete global.AMap; } catch (e) { global.AMap = undefined; } }
  }

  /**
   * 配安全密钥（只有「Web端(JS API)」类型的 Key 才有）。
   * 控制台一句 `MapPicker.setSecurityCode('xxx')` 即可，不用改文件。
   */
  function setSecurityCode(code) {
    var v = String(code == null ? '' : code).trim();
    state.securityCode = v;
    try { localStorage.setItem(SCODE_STORAGE, v); } catch (e) {}
    resetSdk();
    return v;
  }

  function ensureKeys() {
    ensureKeyFile();
    if (!state.keys || !state.keys.length) state.keys = readKeys();
    // setKey() 明确指定过 Key（含「明确指定为无」）后就不再回填，见 setKey 的注释
    if (!state.keyLocked) {
      if (!state.key) {
        state.key = state.keys.length ? state.keys[state.keyIndex] || state.keys[0] : '';
      }
    }
    return state.keys;
  }

  function hasKey() { ensureKeys(); return !!state.key; }

  // ---------- 安全密钥 / 兜底开关（都来自同一个配置文件） ----------

  /**
   * 安全密钥 securityJsCode —— 只有「Web端(JS API)」类型的 Key 才有。
   * 优先级：本次会话 setSecurityCode() 设的 → 内联变量 → 配置对象 → localStorage。
   * 会话内的 runtime 覆盖放最前，是为了让控制台调试能立刻生效。
   */
  function readSecurityCode() {
    var v = state.securityCode;
    if (!v) {
      v = global.__EATWHAT_AMAP_SECURITY__;
      if (!v && global.__EATWHAT_AMAP_CFG__) v = global.__EATWHAT_AMAP_CFG__.securityJsCode;
      if (!v) { try { v = localStorage.getItem(SCODE_STORAGE); } catch (e) {} }
    }
    return String(v == null ? '' : v).trim();
  }

  /**
   * 兜底：Geocoder 插件失败时改走 REST 直连。
   *
   * **为什么需要它**：高德把 Key 分成「Web端(JS API)」和「Web服务」两种平台类型。
   * 用「Web服务」类型的 Key 时，地图瓦片照渲染（不走平台校验），
   * 但 JS API 的逆地理编码走 `platform=JS` 会被拒 → `10009 USERKEY_PLAT_NOMATCH`。
   * 于是出现最迷惑的现象：**地图好好的，就是拿不到地址**。
   * 而同一个 Key 直接请求 REST 接口却是通的（返回 ACAO: *，浏览器可直连），
   * 所以这条路能让功能立刻可用，不必先去控制台重建 Key。
   */
  function restFallbackEnabled() {
    var cfg = global.__EATWHAT_AMAP_CFG__;
    if (cfg && typeof cfg.restFallback === 'boolean') return cfg.restFallback;
    if (typeof global.__EATWHAT_AMAP_REST_FALLBACK__ === 'boolean') {
      return global.__EATWHAT_AMAP_REST_FALLBACK__;
    }
    return true;    // 默认开：拿不到地址的体验比「多一次请求」糟糕得多
  }

  /**
   * REST 直连逆地理编码（不依赖 AMap SDK / Geocoder 插件）。
   * 成功回调 `{address, district, city}`，失败回调 `{error: infocode}`。
   */
  function restRegeo(lng, lat, ok, fail) {
    var url = 'https://restapi.amap.com/v3/geocode/regeo'
            + '?key=' + encodeURIComponent(state.key)
            + '&location=' + encodeURIComponent(lng + ',' + lat)
            + '&extensions=base&radius=200';

    function handle(text) {
      var j;
      try { j = JSON.parse(text); } catch (e) { fail('bad-json'); return; }
      if (j && j.status === '1' && j.regeocode) {
        var rg = j.regeocode;
        var ac = rg.addressComponent || {};
        // ⚠️ **字段名在 REST 和 SDK 里不一样**，这里踩过一次：
        //    JS SDK 的 Geocoder 回调给的是 `formattedAddress`（驼峰），
        //    而 REST 接口原始响应给的是 `formatted_address`（下划线）。
        //    照 SDK 的名字去 REST 里取 → 永远 undefined → 表现为
        //    「接口明明返回 200 和完整地址，界面上却始终是空的」。
        //    两个都认，别只写一个。
        var addr = rg.formatted_address || rg.formattedAddress || '';
        ok({
          address: addr,
          district: ac.district || '',
          city: ac.city || ac.province || ''
        });
      } else {
        fail((j && (j.infocode || j.info)) || 'unknown');
      }
    }

    try {
      if (global.fetch) {
        fetch(url).then(function (r) { return r.text(); })
                  .then(handle)
                  .catch(function () { fail('network'); });
        return;
      }
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) handle(xhr.responseText);
        else fail('http-' + xhr.status);
      };
      xhr.onerror = function () { fail('network'); };
      xhr.send(null);
    } catch (e) {
      fail('exception');
    }
  }

  // ============================ SDK 加载 ============================

  /**
   * 加载高德 SDK，**失败自动换下一个 Key 重试**（多 Key 池）。
   *
   * 为什么要轮询：高德个人免费 Key 有日配额，演示/联调时很容易打满，
   * 表现是 SDK 能下载但地图不渲染、或直接 403。这时换一个 Key 通常就好了。
   * 池里只有一个 Key 时行为跟以前完全一样（试一次，不行就降级）。
   */
  function loadSdk() {
    if (state.sdkReady) return Promise.resolve(true);
    if (state.sdkLoading) return state.sdkLoading;
    ensureKeys();
    if (!state.key) { state.sdkFailed = true; return Promise.resolve(false); }

    state.sdkLoading = new Promise(function (resolve) {
      // ⚠️ setKey() 显式指定的 Key 必须**压过**配置池。
      // 否则「用假 Key 验证降级」会变成：state.keys 被配置文件回填成真 Key，
      // 于是拿真 Key 顺利加载出地图，假 Key 根本没被用过 —— 测试假绿。
      var keys = (state.keyLocked && state.key)
        ? [state.key]
        : (state.keys.length ? state.keys : [state.key]);
      var start = state.keyIndex;
      var tried = 0;

      function tryKey(i) {
        var key = keys[i];
        var timer = null;
        var done = false;
        var s = document.createElement('script');

        function settle(ok) {
          if (done) return;
          done = true;
          if (timer) clearTimeout(timer);
          if (s.parentNode) s.parentNode.removeChild(s);   // 失败的那条别留在 head 里

          if (!ok && tried < keys.length - 1) {
            tried++;
            // 换 Key 前先清干净：AMap 可能是上一次残留的半成品
            if (global.AMap) { try { delete global.AMap; } catch (e) { global.AMap = undefined; } }
            tryKey((i + 1) % keys.length);
            return;
          }

          state.key = key;
          state.keyIndex = i;
          state.sdkReady = ok;
          state.sdkFailed = !ok;
          resolve(ok);
        }

        // 沙箱里 https://webapi.amap.com 常被代理拦，onerror 不一定触发（会挂起），
        // 所以事件 + 超时双保险，哪条先到算哪条。
        // 注意：onload 还必须再判一次 global.AMap —— CDN 返回 200 但内容是错误页时
        // onload 照样触发，AMap 却是 undefined，只监听事件会漏。
        // ⚠️ `plugin=AMap.Geocoder` 不能省。
        // 高德 2.0 把逆地理编码拆成独立插件，主包**不含** `AMap.Geocoder`，
        // 不声明的话 `new AMap.Geocoder(...)` 会抛
        // `AMap.Geocoder is not a constructor` —— 而地图本身、Marker、事件全正常，
        // 所以现象是「地图能显示但一选点就炸」。踩过一次，别删。
        //
        // ⚠️ `_AMapSecurityConfig` 必须在脚本**执行前**挂到 window 上。
        // 高德在 SDK 初始化时读一次这个全局变量；脚本跑完再设就晚了。
        // 只有「Web端(JS API)」类型的 Key 才有安全密钥，没有就跳过（不影响地图渲染）。
        var scode = readSecurityCode();
        if (scode && !global._AMapSecurityConfig) {
          global._AMapSecurityConfig = { securityJsCode: scode };
        }

        s.src = 'https://webapi.amap.com/maps?v=2.0'
              + '&key=' + encodeURIComponent(key)
              + '&plugin=AMap.Geocoder';
        s.async = true;
        s.onload = function () { settle(!!global.AMap); };
        s.onerror = function () { settle(false); };
        document.head.appendChild(s);

        timer = setTimeout(function () { settle(!!global.AMap); }, SDK_TIMEOUT);
      }

      tryKey(start % keys.length);
    });

    return state.sdkLoading;
  }

  // ============================ 弹层骨架 ============================

  var CSS = [
    '.mk-mask{position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:9998;display:flex;align-items:center;justify-content:center;padding:20px}',
    '.mk-panel{background:#fff;border-radius:16px;width:100%;max-width:720px;max-height:88vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 20px 60px rgba(0,0,0,.3)}',
    '.mk-head{display:flex;align-items:center;justify-content:space-between;padding:16px 20px;border-bottom:1px solid #EFF1F4;flex-shrink:0}',
    '.mk-title{font-size:16px;font-weight:600;color:#1A1D24}',
    '.mk-close{width:30px;height:30px;border-radius:50%;border:0;background:#F3F5F8;color:#646A73;font-size:18px;line-height:1;cursor:pointer}',
    '.mk-close:hover{background:#E8EBF0}',
    '.mk-body{flex:1;min-height:0;display:flex;flex-direction:column;position:relative}',
    '.mk-map{width:100%;height:340px;background:#E8EEF4;flex-shrink:0}',
    '.mk-tip{padding:9px 20px;background:#FFF8E6;color:#8A6100;font-size:12px;border-bottom:1px solid #FFEFC2;flex-shrink:0}',
    '.mk-tip b{color:#664A00}',
    '.mk-warn{padding:9px 20px;background:#FFF1F0;color:#A8170F;font-size:12px;line-height:1.5;border-bottom:1px solid #FFD7D4;flex-shrink:0}',
    '.mk-search{padding:12px 20px;display:flex;gap:8px;flex-shrink:0;border-bottom:1px solid #EFF1F4}',
    '.mk-input{flex:1;height:38px;border:1px solid #E3E6EB;border-radius:9px;padding:0 12px;font-size:13px;color:#1A1D24;outline:none;background:#fff}',
    '.mk-input:focus{border-color:#FE2C55}',
    '.mk-btn{height:38px;padding:0 18px;border:0;border-radius:9px;background:#FE2C55;color:#fff;font-size:13px;font-weight:600;cursor:pointer;flex-shrink:0}',
    '.mk-btn:hover{opacity:.9}',
    '.mk-btn--ghost{background:#F3F5F8;color:#41464D;font-weight:500}',
    '.mk-list{flex:1;min-height:140px;max-height:300px;overflow-y:auto;padding:6px 0}',
    '.mk-item{padding:11px 20px;cursor:pointer;border-bottom:1px solid #F6F7F9}',
    '.mk-item:hover{background:#F7F8FA}',
    '.mk-item__n{font-size:14px;color:#1A1D24;font-weight:500}',
    '.mk-item__a{font-size:12px;color:#8A9099;margin-top:3px}',
    '.mk-item__c{font-size:11px;color:#B0B5BD;margin-top:3px;font-family:ui-monospace,Menlo,monospace}',
    '.mk-empty{padding:34px 20px;text-align:center;color:#8A9099;font-size:13px}',
    '.mk-foot{display:flex;align-items:center;gap:12px;padding:14px 20px;border-top:1px solid #EFF1F4;flex-shrink:0}',
    '.mk-picked{flex:1;min-width:0}',
    '.mk-picked__a{font-size:13px;color:#1A1D24;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.mk-picked__c{font-size:11px;color:#8A9099;margin-top:2px;font-family:ui-monospace,Menlo,monospace}',
    '.mk-picked--none{color:#B0B5BD;font-size:13px}',
    '.mk-picked__none{color:#B0B5BD}',
    '.mk-badge{display:inline-block;padding:2px 7px;border-radius:5px;font-size:10px;font-weight:600;margin-left:6px;vertical-align:1px}',
    '.mk-badge--real{background:#E6F7EE;color:#0E8A4A}',
    '.mk-badge--lite{background:#FFF3E0;color:#B26A00}',
    '@media(max-width:640px){.mk-mask{padding:0}.mk-panel{max-width:none;height:100%;max-height:none;border-radius:0}.mk-map{height:260px}}'
  ].join('');

  function injectStyle() {
    if (document.getElementById('mk-style')) return;
    var st = document.createElement('style');
    st.id = 'mk-style';
    st.textContent = CSS;
    document.head.appendChild(st);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function fmtCoord(lat, lng) {
    return Number(lat).toFixed(6) + ', ' + Number(lng).toFixed(6);
  }

  // ============================ 距离（仅用于列表排序，与后端同公式） ============================

  function kmFrom(lat, lng, clat, clng) {
    var R = 6371.0, rad = Math.PI / 180;
    var dLat = (clat - lat) * rad, dLng = (clng - lng) * rad;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat * rad) * Math.cos(clat * rad) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return R * 2 * Math.asin(Math.sqrt(a));
  }

  /** 按关键词筛内置坐标库；空关键词返回全部（按距市中心近→远排，常见的排前面） */
  function searchPlaces(kw) {
    var q = String(kw || '').trim();
    var list = PLACES.slice();
    if (q) {
      list = list.filter(function (p) {
        return p.name.indexOf(q) >= 0 || p.addr.indexOf(q) >= 0;
      });
    } else {
      list.sort(function (a, b) {
        return kmFrom(a.lat, a.lng, DEFAULT_CENTER[1], DEFAULT_CENTER[0]) -
               kmFrom(b.lat, b.lng, DEFAULT_CENTER[1], DEFAULT_CENTER[0]);
      });
    }
    return list;
  }

  // ============================ 弹层渲染 ============================

  function buildShell() {
    var mask = document.createElement('div');
    mask.className = 'mk-mask';
    mask.innerHTML =
      '<div class="mk-panel" role="dialog" aria-modal="true" aria-label="选择店铺位置">' +
        '<div class="mk-head">' +
          '<div class="mk-title">选择店铺位置</div>' +
          '<button class="mk-close" type="button" aria-label="关闭">×</button>' +
        '</div>' +
        '<div class="mk-body"></div>' +
        '<div class="mk-foot">' +
          '<div class="mk-picked mk-picked--none">尚未选点</div>' +
          '<button class="mk-btn mk-btn--ghost" type="button" data-act="cancel">取消</button>' +
          '<button class="mk-btn" type="button" data-act="ok" disabled>确认选点</button>' +
        '</div>' +
      '</div>';

    // 点遮罩关闭：只有点在遮罩本体上才算，点面板内部不算（拖地图时容易甩到边缘）
    mask.addEventListener('mousedown', function (e) {
      if (e.target === mask) close();
    });
    mask.addEventListener('click', function (e) {
      var act = e.target.getAttribute && e.target.getAttribute('data-act');
      if (e.target.classList && e.target.classList.contains('mk-close')) { close(); return; }
      if (act === 'cancel') { close(); return; }
      if (act === 'ok') { confirmPick(); return; }
    });

    document.body.appendChild(mask);
    return mask;
  }

  function renderFoot() {
    var box = state.el.querySelector('.mk-picked');
    var okBtn = state.el.querySelector('[data-act="ok"]');
    if (!state.picked) {
      box.className = 'mk-picked mk-picked--none';
      box.textContent = '尚未选点';
      okBtn.disabled = true;
      return;
    }
    box.className = 'mk-picked';
    box.innerHTML =
      '<div class="mk-picked__a">' +
        (state.picked.address
          ? esc(state.picked.address)
          : '<span class="mk-picked__none">未解析到地址 —— 确认后请手动填写详细地址</span>') +
      '</div>' +
      '<div class="mk-picked__c">' + fmtCoord(state.picked.lat, state.picked.lng) + '</div>';
    okBtn.disabled = false;
  }

  function setPicked(lat, lng, address) {
    state.picked = {
      lat: Number(lat),
      lng: Number(lng),
      address: address || '',
      // 让页面能区分「有真地址」和「只有坐标」—— 别把兜底文案写进商家的地址输入框
      addressResolved: !!address
    };
    renderFoot();
  }

  function confirmPick() {
    if (!state.picked) return;
    var cb = state.onPick, val = state.picked;
    close();
    if (cb) cb(val);
  }

  function close() {
    if (state.el && state.el.parentNode) state.el.parentNode.removeChild(state.el);
    state.el = null;
    state.map = null;
    state.marker = null;
    state.geocoder = null;
    state.onPick = null;
    state.picked = null;
    document.removeEventListener('keydown', onEsc);
  }

  function onEsc(e) { if (e.key === 'Escape') close(); }

  // ---------- 三种模式的 body ----------

  function renderMapBody(body) {
    body.innerHTML =
      '<div class="mk-tip">拖动地图或点击地图选点 <b>·</b> 已接入高德地图</div>' +
      '<div class="mk-map" id="mk-map"></div>';
  }

  function renderLiteBody(body, reason) {
    var items = searchPlaces('').map(function (p, i) {
      return '<div class="mk-item" data-i="' + i + '">' +
               '<div class="mk-item__n">' + esc(p.name) + '</div>' +
               '<div class="mk-item__a">' + esc(p.addr) + '</div>' +
               '<div class="mk-item__c">' + fmtCoord(p.lat, p.lng) + '</div>' +
             '</div>';
    }).join('');

    body.innerHTML =
      '<div class="mk-tip">' + esc(reason) + ' 已切到<b>内置地址库</b>：搜关键词或直接从下面 40 个仪征地标里选，坐标是真实的，能参与后端距离计算。</div>' +
      '<div class="mk-search">' +
        '<input class="mk-input" id="mk-kw" placeholder="搜地标、商圈、街道，如「春熙路」「武侯」">' +
        '<button class="mk-btn" type="button" id="mk-go">搜索</button>' +
      '</div>' +
      '<div class="mk-list" id="mk-list">' + items + '</div>';

    var list = body.querySelector('#mk-list');
    var kw = body.querySelector('#mk-kw');

    function rerender() {
      var found = searchPlaces(kw.value);
      if (!found.length) {
        list.innerHTML = '<div class="mk-empty">没找到「' + esc(kw.value) + '」<br>换个词试试，或直接清空看全部 40 个地标</div>';
        return;
      }
      list.innerHTML = found.map(function (p) {
        var i = PLACES.indexOf(p);
        return '<div class="mk-item" data-i="' + i + '">' +
                 '<div class="mk-item__n">' + esc(p.name) + '</div>' +
                 '<div class="mk-item__a">' + esc(p.addr) + '</div>' +
                 '<div class="mk-item__c">' + fmtCoord(p.lat, p.lng) + '</div>' +
               '</div>';
      }).join('');
    }

    kw.addEventListener('input', rerender);
    kw.addEventListener('keydown', function (e) { if (e.key === 'Enter') rerender(); });
    body.querySelector('#mk-go').addEventListener('click', rerender);

    list.addEventListener('click', function (e) {
      var it = e.target.closest ? e.target.closest('.mk-item') : null;
      if (!it) return;
      var p = PLACES[Number(it.getAttribute('data-i'))];
      if (!p) return;
      // 双击直接确认（列表模式没有地图可挪，多一次点击纯属浪费）
      setPicked(p.lat, p.lng, p.addr);
      Array.prototype.forEach.call(list.children, function (c) { c.style.background = ''; });
      it.style.background = '#FFF0F4';
    });

    // 双击 = 选中 + 直接确认
    list.addEventListener('dblclick', function (e) {
      var it = e.target.closest ? e.target.closest('.mk-item') : null;
      if (!it) return;
      var p = PLACES[Number(it.getAttribute('data-i'))];
      if (p) { setPicked(p.lat, p.lng, p.addr); confirmPick(); }
    });
  }

  // ---------- 地图模式 ----------

  function initMap(body) {
    var AMap = global.AMap;
    var center = state.picked
      ? [state.picked.lng, state.picked.lat]
      : DEFAULT_CENTER;

    state.map = new AMap.Map('mk-map', {
      zoom: DEFAULT_ZOOM,
      center: center,
      viewMode: '2D'
    });
    // Geocoder 是插件，可能因为 plugin 参数没生效而缺失。
    // 它只负责「坐标 → 文字地址」，缺了顶多是地址栏空着、经纬度照样能拿到，
    // 所以**降级而不是抛错** —— 不然一个插件缺失就把整张地图废掉了。
    state.geocoder = null;
    if (typeof AMap.Geocoder === 'function') {
      state.geocoder = new AMap.Geocoder({ radius: 200, extensions: 'base' });
    } else if (global.console && console.warn) {
      console.warn('[MapPicker] AMap.Geocoder 未加载（plugin 参数失效），'
        + '将只取经纬度、不反查地址');
    }

    state.marker = new AMap.Marker({
      position: center,
      draggable: true,
      cursor: 'move',
      // 用默认图钉样式即可 —— 自造 icon 在小尺寸下容易糊
      offset: new AMap.Pixel(-13, -30)
    });
    state.map.add(state.marker);

    // 拖拽结束 → 逆地理编码
    state.marker.on('dragend', function (e) {
      var p = e.lnglat;
      reverseGeocode(p.getLng(), p.getLat());
    });

    // 点击地图 → 图钉跟过去 → 逆地理编码
    state.map.on('click', function (e) {
      if (e.lnglat) {
        state.marker.setPosition([e.lnglat.getLng(), e.lnglat.getLat()]);
        reverseGeocode(e.lnglat.getLng(), e.lnglat.getLat());
      }
    });

    // 已经有坐标（比如商家重新选点）：先把已有地址显示出来
    if (state.picked) {
      reverseGeocode(state.picked.lng, state.picked.lat, true);
    } else {
      reverseGeocode(center[0], center[1], true);
    }
  }

  /**
   * 逆地理编码：坐标 → 地址。
   *
   * 两条链路，先官方插件后直连兜底：
   *   1. `AMap.Geocoder`（JS API 官方）—— 需要「Web端(JS API)」类型的 Key
   *   2. REST 直连 `restapi.amap.com/v3/geocode/regeo`（见 restFallbackEnabled）
   * 只要有一条成功就拿到真实中文地址。
   *
   * @param silent 是否「只刷新展示、不产生选点」。
   *   弹层打开时会以地图中心点调一次，用来看中心是哪 —— 这时候**不能**把中心点
   *   当成用户已选的点（否则一打开弹层「确认选点」就是可点的，不点任何地方
   *   直接确认会选到市中心，而且地址栏里填着一句「坐标 32.2728, 119.1845」）。
   */
  function reverseGeocode(lng, lat, silent) {
    state.regeoSource = '';

    function apply(addr, src) {
      state.regeoSource = src;
      // silent 且本来就没有选点 → 只更新展示，不凭空造一个「已选」
      if (silent && !state.picked) { renderFoot(); return; }
      if (state.picked) {
        state.picked.address = addr || state.picked.address || '';
        state.picked.addressResolved = !!addr;
        renderFoot();
        return;
      }
      setPicked(lat, lng, addr);
      state.picked.addressResolved = !!addr;
    }

    /** 插件和 REST 都挂了才会走到这里 */
    function giveUp(code) {
      state.lastGeoError = code || 'unknown';
      apply('');
      if (silent) return;                       // 打开弹层那次不打扰用户

      var hint = '';
      if (code === '10009') {
        // ⚠️ 别把它当成「域名白名单」问题 —— 那是错的。
        // 10009 = USERKEY_PLAT_NOMATCH = **请求 key 与绑定平台不符**。
        //     典型成因：控制台里申请的是「Web服务」类型的 Key，却拿来调 JS API。
        //     迷惑点在于地图瓦片不走平台校验 → 地图显示完全正常，
        //     只有逆地理编码这类服务接口被拒，现象是「地图好好的，就是没有地址」。
        //     另一种成因：把「安全密钥」误当成 Key 填进了 keys[]。
        hint = '地址解析被拒（10009 平台不匹配）：请确认 assets/data/amap-key.js 里 '
             + 'keys[] 填的是控制台的「Key」，且该 Key 在控制台注册的平台类型是'
             + '「Web端(JS API)」（不是「Web服务」）。'
             + '地图仍可用、经纬度也准确 —— 请手动填写详细地址。';
      } else if (code === '10008') {
        hint = 'Key 的安全密钥（securityJsCode）未配置或不对（错误码 10008）：'
             + '请到高德控制台复制该 Key 的安全密钥，填到 assets/data/amap-key.js '
             + '的 securityJsCode 字段。经纬度已取到，可先手动补地址。';
      } else if (code === '10004') {
        hint = 'Key 今日配额已用完（10004），明天恢复或换一个 Key。经纬度已取到。';
      } else if (code === '10001' || code === '10003') {
        hint = 'Key 无效或已过期（' + code + '）。经纬度已取到，可先手动补地址。';
      } else if (code === 'network' || code === 'exception') {
        hint = '地址解析请求发不出去（网络或代理拦截）。经纬度已取到，可先手动补地址。';
      } else {
        hint = '地址解析失败（' + code + '）。经纬度已取到，可先手动补地址。';
      }
      notify(hint);
    }

    // silent 的一次调用也要把上次的警告清掉，否则界面会停在过时的提示上
    if (!silent) clearWarn();

    function tryRest(firstCode) {
      // 记下第一段（官方插件）的错误 —— 即使兜底成功也留着，
      // 排查时才知道「为什么走了兜底」
      state.lastGeoError = firstCode || '';
      if (!restFallbackEnabled() || !state.key) { giveUp(firstCode); return; }
      restRegeo(lng, lat, function (r) {
        apply(r.address, 'rest');
      }, function (restCode) {
        // 两条链路都失败：报告第一段的错误码（更能说明根因），
        // 但把 REST 的结果留着，方便排查
        state.lastRestError = restCode;
        giveUp(firstCode || restCode);
      });
    }

    if (!state.geocoder) { tryRest('no-geocoder'); return; }

    state.geocoder.getAddress([lng, lat], function (status, result) {
      if (status === 'complete' && result && result.regeocode) {
        apply(result.regeocode.formattedAddress || '', 'sdk');
        return;
      }
      // ⚠️ 高德回调里的 `result` 有时是**字符串**（如 "USERKEY_PLAT_NOMATCH"）
      //   而不是对象，直接取 result.infocode 会得到 undefined ——
      //   表现为「有错但错误码是空的」，让人无从下手。两种形态都要认。
      var code = '';
      if (result && typeof result === 'object') code = result.infocode || result.info || '';
      else if (typeof result === 'string') code = result;
      tryRest(code);
    });
  }

  /**
   * 轻量提示条：在弹层顶部插一条黄条。
   * 不用 alert（打断操作），也不只写 console（用户看不到）。
   */
  function clearWarn() {
    if (!state.el) return;
    var w = state.el.querySelector('.mk-warn');
    if (w && w.parentNode) w.parentNode.removeChild(w);
  }

  function notify(text) {
    if (!state.el) return;
    var body = state.el.querySelector('.mk-body');
    if (!body || body.querySelector('.mk-warn')) return;
    var w = document.createElement('div');
    w.className = 'mk-warn';
    w.textContent = text;
    body.insertBefore(w, body.firstChild);
  }

  // ============================ 对外入口 ============================

  /**
   * 打开选点弹层。
   *
   * @param opts.current  {lat, lng, address} 已有位置（店铺资料页要能改回原样）
   * @param opts.city     城市名，仅用于提示文案
   * @param opts.onPick   确认回调，收到 {lat, lng, address}
   */
  function open(opts) {
    opts = opts || {};
    if (!document || !document.body) return;

    close();                       // 保险：连着点两次按钮不该叠出两个层
    injectStyle();

    state.onPick = opts.onPick || null;
    state.picked = null;
    if (opts.current && opts.current.lat != null && opts.current.lng != null) {
      setPicked(opts.current.lat, opts.current.lng, opts.current.address || '');
    }

    state.el = buildShell();
    var body = state.el.querySelector('.mk-body');

    if (!hasKey()) {
      state.mode = 'list';
      renderLiteBody(body, '未配置高德地图 Key。');
      renderFoot();
      document.addEventListener('keydown', onEsc);
      return;
    }

    // 先渲染「加载中」，再异步换真地图 —— 避免用户点了按钮没反应以为卡住
    body.innerHTML = '<div class="mk-tip">正在加载高德地图…</div>' +
                     '<div class="mk-map" style="display:flex;align-items:center;justify-content:center;color:#8A9099;font-size:13px">地图加载中</div>';
    renderFoot();

    loadSdk().then(function (ok) {
      if (!state.el) return;       // 加载期间用户已经关掉了
      if (ok) {
        state.mode = 'map';
        renderMapBody(body);
        try {
          initMap(body);
        } catch (e) {
          // SDK 在但初始化炸了（配额用尽 / 容器尺寸为 0 / 插件缺失）——
          // 这种时候不能白屏，直接退列表。
          // 把真实原因带上：笼统写「检查 Key 配置」会把人引偏，
          // 实际踩过的是「容器高度还没算出来」和「Geocoder 插件没声明」。
          var why = (e && e.message) ? e.message : String(e);
          state.lastMapError = why;
          if (global.console && console.warn) console.warn('[MapPicker] 地图初始化失败:', e);
          state.mode = 'list';
          renderLiteBody(body, '地图初始化失败（' + why + '），');
          renderFoot();
        }
      } else {
        state.mode = 'list';
        renderLiteBody(body, '地图 SDK 加载失败（网络受限或 Key 无效）。');
        renderFoot();
      }
    });

    document.addEventListener('keydown', onEsc);
  }

  global.MapPicker = {
    open: open,
    setKey: setKey,
    /** 配安全密钥（只有「Web端(JS API)」类型 Key 才有） */
    setSecurityCode: setSecurityCode,
    /** 当前是否已配安全密钥 */
    hasSecurityCode: function () { return !!readSecurityCode(); },
    /** 逆地理编码兜底是否开着 */
    restFallback: restFallbackEnabled,
    hasKey: hasKey,
    /** 当前 Key 池（只读用途，调试时看有几个可用 Key） */
    keys: function () { ensureKeys(); return state.keys.slice(); },
    /** 上一次地图初始化失败的原因（调试用；null = 没失败） */
    lastMapError: function () { return state.lastMapError || null; },
    /**
     * 上一次逆地理编码的错误码（诊断用）。
     * 10009 = USERKEY_PLAT_NOMATCH = Key 的平台类型不对（**不是**域名白名单）；
     * 10008 = 安全密钥缺失/不匹配；10004 = 配额用尽。
     */
    lastGeoError: function () { return state.lastGeoError || null; },
    /** REST 兜底链路的错误码（两条链路都失败时才有值） */
    lastRestError: function () { return state.lastRestError || null; },
    /** 地址是从哪条链路拿到的：'sdk' | 'rest' | ''（都失败） */
    regeoSource: function () { return state.regeoSource || ''; },
    /** 强制重新尝试加载地图（换了 Key / 网络恢复后调） */
    reload: function () { resetSdk(); return loadSdk(); },
    /** 内置坐标库只读副本 —— 便于别处（比如测试）复用同一份真实坐标 */
    places: PLACES,
    /** 距离公式，与后端 DistanceCalculator 口径一致 */
    distanceKm: kmFrom,
    center: { lat: DEFAULT_CENTER[1], lng: DEFAULT_CENTER[0] }
  };
})(window);
