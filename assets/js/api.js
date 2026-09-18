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

  var API_BASE = localStorage.getItem('apiBase') || defaultApiBase();
  var enabled = localStorage.getItem('useApi') === '1';

  /**
   * 默认的接口地址。
   *
   * 分四种情况（v1.9 起后端也会托管前端页面，所以多了第 ② 种）：
   *
   *   ① 页面从 5173（dev.py 起的静态服务）打开 → 后端在 8080 → 跨域调它
   *   ② 页面**就是后端发的**（同一个 origin）→ 用相对路径 `/api`
   *   ③ `file://` 直开 → 退回 localhost，跟改造前行为一致
   *   ④ 拿不到 `location.port`（非浏览器宿主、单元测试沙箱）→ 退回 localhost
   *
   * ⚠️ ② 必须用相对路径，**不能写死 `http://localhost:8080`**：
   * 手机上的 `localhost` 指的是**手机自己**，写死了手机上就永远连不上。
   * 而且同源请求天然没有跨域，CORS 那一套整个用不上 —— 这是「一个地址
   * 搞定」最大的收益。
   *
   * ⚠️ ③④ 不能漏，两者都会出现「端口读不到」：
   *   · `file://` 下 `location.port` 是空字符串
   *   · Node 沙箱（`test_adapter.cjs`）的 `location` 只是个 `{search, href}` 替身
   * 若一律返回 `/api`，它们会去请求 `file:///api/...` 或干脆连不上，
   * 报一个看不懂的错误。**默认值要偏向「能用」而不是「正确」** ——
   * 因为「端口是 5173」这个判断只在真的浏览器里才成立。
   */
  function defaultApiBase() {
    var loc = (typeof location !== 'undefined' && location) || {};
    // ① 端口明确是 5173 → 分离部署，后端在 8080
    if (String(loc.port || '') === '5173') return 'http://localhost:8080/api';
    // ③ file:// 直开
    if (loc.protocol === 'file:') return 'http://localhost:8080/api';
    // ④ 端口读不到（沙箱/非浏览器）→ 退回 localhost，别赌同源
    if (!loc.port) return 'http://localhost:8080/api';
    // ② 其余情况 = 页面由后端发出 → 同源相对路径
    return '/api';
  }

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

  /**
   * 商家身份：当前登录的是哪家店。
   * 授权以 JWT 里的 shopId 为准（服务端会强制覆盖 body 里的值），
   * 这里读 localStorage 只是为了「刷新页面后知道该拉哪家店」，
   * 拉不到就回退到 mock 里的演示账号。
   */
  function merchantShopId() {
    var id = null;
    try { id = localStorage.getItem('merchantShopId'); } catch (e) { id = null; }
    if (id) return id;
    return (window.MOCK && window.MOCK.currentMerchant && window.MOCK.currentMerchant.shopId) || null;
  }

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
    me: null,              // 商家端：当前登录的店铺
    canPostToday: null,    // 商家端：今天还能不能发
    cooldownSeconds: 0,    // 商家端：距下次可发的剩余秒数
    loaded: false
  };

  // ================= 同步请求（见文首说明） =================

  // ================= 登录态（JWT） =================

  /**
   * 会话**按角色分 key 存**。
   *
   * 原来三端共用一个 `eatwhat_token`，后果是：同一个浏览器里先登平台端、
   * 再打开商家端，商家端的 token 就被顶掉了 —— 适配层拿着 admin token 去
   * 请求 /api/merchant/**，被 403 挡回，页面静默变空白。
   * 而演示时三端本来就是同一个浏览器来回切的，这个坑一定会踩到。
   *
   * 现在按角色分开，一个浏览器可以同时持有三端身份，互不干扰。
   * 没有显式传角色时，用当前所在的端（END）。
   */
  function tokenKey(role) { return 'eatwhat_token_' + (role || END); }
  function roleKey(role) { return 'eatwhat_token_role_' + (role || END); }
  function userKey(role) { return 'eatwhat_user_' + (role || END); }

  function getToken(role) {
    try { return localStorage.getItem(tokenKey(role)); } catch (e) { return null; }
  }

  function getTokenRole(role) {
    try { return localStorage.getItem(roleKey(role)); } catch (e) { return null; }
  }

  /** 记下这次登录。**只存 token 和用户展示信息，不存密码** —— 密码留在 localStorage 里是隐患 */
  function saveSession(role, data) {
    try {
      localStorage.setItem(tokenKey(role), data.token);
      localStorage.setItem(roleKey(role), role);
      // 客户端要把服务端返回的用户带上：评论这类写操作的身份**由服务端从 token 取**，
      // 但页面渲染（头像 / 昵称）还需要它，否则只能拿 mock 里的假身份去显示
      if (data.user) localStorage.setItem(userKey(role), JSON.stringify(data.user));
      if (role === 'merchant' && data.shopId) {
        localStorage.setItem('merchantShopId', data.shopId);
      }
    } catch (e) { /* 隐私模式下写不进去，忽略 */ }
  }

  /** 只清当前端（或指定角色）的会话，不动其他端的 */
  function clearSession(role) {
    try {
      localStorage.removeItem(tokenKey(role));
      localStorage.removeItem(roleKey(role));
      localStorage.removeItem(userKey(role));
    } catch (e) { /* 同上 */ }
  }

  /**
   * 把已登录的客户端用户还原进 MOCK.currentUser。
   *
   * 原先客户端的「当前用户」只是 mock.js 里一个演示对象，登录页把
   * `loggedIn` 翻成 true 就算登进去了 —— 服务端完全不知道来的是谁。
   * 而且登录页写的 `localStorage.loggedIn` **没有任何地方读**，
   * 所以一翻页登录态就丢，发评论又让你去登录。
   *
   * 现在两路都还原：优先用服务端返回的真实用户，其次退回离线演示标志。
   */
  function hydrateCurrentUser() {
    if (END !== 'client' || !window.MOCK || !window.MOCK.currentUser) return;
    var cu = window.MOCK.currentUser;

    var raw = null;
    try { raw = localStorage.getItem(userKey('client')); } catch (e) { raw = null; }
    if (raw) {
      try {
        var u = JSON.parse(raw);
        if (u && u.id) {
          cu.id = u.id;
          if (u.name) cu.name = u.name;
          if (u.avatar) cu.avatar = u.avatar;
          cu.loggedIn = true;
          return;
        }
      } catch (e) { /* 存的东西坏了，往下走离线分支 */ }
    }

    // 离线演示：没接后端时登录页只落了一个标志，把它认下来
    try {
      if (localStorage.getItem('loggedIn') === '1') cu.loggedIn = true;
    } catch (e) { /* 忽略 */ }
  }

  /**
   * 登出。**先通知服务端，再清本地**，顺序不能反。
   *
   * 只清 localStorage 是不够的 —— token 本身还在有效期内（168 小时），
   * 谁把这份字符串抄走都还能接着用。服务端登出会把该主体已签发的凭证**真正作废**
   * （版本号 +1），旧 token 下一毫秒就对不上号了。
   *
   * 两条兜底：
   * - 离线模式没有服务端可通知，退化成只清本地；
   * - 服务端调不通也照样清本地 —— 登出**必须永远成功**，
   *   不能因为网络抽风把人留在登录态里出不去。
   */
  function logout(role) {
    var r = role || END;
    if (enabled) {
      try {
        if (getToken(r)) syncRequest('POST', '/auth/logout', {});
      } catch (e) {
        console.warn('[api] 服务端登出未成功，已只清本地会话：' + e.message);
      }
    }
    clearSession(r);
    // 客户端还有个离线演示标志要一起清，否则登出后翻页又"登录"回来了
    if (r === 'client') {
      try { localStorage.removeItem('loggedIn'); } catch (e) { /* 忽略 */ }
      if (window.MOCK && window.MOCK.currentUser) window.MOCK.currentUser.loggedIn = false;
    }
  }

  /**
   * 给后台端（平台 / 商家）侧栏挂一个「退出登录」入口。
   *
   * 为什么不改页面：侧栏是**各页内联复制**的，平台端 9 页 + 商家端 8 页，
   * 逐个加容易漏、以后新增页面还会忘。注入只有一处，新页面自动带上。
   *
   * 客户端不挂 —— 客户端的退出在「我的」页里，入口位置不一样。
   */
  function mountLogoutEntry() {
    if (END === 'client' || typeof document === 'undefined') return;
    var nav = document.querySelector('.sidebar__nav');
    if (!nav || nav.querySelector('[data-logout]')) return;

    var a = document.createElement('a');
    a.className = 'nav-item';
    a.href = 'javascript:void(0)';
    a.setAttribute('data-logout', '1');
    a.textContent = '退出登录';
    // 跟上面的菜单拉开距离，免得被当成又一个功能项
    a.style.marginTop = '18px';
    a.style.borderTop = '1px solid rgba(128,128,128,.18)';
    a.style.paddingTop = '14px';
    a.onclick = function () {
      if (!confirm('确定退出登录？\n\n退出后这份登录凭证会在服务端立即作废，需要重新登录。')) return;
      logout();
      location.replace('login.html');
    };
    nav.appendChild(a);
  }

  /** 解出 payload 看 exp。解不开就当过期 —— 宁可多登一次，也别拿着坏 token 一直撞 401 */
  function tokenExpired(token) {
    try {
      var part = token.split('.')[1] || '';
      part = part.replace(/-/g, '+').replace(/_/g, '/');
      while (part.length % 4) part += '=';
      var payload = JSON.parse(decodeURIComponent(escape(atob(part))));
      return !payload.exp || payload.exp * 1000 <= Date.now() + 30000;
    } catch (e) {
      return true;
    }
  }

  /**
   * 取当前可用的 token。
   *
   * 客户端免登录，直接返回 null；平台端 / 商家端没登录就返回 null，
   * 由 preloadSync 走降级（页面照样能打开，只是用的本地假数据）。
   * 这里刻意**不做静默登录** —— 那等于在 localStorage 里埋一份明文密码。
   */
  function ensureToken() {
    if (!enabled || END === 'client') return null;
    var t = getToken();
    if (t && !tokenExpired(t)) return t;
    if (t) clearSession();
    return null;
  }

  /**
   * 登录（同步）。成功后落盘 token，返回后端给的整个 data。
   *
   * 四种用法：
   *   login('admin',    'admin',         'admin123')          平台端账号密码
   *   login('merchant', 's_001',         '123456')            商家账号密码
   *   login('merchant', '13800138000',   null, '483920')      商家手机号 + 短信验证码
   *   login('client',   '13800000001',   '483920')            客户端手机号 + 验证码
   *
   * 客户端走 /login（手机号 + 验证码，验证码由 /auth/sms-code 下发），
   * 平台端 / 商家端走 /auth/login。
   *
   * 离线模式：不发请求直接放行 —— 原型阶段不接后端也要能"登录"进去看页面，
   * 返回 `{ offline: true }`，调用方可以据此提示"离线演示模式"。
   */
  function login(role, account, password, code) {
    if (!enabled) {
      return {
        offline: true,
        role: role,
        account: account,
        shopId: role === 'merchant' ? merchantShopId() : null,
        name: role === 'admin' ? '平台运营' : ''
      };
    }

    var path, body;
    if (role === 'client') {
      path = '/login';
      body = { phone: account, code: code || password };
    } else {
      path = '/auth/login';
      body = { role: role, account: account };
      if (code) body.code = code; else body.password = password;
    }

    var data = syncRequest('POST', path, body, true);
    saveSession(role, data);
    return data;
  }

  /**
   * 获取短信验证码（同步）。返回 { sent, expiresIn, resendAfter, devCode? }。
   *
   * `devCode` 只有后端开了 `echoSmsCode` 才有（本地调试用），
   * 生产环境这个字段不存在，码只能从短信里拿。
   * 60 秒内重发、或发得太频繁，后端会返回 429，异常消息里带还要等几秒。
   */
  function sendSmsCode(phone, scene) {
    if (!enabled) {
      // 离线降级：给个固定码，原型照样能演示完整登录流程
      return { sent: true, offline: true, devCode: '123456', expiresIn: 300, resendAfter: 60 };
    }
    return syncRequest('POST', '/auth/sms-code',
      { phone: phone, scene: scene || 'login' }, true);
  }

  // ================= 同步请求（见文首说明） =================

  /**
   * @param skipAuth 登录接口自己用，避免「拿 token 换 token」的递归
   */
  function syncRequest(method, path, body, skipAuth) {
    return doRequest(method, path, body, skipAuth, true);
  }

  function doRequest(method, path, body, skipAuth, allowRetry) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, API_BASE + path, false);   // false = 同步
    xhr.setRequestHeader('Accept', 'application/json');
    if (body !== undefined && body !== null) {
      xhr.setRequestHeader('Content-Type', 'application/json');
    }
    if (!skipAuth) {
      var t = getToken();
      if (t) xhr.setRequestHeader('Authorization', 'Bearer ' + t);
    }
    xhr.send(body === undefined || body === null ? null : JSON.stringify(body));

    var j = null;
    try { j = JSON.parse(xhr.responseText); } catch (e) { j = null; }

    if (xhr.status === 401 && allowRetry && !skipAuth) {
      // token 过期或换了密钥：清掉重来一次。再失败就交给上层降级。
      clearSession();
      return doRequest(method, path, body, skipAuth, false);
    }
    if (xhr.status < 200 || xhr.status >= 300) {
      // 优先把后端的业务提示抛出去（「发布太频繁」比「HTTP 400」有用得多）
      var err = new Error((j && j.msg) || ('HTTP ' + xhr.status));
      // 打上状态码标记：上层必须能区分「后端没起来」和「这份凭证不作数了」——
      // 前者该降级到假数据保住原型可看，后者**绝不能降级**（那等于给被封的人看演示数据）
      err.httpStatus = xhr.status;
      err.authFailed = (xhr.status === 401);
      throw err;
    }
    if (!j) throw new Error('接口返回的不是 JSON');
    if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
    return j.data;
  }

  function syncGet(path) { return syncRequest('GET', path, null); }
  function syncPost(path, body) { return syncRequest('POST', path, body || {}); }

  /**
   * 上传文件（异步）。
   *
   * 为什么不像别的请求那样走同步 XHR：上传动辄几百 KB 到 20MB，
   * 同步会把主线程连同转圈动画一起冻住，体验很差。
   * 离线模式下退化成 dataURL，原型仍然能完整演示「选了图能预览」。
   */
  function upload(file) {
    return new Promise(function (resolve, reject) {
      if (!enabled) {
        if (file.size > 8 * 1024 * 1024) {
          reject(new Error('离线模式下最大支持 8MB（接上后端后图片 5MB / 视频 20MB）'));
          return;
        }
        var fr = new FileReader();
        fr.onload = function () {
          resolve({
            url: fr.result,
            name: file.name,
            size: file.size,
            kind: file.type.indexOf('video') === 0 ? 'video' : 'image',
            offline: true
          });
        };
        fr.onerror = function () { reject(new Error('读取文件失败')); };
        fr.readAsDataURL(file);
        return;
      }

      var fd = new FormData();
      fd.append('file', file);
      var xhr = new XMLHttpRequest();
      xhr.open('POST', API_BASE + '/upload', true);
      var t = getToken();
      if (t) xhr.setRequestHeader('Authorization', 'Bearer ' + t);
      xhr.onload = function () {
        var j = null;
        try { j = JSON.parse(xhr.responseText); } catch (e) { j = null; }
        if (xhr.status >= 200 && xhr.status < 300 && j && j.code === 0) {
          resolve(j.data);
        } else {
          reject(new Error((j && j.msg) || ('上传失败（HTTP ' + xhr.status + '）')));
        }
      };
      xhr.onerror = function () { reject(new Error('网络异常，上传失败')); };
      xhr.send(fd);
    });
  }

  /**
   * 把缓存里的「活数据」镜像回 `window.MOCK`。
   *
   * ⚠️⚠️ **这是本适配层最容易漏、后果最隐蔽的一条。**
   *
   * 页面代码有两类读法，**都必须拿到同一份数据**：
   *
   *   ① `MOCK.getShop(id)`  → 被本层覆写，走 `cache.shops`
   *   ② `MOCK.shops` / `MOCK.buildFeed()` 等**直接读数组**的路径
   *      —— 而 `mock.js` 内部的比较器用的是 `this.shops`，
   *         完全绕过了被覆写的 `getShop`
   *
   * 如果只做 ① 不同步 ②，就会出现**同一个店 id 有两个不同距离**：
   * `cache.shops` 里是后端算好的 0.9km，`MOCK.shops` 里还是种子数据的 2.3km。
   * 排序器读前者、卡片显示读后者时，列表看起来就是「乱序」的，
   * 但**两个值各自都"没错"** —— 极难查（本项目为此排查了两轮）。
   *
   * 所以任何时候只要 `cache` 变了，就必须调这个函数把它推回 MOCK，
   * **保持「数据只有一份」**。
   */
  function hydrateMock() {
    var M = window.MOCK;
    if (!M) return;
    // 用同一个数组引用，而不是复制 —— 复制会立刻产生第二个真相源
    if (cache.shops.length || !cache.loaded) M.shops = cache.shops;
    if (cache.dishes.length) M.dishes = cache.dishes;
    if (cache.comments.length) M.comments = cache.comments;
    if (cache.priceTiers.length) M.priceTiers = cache.priceTiers;
    if (cache.tasteTags.length) M.tasteTags = cache.tasteTags;
    if (cache.cuisines.length) M.cuisines = cache.cuisines;
    if (cache.publishRule) M.publishRule = cache.publishRule;
  }

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

    hydrateMock();
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
   * 商家端数据。
   * 与客户端的根本差别：这里是「我这一个店」的口径 ——
   * 包含 pending / rejected 状态的**自己**，以及**已下架**的菜品。
   * 这两样在客户端可见范围里都不存在，所以商家端拿 /client/bootstrap
   * 会直接 getShop 返回 null，整页空白。
   */
  function applyMerchantData(d) {
    d = d || {};
    var shop = d.shop || null;
    cache.me = shop;

    // 商家端不做跨店过滤：shops / dishes 就是「我的店 / 我的菜」
    cache.shops = shop ? [shop] : [];
    cache.dishes = d.dishes || [];
    cache.comments = d.comments || [];
    cache.canPostToday = d.canPostToday;
    cache.cooldownSeconds = d.cooldownSeconds || 0;
    applyConfig(d.config);

    if (!window.MOCK) return;
    var M = window.MOCK;
    if (shop) {
      M.shops = cache.shops;
      // 商家身份以服务端返回的店为准，避免 localStorage 里的旧 id 把页面带偏
      if (M.currentMerchant) {
        M.currentMerchant.shopId = shop.id;
        M.currentMerchant.status = shop.status;
        M.currentMerchant.rejectReason = shop.rejectReason || '';
      }
    }
    // 商家端不拆「下架」桶：下架的菜商家必须看得见、能恢复，
    // 所以 getShopDishes 返回的是全集。
    M.dishes = cache.dishes;
    M.removedDishes = [];
    M.comments = cache.comments;
    // 冷却秒数暴露给页面，发布页的倒计时用它 ——
    // 原来页面写死了 23:45:12，跟真实剩余时间对不上
    M.cooldownSeconds = cache.cooldownSeconds;
  }

  /**
   * 同步预加载：把后端数据灌进缓存。
   * 返回 true 表示成功接上后端；false 表示已降级回本地假数据。
   */
  function preloadSync() {
    var t0 = Date.now();
    try {
      // 平台端 / 商家端要凭证；客户端免登录浏览，直接放行
      if (END !== 'client') {
        var tk = ensureToken();
        if (!tk) {
          throw new Error('未登录或登录已过期 —— 请先在 ' +
            (END === 'admin' ? 'admin/login.html' : 'merchant/login.html') +
            ' 登录（页面上是本地演示数据）');
        }
      }

      // 每端只打一个聚合接口，请求数直接等于白屏时长，能少则少
      if (END === 'admin') {
        applyAdminData(syncGet('/admin/bootstrap') || {});
      } else if (END === 'merchant') {
        var sid = merchantShopId();
        if (sid) {
          applyMerchantData(syncGet('/merchant/bootstrap?shopId=' +
            encodeURIComponent(sid)) || {});
        } else {
          // 没登录也没演示身份 —— 交给本地假数据兜底，页面至少能打开
          throw new Error('未识别到商家身份（localStorage.merchantShopId 为空）');
        }
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
      // 401 = 这份凭证不作数了（被封店 / 已登出 / 版本号对不上）。
      // 跟「后端没起来」是两回事，处理方式也必须相反：
      // 后端没起来 → 降级到假数据，保住原型能打开；
      // 凭证失效   → **绝不能降级**，否则被封的商家会看到一屏演示数据，
      //              以为一切正常，还在那儿点着玩。
      if (e && e.authFailed && END !== 'client') {
        console.warn('[api] 凭证已失效：' + e.message);
        try { sessionStorage.setItem('eatwhat_login_reason', e.message); } catch (ignore) { }
        if (typeof location !== 'undefined' && location.replace) {
          location.replace('login.html');
        }
        return false;
      }
      console.warn('[api] 后端未就绪，已降级到本地假数据：' + e.message);
      enabled = false;
      cache.loaded = false;
      return false;
    }
  }

  var online = function () { return enabled && cache.loaded; };

  // ================= 异步请求（写操作 / 未来用） =================

  function authHeaders(extra) {
    var h = extra || {};
    var t = getToken();
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  function get(path) {
    return fetch(API_BASE + path, { headers: authHeaders({ 'Accept': 'application/json' }) })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || '接口返回异常');
        return j.data;
      });
  }

  function send(method, path, body) {
    return fetch(API_BASE + path, {
      method: method,
      headers: authHeaders({ 'Content-Type': 'application/json' }),
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
    if (END !== 'client' && !ensureToken()) return Promise.resolve(false);
    var path;
    if (END === 'admin') {
      path = '/admin/bootstrap';
    } else if (END === 'merchant') {
      var sid = merchantShopId();
      if (!sid) return Promise.resolve(false);
      path = '/merchant/bootstrap?shopId=' + encodeURIComponent(sid);
    } else {
      path = '/client/bootstrap';
    }
    return get(path)
      .then(function (d) {
        if (END === 'admin') applyAdminData(d);
        else if (END === 'merchant') applyMerchantData(d);
        else applyClientData(d);
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

    // ---- 登录态 / 上传 ----
    login: login,
    logout: logout,
    sendSmsCode: sendSmsCode,
    token: getToken,
    role: getTokenRole,
    isLoggedIn: function () { return !!ensureToken(); },
    upload: upload,

    // ---- 客户端互动 / 登录引导 ----
    toggleInteract: toggleInteract,
    guardLogin: guardLogin,
    isInteracted: function (kind, dishId) {
      return window.MOCK ? window.MOCK.isInteracted(kind, dishId) : false;
    },
    addBrowsed: function (dishId) {
      if (window.MOCK) window.MOCK.addBrowsed(dishId);
    },

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

  /**
   * 用最新数据更新数组里的同 id 项；找不到就 push。
   *
   * ⚠️ 必须是**就地合并字段**，不能写 `list[i] = item`。
   *
   * 整体换引用会造成「同一个 id 有两个对象」：持有旧引用的地方
   * （比如排序器刚取到的 `shop`、某个闭包里的变量）看到的还是旧数据，
   * 而新数组里已经换了 —— 表现为「同一家店两个距离 / 两个权重」，
   * 且**两边各自都自洽**，极难定位。
   *
   * 另外服务端返回的常是**精简视图**（可能不含 lat/lng），
   * 直接替换会把坐标弄丢，距离随之算不出来。
   * 就地合并天然规避了这一点：没返回的字段保持原值。
   */
  function upsert(list, item) {
    if (!list || !item || !item.id) return;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === item.id) {
        var target = list[i];
        if (target !== item) {
          Object.keys(item).forEach(function (k) { target[k] = item[k]; });
        }
        return;
      }
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

  function findComment(id) {
    var M = window.MOCK;
    var lists = [cache.comments, M && M.comments];
    for (var i = 0; i < lists.length; i++) {
      var hit = (lists[i] || []).find(function (c) { return c.id === id; });
      if (hit) return hit;
    }
    return null;
  }

  /**
   * 商家端的菜品写入：只动「我的菜」这一个池子。
   * 不调 reconcileDishLists —— 那个函数按 status 把菜拆进
   * removed 桶，而商家端刻意不拆（下架的菜他要看得见、能恢复）。
   */
  function upsertShopDish(d) {
    if (!d || !d.id) return;
    upsert(cache.dishes, d);
    if (window.MOCK) upsert(window.MOCK.dishes, d);
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

    // ---------- 客户端 ----------
    /*
     * 发表评论。
     *
     * ⚠️ 请求体里**不再传 userId** —— 身份由服务端从 token 取。
     * 以前是把 userId 塞进 body，等于谁改一下这个字段就能以别人的名义评论。
     */
    'client.comment': {
      path: function (p) { return '/client/dish/' + p.dishId + '/comment'; },
      body: function (p) { return { content: p.content }; },
      local: function (p) {
        // 离线：本地造一条，原型仍然能演示「评论发出去、马上出现在列表里」
        var M = window.MOCK;
        var cu = (M && M.currentUser) || {};
        var c = {
          id: 'c_' + Date.now(),
          dishId: p.dishId,
          userId: cu.id,
          userName: cu.name,
          avatar: cu.avatar,
          content: p.content,
          at: nowStr(),
          reply: null
        };
        upsert(M.comments, c);
        var d = findDish(p.dishId);
        if (d && d.stats) d.stats.comments = (d.stats.comments || 0) + 1;
        return true;
      },
      apply: function (data) {
        // 服务端回的是权威评论视图（含它生成的 id 与时间），直接并进池子，
        // 让 getDishComments / MOCK.comments 都能看到
        upsert(cache.comments, data);
        if (window.MOCK) upsert(window.MOCK.comments, data);
        var d = findDish(data.dishId);
        if (d && d.stats) d.stats.comments = (d.stats.comments || 0) + 1;
      }
    },

    /*
     * 点赞 / 收藏。
     *
     * ⚠️ 这是「本地 + 后端双写」：**先改本地（永远成功），再尽力打后端**。
     *
     * 为什么顺序是这样，而不是「后端成功才算数」：
     * 点赞是个高频、低价值、用户预期「立刻有反馈」的动作。若等后端返回
     * 才点亮爱心，弱网下会有明显卡顿感；而如果后端报错就回滚，
     * 用户会觉得「我明明点了怎么又灭了」。
     * 所以本地状态是权威的（决定爱心亮不亮），后端的 stats 只当参考数。
     *
     * 注意与评论的区别：评论必须落库（内容是别人要看到的），
     * 失败就得让用户知道；点赞失败了静默即可，用户体验优先。
     */
    'client.like': interactAction('liked'),
    'client.favorite': interactAction('favorited'),

    // ---------- 商家审核 ----------
    'audit.approve': {
      path: function (p) { return '/admin/audit/' + p.id + '/approve'; },
      body: function (p) { return { reviewer: p.reviewer || '平台运营', force: !!p.force }; },
      // 本地没有 DistanceCalculator，用「到市中心的近似距离」补齐：
      // 与后端 Haversine 口径一致（仪征锚点 32.2728, 119.1845），
      // 不做这件事的话，本地模式审核通过的店 distance 永远是 null，
      // 会静默排到「附近」列表末尾 —— 看起来像审核没生效。
      local: function (p) {
        var M = window.MOCK;
        var s = removeById(M.pendingShops, p.id);
        if (!s) return false;
        s.status = 'normal';
        s.distance = localDistanceKm(s.lat, s.lng);
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
      apply: function (data) {
        // MOCK.shops 与 cache.shops 是**同一个数组引用**（见 hydrateMock），
        // 所以这里只需 upsert 一次 —— 写两遍等于对同一个数组做两次，纯属多余。
        upsert(cache.shops, data);
        hydrateMock();
      }
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
      apply: function (data) {
        // MOCK.shops 与 cache.shops 是**同一个数组引用**（见 hydrateMock），
        // 所以这里只需 upsert 一次 —— 写两遍等于对同一个数组做两次，纯属多余。
        upsert(cache.shops, data);
        hydrateMock();
      }
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
      apply: function (data) {
        // MOCK.shops 与 cache.shops 是**同一个数组引用**（见 hydrateMock），
        // 所以这里只需 upsert 一次 —— 写两遍等于对同一个数组做两次，纯属多余。
        upsert(cache.shops, data);
        hydrateMock();
      }
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
        if (!Array.isArray(data)) return;
        /*
         * ⚠️ 这里**不能**写 `cache.shops = data; MOCK.shops = data;`。
         *
         * 后端返回的只是「排序后的店铺列表」，直接拿它替换数组会同时造成两件事：
         *   ① 若它是个**精简视图**（少了 lat/lng 等字段），
         *      `MOCK.shops` 里的店就丢了坐标 → 距离算不出来 → 列表顺序全乱
         *   ② 它成了**第三个数组对象**，与 cache 里原有的店对象不再同一个引用，
         *      后续对某一家店的修改只会落在其中一个上
         *
         * 正确做法：数据仍然以 cache 为准，只按服务端给的顺序**重排**，
         * 并用它返回的字段把已有对象补齐（就地更新，保住引用）。
         */
        var seq = {};
        data.forEach(function (s, i) { if (s && s.id) seq[s.id] = i; });
        cache.shops.sort(function (a, b) {
          var ia = seq[a.id] == null ? 1e9 : seq[a.id];
          var ib = seq[b.id] == null ? 1e9 : seq[b.id];
          return ia - ib;
        });
        // 服务端返回的字段覆盖回来（权重等），对象引用保持不变
        data.forEach(function (s) {
          if (!s || !s.id) return;
          var mine = findShop(s.id);
          if (mine && mine !== s) Object.keys(s).forEach(function (k) { mine[k] = s[k]; });
        });
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
    },

    // ==================== 商家端 ====================
    /*
     * 与平台端的关键差别：平台端操作「任意一家店」，商家端只能操作
     * 「自己那家店」。所以 shopId 一律由 merchantShopId() 决定，
     * 不接受页面随便传 —— 页面把参数写错就能改串数据，这种口子不能留。
     */

    /** 发布菜品。冷却 / 日上限 / 媒体数量都由服务端把关 */
    'merchant.publish': {
      path: function () { return '/merchant/dish'; },
      body: function (p) {
        return {
          shopId: merchantShopId(),
          name: p.name, desc: p.desc, type: p.type,
          media: p.media, cover: p.cover, price: p.price,
          // 视频菜品才有；media 里那一项是封面图，真视频走这里
          videoUrl: p.videoUrl,
          tasteTags: p.tasteTags
        };
      },
      local: function (p) {
        var M = window.MOCK;
        if (!M) return false;
        var media = p.media || [];
        if (!media.length) return false;
        var tier = API.getTierByPrice(p.price);
        upsertShopDish({
          id: 'd_' + Date.now(),
          shopId: merchantShopId(),
          shopName: (cache.me && cache.me.name) || '',
          name: p.name,
          desc: p.desc,
          type: p.type,
          media: media,
          cover: p.cover || media[0],
          price: p.price,
          priceTierId: tier ? tier.id : null,
          realTag: 'pending',
          tasteTags: p.tasteTags || [],
          publishedAt: nowStr(),
          status: 'normal',
          stats: { views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 },
          decay: 1
        });
        return true;
      },
      apply: function (data) {
        upsertShopDish(data);
        // 发布成功后本地也要立刻反映「今天不能发了」，否则页面还在放行
        cache.canPostToday = false;
        if (cache.me) {
          cache.me.canPostToday = false;
          cache.me.lastPostAt = data.publishedAt;
        }
      }
    },

    /** 编辑菜品。type 不可改，服务端会拒 */
    'merchant.dish.update': {
      method: 'PUT',
      path: function (p) { return '/merchant/dish/' + p.id; },
      body: function (p) {
        return { name: p.name, desc: p.desc, price: p.price,
                 media: p.media, tasteTags: p.tasteTags };
      },
      local: function (p) {
        var d = findDish(p.id);
        if (!d) return false;
        if (p.name != null) d.name = p.name;
        if (p.desc != null) d.desc = p.desc;
        if (p.media != null) d.media = p.media;
        if (p.tasteTags != null) d.tasteTags = p.tasteTags;
        if (p.price != null) {
          d.price = p.price;
          var t = API.getTierByPrice(p.price);   // 价格变了要重新归档档位
          d.priceTierId = t ? t.id : null;
        }
        return true;
      },
      apply: function (data) { upsertShopDish(data); }
    },

    /**
     * 下架 / 恢复自己的菜品。
     * ⚠️ 一律走逻辑下架（status=removed），**不能从数组里 splice 掉** ——
     * 「超 24h 权重归零但不物理删除」是同一条口径，物理删除会让
     * 平台端的内容管理和下架记录对不上账。
     */
    'merchant.dish.remove': {
      path: function (p) { return '/merchant/dish/' + p.id + '/remove'; },
      body: function () { return { shopId: merchantShopId() }; },
      local: function (p) {
        var d = findDish(p.id);
        if (!d) return false;
        d.status = 'removed';
        return true;
      },
      apply: function (data) { upsertShopDish(data); }
    },

    'merchant.dish.restore': {
      path: function (p) { return '/merchant/dish/' + p.id + '/restore'; },
      body: function () { return { shopId: merchantShopId() }; },
      local: function (p) {
        var d = findDish(p.id);
        if (!d) return false;
        d.status = 'normal';
        return true;
      },
      apply: function (data) { upsertShopDish(data); }
    },

    /** 保存店铺资料 */
    'merchant.shop.update': {
      method: 'PUT',
      path: function () { return '/merchant/shop/' + merchantShopId(); },
      body: function (p) {
        return { name: p.name, cuisine: p.cuisine, intro: p.intro,
                 address: p.address, phone: p.phone, hours: p.hours,
                 cover: p.cover, logo: p.logo,
                 // 重选点后一起提交，后端会顺手重算 distance
                 lat: p.lat, lng: p.lng };
      },
      local: function (p) {
        var s = cache.me || findShop(merchantShopId());
        if (!s) return false;
        ['name', 'cuisine', 'intro', 'address', 'phone', 'hours', 'cover', 'logo']
          .forEach(function (k) { if (p[k] != null) s[k] = p[k]; });
        // 坐标两个都传才认 —— 只传半边会落到 (新lat, 旧lng) 这个不存在的位置
        if (p.lat != null && p.lng != null) { s.lat = p.lat; s.lng = p.lng; }
        return true;
      },
      apply: function (data) {
        /*
         * ⚠️ 这里**不能**写 `cache.shops = [data]; MOCK.shops = cache.shops;`。
         *
         * 这是商家端「我这一家店」的口径，但 `MOCK.shops` 是**全局店铺池**。
         * 把池子整个换掉会连锁出事：
         *   · 商家端 `MOCK.buildFeed()` 之类的跨店逻辑瞬间只剩 1 家店
         *   · 若之后有人在同一浏览器里切到客户端，池子已被污染
         *     （客户端同源、共享 localStorage 与 MOCK）
         *   · 并且新数组与 cache 里原有对象**不再是同一引用**，
         *     后续改这家的字段会漏改另一个
         *
         * 正确做法：就地更新 cache 里的那一家，MOCK.shops 交给 hydrateMock 同步。
         */
        cache.me = data;
        var cur = findShop(data && data.id);
        if (cur && cur !== data) {
          Object.keys(data).forEach(function (k) { cur[k] = data[k]; });
          cache.me = cur;
        } else if (!cur && data) {
          cache.shops.push(data);
          cache.me = data;
        }
        // 商家端页面读的是「我这一家」，所以 cache.shops 仍然只放它一个 ——
        // 但 MOCK.shops 保持全局池，由 hydrateMock 负责镜像
        if (cache.me) cache.shops = [cache.me];
      }
    },

    /** 商家回评 */
    'merchant.comment.reply': {
      path: function (p) { return '/merchant/comment/' + p.id + '/reply'; },
      body: function (p) { return { content: p.content }; },
      local: function (p) {
        var c = findComment(p.id);
        if (!c) return false;
        c.reply = { content: p.content, at: nowStr() };
        return true;
      },
      apply: function (data) {
        if (window.MOCK) upsert(window.MOCK.comments, data);
        upsert(cache.comments, data);
      }
    },

    /** 清除回评 */
    'merchant.comment.clearReply': {
      method: 'DELETE',
      path: function (p) { return '/merchant/comment/' + p.id + '/reply'; },
      body: function () { return null; },
      local: function (p) {
        var c = findComment(p.id);
        if (!c) return false;
        c.reply = null;
        return true;
      },
      apply: function (data) {
        if (window.MOCK) upsert(window.MOCK.comments, data);
        upsert(cache.comments, data);
      }
    },

    /** 入驻申请：落成一家待审核店铺，并把商家身份切到新店 */
    'merchant.apply': {
      path: function () { return '/merchant/apply'; },
      body: function (p) {
        return { name: p.name, cuisine: p.cuisine, phone: p.phone,
                 address: p.address, city: p.city, district: p.district,
                 hours: p.hours, intro: p.intro,
                 cover: p.cover, logo: p.logo,
                 // 地图选点结果。没选点时为 undefined，序列化后字段直接消失，
                 // 后端 num() 收到 null → 存 null（不是 0，0 会被当成「就在你脚下」）
                 lat: p.lat, lng: p.lng };
      },
      local: function (p) {
        var M = window.MOCK;
        if (!M) return false;
        var s = {
          id: 'p_' + Date.now(),
          name: p.name, cuisine: p.cuisine, phone: p.phone,
          address: p.address, city: p.city || '仪征市', district: p.district || '真州镇',
          hours: p.hours, intro: p.intro,
          cover: p.cover, logo: p.logo || p.cover,
          lat: p.lat != null ? p.lat : null,   // 商家选的坐标：存下来但此刻不参与排序
          lng: p.lng != null ? p.lng : null,
          status: 'pending',
          distance: null,          // 待审核店没有定位，见 docs/00 的坑位说明
          weight: 0, pinned: false, canPostToday: false,
          intervalHours: 24, dailyLimit: 1,
          submittedAt: nowStr(),
          stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 }
        };
        upsert(M.pendingShops, s);
        cache.me = s;
        try { localStorage.setItem('merchantShopId', s.id); } catch (e) {}
        return true;
      },
      apply: function (data) {
        var M = window.MOCK;
        if (M) upsert(M.pendingShops, data);
        // 入驻后商家身份切到新店，审核页才查得到它的状态
        try { localStorage.setItem('merchantShopId', data.id); } catch (e) {}
        applyMerchantData({
          shop: data, dishes: [], comments: [],
          config: { priceTiers: cache.priceTiers, tasteTags: cache.tasteTags,
                    cuisines: cache.cuisines, publishRule: cache.publishRule },
          canPostToday: false, cooldownSeconds: 0
        });
      }
    }
  };

  /**
   * 互动（点赞 / 收藏）动作工厂。
   *
   * 后端接口是 /client/dish/{id}/action，body 里带 type。
   * 但后端此刻的语义是「计数 +1」，而本地要的是「切换」——
   * 两者会打架（取消点赞时后端无法表达 -1）。所以：
   *   - 本地：toggle，决定爱心亮不亮，**这是权威**；
   *   - 后端：只在「变成点赞」时通知一次，失败静默（见 ACTIONS 里的说明）。
   */
  function interactAction(kind) {
    var type = kind === 'favorited' ? 'favorite' : 'like';
    return {
      path: function (p) { return '/client/dish/' + p.dishId + '/action'; },
      body: function () { return { type: type }; },
      local: function () { return true; },   // 本地切换由客户端自己做了，这里不用再动
      apply: function (data, p) {
        // 服务端可能回权威的 stats，用它校准计数（爱心状态仍以本地为准）
        if (data && data.id) {
          var d = findDish(data.id);
          if (d && data.stats) d.stats = data.stats;
        }
      }
    };
  }

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
      apply: function (data) {
        // MOCK.shops 与 cache.shops 是**同一个数组引用**（见 hydrateMock），
        // 所以这里只需 upsert 一次 —— 写两遍等于对同一个数组做两次，纯属多余。
        upsert(cache.shops, data);
        hydrateMock();
      }
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
   * 离线模式下的「到本市中心点」距离（km，一位小数）。
   *
   * 这是后端 {@code DistanceCalculator} 的镜像实现 —— 本地假数据模式没有 Java 可调，
   * 但审核通过时 distance 必须落地，否则那家店在「附近」里永远排最后。
   *
   * ⚠️ 两边算法必须一起改：改了后端 Haversine 而忘了这里，
   * 就会出现「本地模式审核的店距离 3.2km、连后端后变成 2.7km」这种鬼故事。
   */
  var CITY_CENTER = { lat: 32.2728, lng: 119.1845 };  // 仪征市中心（与后端 DistanceCalculator 一致）

  function localDistanceKm(lat, lng) {
    if (lat == null || lng == null) return null;
    var R = 6371.0;
    var rad = Math.PI / 180;
    var dLat = (CITY_CENTER.lat - lat) * rad;
    var dLng = (CITY_CENTER.lng - lng) * rad;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat * rad) * Math.cos(CITY_CENTER.lat * rad) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return Math.round(R * 2 * Math.asin(Math.sqrt(a)) * 10) / 10;
  }

  // ================= 客户端互动（本地 + 后端双写） =================

  /**
   * 切换点赞 / 收藏。
   *
   * 返回 { on, count }：on = 切换后的状态，count = 该菜品最新的总数。
   *
   * 三条设计要点：
   * 1. **本地先改**（MOCK.toggleInteract 写 localStorage）—— 保证刷新、
   *    翻页、切 Tab 都不丢，这是上一版最大的问题。
   * 2. **后端尽力而为** —— 在线时补一次通知；失败只 warn，不回滚。
   *    理由见 ACTIONS 里 client.like 的注释。
   * 3. **计数同时改菜品上的 stats** —— 页面上的数字要跟着动。
   *    离线时纯靠它；在线时若后端回了权威 stats 会被覆盖回来。
   */
  function toggleInteract(kind, dishId) {
    var M = window.MOCK;
    if (!M || !dishId) return { on: false, count: 0 };

    /*
     * ⚠️ 必须调 _local_ 备份，不能调 M.toggleInteract。
     *
     * install() 会把本函数挂到 window.MOCK.toggleInteract 上覆盖掉 mock.js 的原版，
     * 所以这里再调 M.toggleInteract 就是**调自己** —— 无限递归，页面直接卡死。
     * 与 getShop / getDish 等读方法同一套路：原版先备份到 _local_xxx 再覆写。
     */
    var local = M._local_toggleInteract || M.toggleInteract;
    var localIs = M._local_isInteracted || M.isInteracted;

    var before = localIs.call(M, kind, dishId);
    var r = local.call(M, kind, dishId);      // 本地权威状态
    var on = r.on;

    // 联动计数
    var d = findDish(dishId) || M.getDish(dishId);
    var key = kind === 'favorited' ? 'favorites' : 'likes';
    if (d && d.stats) {
      d.stats[key] = Math.max(0, (d.stats[key] || 0) + (on ? 1 : -1));
    } else if (M.getDish) {
      var ld = M.getDish(dishId);
      if (ld && ld.stats) ld.stats[key] = Math.max(0, (ld.stats[key] || 0) + (on ? 1 : -1));
    }

    // 后端只通知「变成点赞」这一种，取消点赞后端表达不了。
    // 用 try 包住：点赞失败不该打断用户，最多控制台留个痕。
    if (online() && on) {
      try {
        var actName = kind === 'favorited' ? 'client.favorite' : 'client.like';
        var res = act(actName, { dishId: dishId });
        if (!res.ok) console.warn('[api] 互动未能同步到后端：' + res.msg);
      } catch (e) {
        console.warn('[api] 互动未能同步到后端：' + e.message);
      }
    }

    return { on: on, count: (d && d.stats) ? d.stats[key] : 0, changed: before !== on };
  }

  // ================= 未登录引导浮层 =================

  /**
   * 页面内的登录引导浮层，**替代 confirm()**。
   *
   * 为什么必须换掉 confirm：
   * - iOS Safari 会拦掉非用户手势触发的 confirm，点了没反应；
   * - confirm 样式完全不可控，跟深色沉浸式体验格格不入；
   * - 弹两次还会被浏览器「阻止此页面显示更多对话框」直接静音。
   *
   * 用法：把原来
   *     function requireLogin() { if (...) return true; if (confirm(...)) ...; }
   * 换成
   *     function requireLogin() { return !window.EAT_API || window.EAT_API.guardLogin(); }
   * guardLogin 返回 true 表示已登录可继续，false 表示已弹出引导。
   */
  var loginSheetEl = null;

  /**
   * 当前是否跑在「有真实 DOM」的浏览器里。
   *
   * 适配层会被 Node vm 沙箱直接加载（server/tools/test_adapter.cjs），
   * 那里的 document 只是个满足基本读写的替身，没有 createElement /
   * getElementById。浮层是纯 UI 能力，在那种环境下必须优雅跳过，
   * 而不是让整个 api.js 加载失败。
   */
  function hasDom() {
    return typeof document !== 'undefined' &&
           typeof document.createElement === 'function' &&
           typeof document.getElementById === 'function';
  }

  function ensureLoginSheet() {
    if (!hasDom()) return null;
    if (loginSheetEl && document.body.contains(loginSheetEl)) return loginSheetEl;

    var mask = document.createElement('div');
    mask.className = 'eat-sheet-mask';

    var sheet = document.createElement('div');
    sheet.className = 'eat-sheet';
    sheet.innerHTML =
      '<div class="eat-sheet__handle"></div>' +
      '<div class="eat-sheet__icon">🍜</div>' +
      '<div class="eat-sheet__title">登录后才能互动</div>' +
      '<div class="eat-sheet__desc">登录后即可点赞、收藏和评论<br>浏览与刷菜无需登录</div>' +
      '<div class="eat-sheet__btns">' +
        '<button class="eat-sheet__btn eat-sheet__btn--ghost" data-act="cancel">再逛逛</button>' +
        '<button class="eat-sheet__btn eat-sheet__btn--primary" data-act="login">去登录</button>' +
      '</div>';

    mask.appendChild(sheet);
    // 挂在 .phone 里（原型是手机壳），挂不到就挂 body
    var host = document.querySelector('.phone') || document.body;
    host.appendChild(mask);

    function close() { mask.classList.remove('is-open'); }

    mask.addEventListener('click', function (e) {
      if (e.target === mask) close();
    });
    sheet.querySelector('[data-act="cancel"]').addEventListener('click', close);
    sheet.querySelector('[data-act="login"]').addEventListener('click', function () {
      close();
      location.href = 'login.html';
    });

    loginSheetEl = mask;
    return mask;
  }

  /** 未登录 → 弹出引导并返回 false；已登录 → 返回 true */
  function guardLogin() {
    if (window.MOCK && window.MOCK.currentUser && window.MOCK.currentUser.loggedIn) {
      return true;
    }
    var mask = ensureLoginSheet();
    if (mask) mask.classList.add('is-open');
    return false;
  }

  /**
   * 注入浮层样式。
   * 为什么不写进 base.css：base.css 是三端共用的浅色后台风格，
   * 这套浮层只服务客户端的深色沉浸场景，放这里不会污染后台。
   */
  function injectSheetStyle() {
    // 非浏览器环境（Node vm 跑单测等）没有真正的 document，直接跳过。
    // 这里必须防御：本函数在 install() 里无条件调用，缺了判断会让整个
    // 适配层在沙箱里加载即崩，连累所有不依赖 DOM 的用例。
    if (!hasDom()) return;
    if (document.getElementById('eat-sheet-style')) return;
    var st = document.createElement('style');
    st.id = 'eat-sheet-style';
    st.textContent = [
      '.eat-sheet-mask{position:absolute;inset:0;background:rgba(0,0,0,.6);',
      'backdrop-filter:blur(3px);z-index:200;display:none;',
      'align-items:flex-end;justify-content:center;}',
      '.eat-sheet-mask.is-open{display:flex;}',
      '.eat-sheet{width:100%;background:#1C1C1E;border-radius:20px 20px 0 0;',
      'padding:8px 24px 28px;text-align:center;color:#fff;',
      'animation:eatSheetUp .26s cubic-bezier(.4,0,.2,1);}',
      '@keyframes eatSheetUp{from{transform:translateY(100%)}to{transform:translateY(0)}}',
      '.eat-sheet__handle{width:36px;height:4px;border-radius:2px;',
      'background:rgba(255,255,255,.2);margin:0 auto 18px;}',
      '.eat-sheet__icon{font-size:38px;margin-bottom:10px;}',
      '.eat-sheet__title{font-size:17px;font-weight:600;margin-bottom:8px;}',
      '.eat-sheet__desc{font-size:13px;color:rgba(255,255,255,.55);line-height:1.7;margin-bottom:22px;}',
      '.eat-sheet__btns{display:grid;grid-template-columns:1fr 1fr;gap:10px;}',
      '.eat-sheet__btn{height:46px;border-radius:23px;font-size:15px;cursor:pointer;',
      'border:none;transition:background .15s;}',
      '.eat-sheet__btn--ghost{background:rgba(255,255,255,.12);color:#fff;}',
      '.eat-sheet__btn--ghost:hover{background:rgba(255,255,255,.2);}',
      '.eat-sheet__btn--primary{background:#FE2C55;color:#fff;font-weight:500;}',
      '.eat-sheet__btn--primary:hover{background:#E62248;}'
    ].join('');
    document.head.appendChild(st);
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
      var data = syncRequest(def.method || 'POST', def.path(payload),
                             def.body ? def.body(payload) : payload);
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

    hydrateCurrentUser();                    // 还原客户端登录态（必须在页面脚本前生效）

    window.MOCK.act = act;
    window.MOCK.isOnline = online;
    window.MOCK.preloadSync = preloadSync;   // 页面手动刷新用（如商家端审核页）
    window.MOCK.login = login;               // 登录页用
    window.MOCK.logout = logout;             // 会通知服务端作废凭证，不只是清 localStorage
    window.MOCK.clearSession = clearSession;  // 只想清本地（如 401 兜底）时才用这个
    window.MOCK.sendSmsCode = sendSmsCode;    // 「获取验证码」按钮用
    window.MOCK.upload = upload;             // 选图 / 选视频后传这里
    window.MOCK.isLoggedIn = API.isLoggedIn;

    /*
     * 客户端互动与登录引导。
     * 页面统一写 MOCK.toggleInteract(...) / MOCK.guardLogin() ——
     * 与 MOCK.getShop 等一样，页面不需要知道背后有没有后端。
     *
     * ⚠️ 覆写前必须备份原版到 _local_xxx：toggleInteract 内部要读改本地状态，
     * 而挂上去的本体也叫 toggleInteract，不留备份就会调到自己（无限递归）。
     */
    if (typeof window.MOCK.toggleInteract === 'function' && !window.MOCK._local_toggleInteract) {
      window.MOCK._local_toggleInteract = window.MOCK.toggleInteract;
    }
    if (typeof window.MOCK.isInteracted === 'function' && !window.MOCK._local_isInteracted) {
      window.MOCK._local_isInteracted = window.MOCK.isInteracted;
    }
    window.MOCK.toggleInteract = toggleInteract;
    window.MOCK.guardLogin = guardLogin;
    injectSheetStyle();                      // 浮层样式注入一次即可

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
  // 登录页跳过预加载 —— 那时候还没 token，拉聚合接口必然 401，纯属白跑一趟。
  var IS_LOGIN_PAGE = /login\.html$/i.test(location.pathname || '');
  if (enabled && !IS_LOGIN_PAGE) preloadSync();
  install();

  // 退出登录入口：侧栏是各页内联的，这里统一注入，避免逐页改
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountLogoutEntry);
  } else {
    mountLogoutEntry();
  }

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
