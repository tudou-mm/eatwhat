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

  /* ==========================================================
     数据段（自动生成）
     ----------------------------------------------------------
     由 server/tools/export_mock.py 生成，请勿手改。
     改数据请改 server/.../config/DataSeeder.java，然后重跑导出脚本。
     ========================================================== */
  /* ---------- 价格档位（平台配置） ---------- */
  priceTiers: [
    { id: 'tier_mid', name: '中等', min: 0, max: 50 },
    { id: 'tier_high', name: '高等', min: 50, max: 150 },
    { id: 'tier_ultra', name: '超高级', min: 150, max: Infinity },
  ],

  /* ---------- 口味标签库（平台配置） ---------- */
  tasteTags: ['麻辣', '清淡', '酸甜', '烧烤', '火锅', '日料', '面食', '甜点', '汤类', '小吃'],

  /* ---------- 真实性标签 ---------- */
  realTags: [
    { id: 'real',    name: '实拍认证', cls: 'tag--real'    },
    { id: 'ad',      name: '广告',     cls: 'tag--ad'      },
    { id: 'pending', name: '待核实',   cls: 'tag--pending' }
  ],

  /* ---------- 兴趣点（店铺 · 全量，含待审核 / 已驳回） ---------- */
  shops: [
    { id: 's_001', name: '蜀香小馆', logo: 'https://picsum.photos/seed/s_001logo/200/200',
      cover: 'https://picsum.photos/seed/s_001cover/800/600', address: '武侯区科华北路 12 号', city: '成都市',
      district: '武侯区', lat: 30.65, lng: 104.07, distance: 0.8, phone: '028-85123456', hours: '11:00-22:00',
      cuisine: '川菜', intro: '开了十年的苍蝇馆子，麻婆豆腐是招牌。', status: 'normal', weight: 60, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 09:30', submittedAt: '2026-09-10 08:40',
      reviewedAt: '2026-09-10 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 3200, likes: 412, favorites: 188, comments: 36, checkins: 24 } },
    { id: 's_002', name: '老李炭火烧烤', logo: 'https://picsum.photos/seed/s_002logo/200/200',
      cover: 'https://picsum.photos/seed/s_002cover/800/600', address: '武侯区一环路南三段 88 号', city: '成都市',
      district: '武侯区', lat: 30.64, lng: 104.08, distance: 1.2, phone: '028-85234567', hours: '17:00-02:00',
      cuisine: '烧烤', intro: '炭火现烤，五花肉厚切。', status: 'normal', weight: 90, pinned: true, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 08:45', submittedAt: '2026-09-09 08:40',
      reviewedAt: '2026-09-09 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 2810, likes: 356, favorites: 142, comments: 28, checkins: 19 } },
    { id: 's_004', name: '一味面馆', logo: 'https://picsum.photos/seed/s_004logo/200/200',
      cover: 'https://picsum.photos/seed/s_004cover/800/600', address: '武侯区人民南路 33 号', city: '成都市',
      district: '武侯区', lat: 30.63, lng: 104.06, distance: 0.5, phone: '028-85456789', hours: '07:00-20:00',
      cuisine: '面食', intro: '红油抄手，皮薄馅大。', status: 'normal', weight: 70, pinned: false, canPostToday: true,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-14 07:40', submittedAt: '2026-09-06 08:40',
      reviewedAt: '2026-09-06 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1180, likes: 142, favorites: 58, comments: 9, checkins: 31 } },
    { id: 's_007', name: '陈记冒菜', logo: 'https://picsum.photos/seed/s_007logo/200/200',
      cover: 'https://picsum.photos/seed/s_007cover/800/600', address: '武侯区双楠路 21 号', city: '成都市', district: '武侯区',
      lat: 30.62, lng: 104.04, distance: 1.6, phone: '028-85789012', hours: '10:30-21:30', cuisine: '川菜',
      intro: '一锅一煮，麻辣自选。', status: 'normal', weight: 80, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-15 10:20', submittedAt: '2026-09-11 08:40',
      reviewedAt: '2026-09-11 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 1640, likes: 208, favorites: 96, comments: 17, checkins: 12 } },
    { id: 's_012', name: '玉林路小酒馆', logo: 'https://picsum.photos/seed/s_012logo/200/200',
      cover: 'https://picsum.photos/seed/s_012cover/800/600', address: '武侯区玉林西路 55 号', city: '成都市',
      district: '武侯区', lat: 30.61, lng: 104.05, distance: 0.9, phone: '028-85234560', hours: '18:00-03:00',
      cuisine: '烧烤', intro: '把把烧配冰啤酒。', status: 'normal', weight: 80, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-08 08:40',
      reviewedAt: '2026-09-08 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 2260, likes: 298, favorites: 134, comments: 41, checkins: 22 } },
    { id: 's_003', name: '川味坊', logo: 'https://picsum.photos/seed/s_003logo/200/200',
      cover: 'https://picsum.photos/seed/s_003cover/800/600', address: '锦江区春熙路 5 号', city: '成都市', district: '锦江区',
      lat: 30.66, lng: 104.09, distance: 2.1, phone: '028-85345678', hours: '10:00-21:00', cuisine: '川菜',
      intro: '家常川菜，回锅肉一绝。', status: 'normal', weight: 90, pinned: true, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-15 08:20', submittedAt: '2026-09-08 08:40',
      reviewedAt: '2026-09-08 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 1420, likes: 168, favorites: 62, comments: 12, checkins: 8 } },
    { id: 's_005', name: '樱町日料', logo: 'https://picsum.photos/seed/s_005logo/200/200',
      cover: 'https://picsum.photos/seed/s_005cover/800/600', address: '锦江区红星路三段 1 号', city: '成都市',
      district: '锦江区', lat: 30.67, lng: 104.1, distance: 3.4, phone: '028-85567890', hours: '11:30-22:00',
      cuisine: '日料', intro: '每日空运，蓝鳍金枪鱼限量。', status: 'normal', weight: 90, pinned: true, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 09:05', submittedAt: '2026-09-06 08:40',
      reviewedAt: '2026-09-06 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 1960, likes: 288, favorites: 210, comments: 44, checkins: 6 } },
    { id: 's_008', name: '牛市口钵钵鸡', logo: 'https://picsum.photos/seed/s_008logo/200/200',
      cover: 'https://picsum.photos/seed/s_008cover/800/600', address: '锦江区牛市口街 9 号', city: '成都市', district: '锦江区',
      lat: 30.69, lng: 104.11, distance: 2.4, phone: '028-85890123', hours: '11:00-23:00', cuisine: '小吃',
      intro: '藤椒味最正，签子按根算。', status: 'normal', weight: 70, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-12 08:40',
      reviewedAt: '2026-09-12 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 1780, likes: 246, favorites: 118, comments: 22, checkins: 34 } },
    { id: 's_010', name: '青石桥海鲜大排档', logo: 'https://picsum.photos/seed/s_010logo/200/200',
      cover: 'https://picsum.photos/seed/s_010cover/800/600', address: '锦江区青石桥中街 7 号', city: '成都市',
      district: '锦江区', lat: 30.66, lng: 104.08, distance: 3.8, phone: '028-85012345', hours: '17:00-02:00',
      cuisine: '粤菜', intro: '现杀现做，蒜蓉粉丝扇贝。', status: 'normal', weight: 60, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-14 18:10', submittedAt: '2026-09-03 08:40',
      reviewedAt: '2026-09-03 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1520, likes: 196, favorites: 88, comments: 16, checkins: 11 } },
    { id: 's_013', name: '春熙路茶餐厅', logo: 'https://picsum.photos/seed/s_013logo/200/200',
      cover: 'https://picsum.photos/seed/s_013cover/800/600', address: '锦江区中纱帽街 12 号', city: '成都市',
      district: '锦江区', lat: 30.65, lng: 104.09, distance: 2.9, phone: '028-85345670', hours: '10:00-22:00',
      cuisine: '粤菜', intro: '菠萝油和丝袜奶茶是招牌。', status: 'normal', weight: 60, pinned: false, canPostToday: true,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-13 09:30', submittedAt: '2026-09-01 08:40',
      reviewedAt: '2026-09-01 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1340, likes: 172, favorites: 74, comments: 13, checkins: 9 } },
    { id: 's_006', name: '老字号甜水面', logo: 'https://picsum.photos/seed/s_006logo/200/200',
      cover: 'https://picsum.photos/seed/s_006cover/800/600', address: '青羊区宽窄巷子 8 号', city: '成都市', district: '青羊区',
      lat: 30.68, lng: 104.05, distance: 4.2, phone: '028-85678901', hours: '09:00-19:00', cuisine: '小吃',
      intro: '一根面拇指粗，酱料甜辣。', status: 'normal', weight: 70, pinned: false, canPostToday: true, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-14 10:15', submittedAt: '2026-09-05 08:40',
      reviewedAt: '2026-09-05 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 980, likes: 176, favorites: 84, comments: 15, checkins: 47 } },
    { id: 's_011', name: '老成都锅盔', logo: 'https://picsum.photos/seed/s_011logo/200/200',
      cover: 'https://picsum.photos/seed/s_011cover/800/600', address: '青羊区文殊院街 15 号', city: '成都市',
      district: '青羊区', lat: 30.67, lng: 104.06, distance: 4.6, phone: '028-85123450', hours: '07:30-19:00',
      cuisine: '小吃', intro: '军屯锅盔，现烤现卖。', status: 'normal', weight: 70, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-07 08:40',
      reviewedAt: '2026-09-07 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1120, likes: 158, favorites: 66, comments: 14, checkins: 28 } },
    { id: 's_009', name: '高新串串实验室', logo: 'https://picsum.photos/seed/s_009logo/200/200',
      cover: 'https://picsum.photos/seed/s_009cover/800/600', address: '高新区天府三街 199 号', city: '成都市',
      district: '高新区', lat: 30.55, lng: 104.06, distance: 5.2, phone: '028-85901234', hours: '16:00-01:00',
      cuisine: '火锅', intro: '锅底自己配，牛油现炒。', status: 'normal', weight: 95, pinned: true, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 09:50', submittedAt: '2026-09-13 08:40',
      reviewedAt: '2026-09-13 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 4, views: 3480, likes: 512, favorites: 264, comments: 58, checkins: 33 } },
    { id: 's_016', name: '天府三街寿司郎', logo: 'https://picsum.photos/seed/s_016logo/200/200',
      cover: 'https://picsum.photos/seed/s_016cover/800/600', address: '高新区天府三街 288 号', city: '成都市',
      district: '高新区', lat: 30.54, lng: 104.08, distance: 8.4, phone: '028-85678900', hours: '11:00-22:30',
      cuisine: '日料', intro: '回转寿司，人均亲民。', status: 'normal', weight: 60, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-14 11:40', submittedAt: '2026-08-30 08:40',
      reviewedAt: '2026-08-30 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1060, likes: 138, favorites: 58, comments: 11, checkins: 7 } },
    { id: 's_014', name: '建设路烤鱼', logo: 'https://picsum.photos/seed/s_014logo/200/200',
      cover: 'https://picsum.photos/seed/s_014cover/800/600', address: '成华区建设路 26 号', city: '成都市', district: '成华区',
      lat: 30.67, lng: 104.13, distance: 6.1, phone: '028-85456780', hours: '16:30-01:00', cuisine: '火锅',
      intro: '万州烤鱼，麻辣与蒜香双拼。', status: 'normal', weight: 60, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-10 08:40',
      reviewedAt: '2026-09-10 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 3, views: 1880, likes: 264, favorites: 124, comments: 26, checkins: 18 } },
    { id: 's_017', name: '玉双路糖水铺', logo: 'https://picsum.photos/seed/s_017logo/200/200',
      cover: 'https://picsum.photos/seed/s_017cover/800/600', address: '成华区玉双路 3 号', city: '成都市', district: '成华区',
      lat: 30.66, lng: 104.12, distance: 5.5, phone: '028-85789010', hours: '12:00-23:00', cuisine: '甜品',
      intro: '广式糖水，姜撞奶现撞。', status: 'normal', weight: 50, pinned: false, canPostToday: true, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-13 13:30', submittedAt: '2026-09-04 08:40',
      reviewedAt: '2026-09-04 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 860, likes: 214, favorites: 132, comments: 19, checkins: 41 } },
    { id: 's_019', name: '东郊记忆西餐厅', logo: 'https://picsum.photos/seed/s_019logo/200/200',
      cover: 'https://picsum.photos/seed/s_019cover/800/600', address: '成华区建设南支路 4 号', city: '成都市',
      district: '成华区', lat: 30.65, lng: 104.14, distance: 9.8, phone: '028-85901230', hours: '11:00-22:00',
      cuisine: '西餐', intro: '牛排现切，环境安静。', status: 'normal', weight: 50, pinned: false, canPostToday: true,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-13 19:10', submittedAt: '2026-09-02 08:40',
      reviewedAt: '2026-09-02 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 740, likes: 96, favorites: 44, comments: 8, checkins: 4 } },
    { id: 's_015', name: '抚琴豆花面', logo: 'https://picsum.photos/seed/s_015logo/200/200',
      cover: 'https://picsum.photos/seed/s_015cover/800/600', address: '金牛区抚琴西路 44 号', city: '成都市',
      district: '金牛区', lat: 30.7, lng: 104.05, distance: 7.3, phone: '028-85567890', hours: '06:30-14:00',
      cuisine: '面食', intro: '豆花嫩，红油香。', status: 'normal', weight: 60, pinned: false, canPostToday: true,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-14 07:10', submittedAt: '2026-08-31 08:40',
      reviewedAt: '2026-08-31 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 920, likes: 128, favorites: 52, comments: 10, checkins: 16 } },
    { id: 's_018', name: '湘遇小炒', logo: 'https://picsum.photos/seed/s_018logo/200/200',
      cover: 'https://picsum.photos/seed/s_018cover/800/600', address: '金牛区解放路二段 18 号', city: '成都市',
      district: '金牛区', lat: 30.68, lng: 104.03, distance: 6.8, phone: '028-85890120', hours: '11:00-21:30',
      cuisine: '湘菜', intro: '剁椒鱼头够辣。', status: 'normal', weight: 60, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-14 12:50', submittedAt: '2026-09-03 08:40',
      reviewedAt: '2026-09-03 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1240, likes: 164, favorites: 72, comments: 21, checkins: 9 } },
    { id: 's_020', name: '老绵阳米粉', logo: 'https://picsum.photos/seed/s_020logo/200/200',
      cover: 'https://picsum.photos/seed/s_020cover/800/600', address: '成华区双桥路 18 号', city: '成都市', district: '成华区',
      lat: 30.66, lng: 104.11, distance: 1.4, phone: '028-86123456', hours: '06:00-14:00', cuisine: '小吃',
      intro: '米粉细滑，红汤清汤都行。', status: 'normal', weight: 80, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-15 07:20', submittedAt: '2026-09-09 08:40',
      reviewedAt: '2026-09-09 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1660, likes: 232, favorites: 104, comments: 27, checkins: 38 } },
    { id: 's_021', name: '老码头火锅', logo: 'https://picsum.photos/seed/s_021logo/200/200',
      cover: 'https://picsum.photos/seed/s_021cover/800/600', address: '金牛区西安中路 9 号', city: '成都市', district: '金牛区',
      lat: 30.67, lng: 104.04, distance: 2.7, phone: '028-86234567', hours: '17:00-02:00', cuisine: '火锅',
      intro: '老码头牛油锅，本地人常去。', status: 'normal', weight: 70, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-14 18:30', submittedAt: '2026-09-07 08:40',
      reviewedAt: '2026-09-07 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1480, likes: 206, favorites: 92, comments: 24, checkins: 15 } },
    { id: 's_022', name: '炭匠烤肉', logo: 'https://picsum.photos/seed/s_022logo/200/200',
      cover: 'https://picsum.photos/seed/s_022cover/800/600', address: '武侯区外双楠 88 号', city: '成都市', district: '武侯区',
      lat: 30.6, lng: 104.03, distance: 3.3, phone: '028-86345678', hours: '17:30-01:00', cuisine: '烧烤',
      intro: '大块牛排串，分量足。', status: 'normal', weight: 60, pinned: false, canPostToday: true, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '2026-09-13 20:00', submittedAt: '2026-09-05 08:40',
      reviewedAt: '2026-09-05 14:20', reviewer: '平台运营', rejectReason: null,
      stats: { dishes: 2, views: 1020, likes: 144, favorites: 64, comments: 12, checkins: 6 } },
    { id: 'p_001', name: '新开的螺蛳粉', logo: 'https://picsum.photos/seed/p_001logo/200/200',
      cover: 'https://picsum.photos/seed/p_001cover/800/600', address: '成华区建设路 66 号', city: '成都市', district: '成华区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88887777', hours: '10:00-23:00', cuisine: '小吃',
      intro: '正宗柳州味道，酸笋每天现发。', status: 'pending', weight: 0, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-14 09:15', reviewedAt: null, reviewer: null,
      rejectReason: null, stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_002', name: '巷子口串串香', logo: 'https://picsum.photos/seed/p_002logo/200/200',
      cover: 'https://picsum.photos/seed/p_002cover/800/600', address: '金牛区抚琴西路 8 号', city: '成都市', district: '金牛区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88888888', hours: '16:00-03:00', cuisine: '火锅',
      intro: '老巷子里的苍蝇馆子，开了七年。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-14 10:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_003', name: '深夜豆浆油条', logo: 'https://picsum.photos/seed/p_003logo/200/200',
      cover: 'https://picsum.photos/seed/p_003cover/800/600', address: '锦江区东大街 41 号', city: '成都市', district: '锦江区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88889999', hours: '22:00-06:00', cuisine: '小吃',
      intro: '专做夜宵档，豆浆现磨。', status: 'pending', weight: 0, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-15 08:15', reviewedAt: null, reviewer: null,
      rejectReason: null, stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_004', name: '城南潮汕牛肉锅', logo: 'https://picsum.photos/seed/p_004logo/200/200',
      cover: 'https://picsum.photos/seed/p_004cover/800/600', address: '高新区府城大道 128 号', city: '成都市',
      district: '高新区', lat: 31, lng: 104.3, distance: null, phone: '028-88886666', hours: '11:00-23:00',
      cuisine: '火锅', intro: '现宰黄牛，八秒吊龙。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-15 09:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_005', name: '锦江老面馆', logo: 'https://picsum.photos/seed/p_005logo/200/200',
      cover: 'https://picsum.photos/seed/p_005cover/800/600', address: '锦江区梨花街 22 号', city: '成都市', district: '锦江区',
      lat: 31, lng: 104.3, distance: null, phone: '0816-2288999', hours: '06:30-20:00', cuisine: '面食',
      intro: '开了二十年的老面馆，杂酱面最出名。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-13 11:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_r1', name: '无名小摊', logo: 'https://picsum.photos/seed/p_r1logo/200/200',
      cover: 'https://picsum.photos/seed/p_r1cover/800/600', address: '成都市某处', city: '成都市', district: '金牛区',
      lat: 31, lng: 104.3, distance: null, phone: '028-00000000', hours: '不定', cuisine: '小吃', intro: '',
      status: 'rejected', weight: 0, pinned: false, canPostToday: false, intervalHours: 24, dailyLimit: 1,
      lastPostAt: '', submittedAt: '2026-09-08 09:00', reviewedAt: '2026-09-09 10:20', reviewer: '平台运营',
      rejectReason: '门头图不清晰，无法辨认店铺招牌，且未填写详细地址',
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_r2', name: '皇家御膳私房菜', logo: 'https://picsum.photos/seed/p_r2logo/200/200',
      cover: 'https://picsum.photos/seed/p_r2cover/800/600', address: '武侯区某写字楼', city: '成都市', district: '武侯区',
      lat: 31, lng: 104.3, distance: null, phone: '028-00000000', hours: '不定', cuisine: '川菜', intro: '主打高端宴请。',
      status: 'rejected', weight: 0, pinned: false, canPostToday: false, intervalHours: 24, dailyLimit: 1,
      lastPostAt: '', submittedAt: '2026-09-11 09:00', reviewedAt: '2026-09-12 10:20', reviewer: '平台运营',
      rejectReason: '营业执照与经营主体不一致，且上传的门头照为效果图而非实拍',
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
  ],

  /* ---------- 菜品（仅上架 normal） ---------- */
  dishes: [
    { id: 'd_010', shopId: 's_014', shopName: '建设路烤鱼', name: '万州烤鱼', desc: '先烤后炖，麻辣与蒜香双拼。', type: 'image',
      media: ['https://picsum.photos/seed/d_010a/800/1200', 'https://picsum.photos/seed/d_010b/800/1200'],
      cover: 'https://picsum.photos/seed/d_010a/800/1200', price: 108, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-15 10:33', status: 'normal',
      stats: { views: 1880, likes: 264, favorites: 124, comments: 26, checkins: 18 } },
    { id: 'd_009', shopId: 's_012', shopName: '玉林路小酒馆', name: '把把烧', desc: '小串一次上二十把，配冰啤酒。', type: 'video',
      media: ['https://picsum.photos/seed/d_009a/800/1200'], cover: 'https://picsum.photos/seed/d_009a/800/1200',
      price: 58, priceTierId: 'tier_high', realTag: 'real', tasteTags: ['烧烤', '麻辣'],
      publishedAt: '2026-09-15 10:33', status: 'normal',
      stats: { views: 2260, likes: 298, favorites: 134, comments: 41, checkins: 22 } },
    { id: 'd_008', shopId: 's_011', shopName: '老成都锅盔', name: '军屯锅盔', desc: '现烤现卖，椒盐味最传统。', type: 'image',
      media: ['https://picsum.photos/seed/d_008a/800/1200', 'https://picsum.photos/seed/d_008b/800/1200'],
      cover: 'https://picsum.photos/seed/d_008a/800/1200', price: 10, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['小吃'], publishedAt: '2026-09-15 10:33', status: 'normal',
      stats: { views: 1120, likes: 158, favorites: 66, comments: 14, checkins: 28 } },
    { id: 'd_006', shopId: 's_008', shopName: '牛市口钵钵鸡', name: '藤椒钵钵鸡', desc: '藤椒油现淋，麻得清爽。', type: 'image',
      media: ['https://picsum.photos/seed/d_006a/800/1200', 'https://picsum.photos/seed/d_006b/800/1200'],
      cover: 'https://picsum.photos/seed/d_006a/800/1200', price: 38, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '小吃'], publishedAt: '2026-09-15 10:33', status: 'normal',
      stats: { views: 1780, likes: 246, favorites: 118, comments: 22, checkins: 34 } },
    { id: 'd_005', shopId: 's_007', shopName: '陈记冒菜', name: '冒菜小锅', desc: '一锅一煮，麻辣度可选。', type: 'image',
      media: ['https://picsum.photos/seed/d_005a/800/1200', 'https://picsum.photos/seed/d_005b/800/1200'],
      cover: 'https://picsum.photos/seed/d_005a/800/1200', price: 36, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-15 10:20', status: 'normal',
      stats: { views: 1640, likes: 208, favorites: 96, comments: 17, checkins: 12 } },
    { id: 'd_007', shopId: 's_009', shopName: '高新串串实验室', name: '现炒牛油锅底', desc: '牛油加二十余味香料现炒，端上桌还在滚。',
      type: 'video', media: ['https://picsum.photos/seed/d_007a/800/1200'],
      cover: 'https://picsum.photos/seed/d_007a/800/1200', price: 58, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-15 09:50', status: 'normal',
      stats: { views: 3480, likes: 512, favorites: 264, comments: 58, checkins: 33 } },
    { id: 'd_001', shopId: 's_001', shopName: '蜀香小馆', name: '麻婆豆腐', desc: '石磨豆腐配自家花椒，麻辣鲜香。', type: 'image',
      media: ['https://picsum.photos/seed/d_001a/800/1200', 'https://picsum.photos/seed/d_001b/800/1200'],
      cover: 'https://picsum.photos/seed/d_001a/800/1200', price: 28, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-15 09:30', status: 'normal',
      stats: { views: 3200, likes: 412, favorites: 188, comments: 36, checkins: 24 } },
    { id: 'd_004', shopId: 's_005', shopName: '樱町日料', name: '蓝鳍金枪鱼大腹', desc: '每日空运，限量六份。油脂分布像雪花一样。', type: 'image',
      media: ['https://picsum.photos/seed/d_004a/800/1200', 'https://picsum.photos/seed/d_004b/800/1200'],
      cover: 'https://picsum.photos/seed/d_004a/800/1200', price: 288, priceTierId: 'tier_ultra', realTag: 'real',
      tasteTags: ['日料', '清淡'], publishedAt: '2026-09-15 09:05', status: 'normal',
      stats: { views: 1960, likes: 288, favorites: 210, comments: 44, checkins: 6 } },
    { id: 'd_002', shopId: 's_002', shopName: '老李炭火烧烤', name: '炭烤五花肉', desc: '厚切带皮五花，炭火慢烤四十分钟，外焦里嫩。',
      type: 'video', media: ['https://picsum.photos/seed/d_002a/800/1200'],
      cover: 'https://picsum.photos/seed/d_002a/800/1200', price: 68, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['烧烤'], publishedAt: '2026-09-15 08:45', status: 'normal',
      stats: { views: 2810, likes: 356, favorites: 142, comments: 28, checkins: 19 } },
    { id: 'd_003', shopId: 's_003', shopName: '川味坊', name: '回锅肉', desc: '二刀肉先煮后炒，豆瓣是自家晒的。', type: 'image',
      media: ['https://picsum.photos/seed/d_003a/800/1200', 'https://picsum.photos/seed/d_003b/800/1200'],
      cover: 'https://picsum.photos/seed/d_003a/800/1200', price: 42, priceTierId: 'tier_mid', realTag: 'ad',
      tasteTags: ['麻辣'], publishedAt: '2026-09-15 08:20', status: 'normal',
      stats: { views: 1420, likes: 168, favorites: 62, comments: 12, checkins: 8 } },
    { id: 'd_011', shopId: 's_020', shopName: '老绵阳米粉', name: '绵阳米粉', desc: '红汤米粉，臊子是牛肉。', type: 'image',
      media: ['https://picsum.photos/seed/d_011a/800/1200', 'https://picsum.photos/seed/d_011b/800/1200'],
      cover: 'https://picsum.photos/seed/d_011a/800/1200', price: 12, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '小吃', '面食'], publishedAt: '2026-09-15 07:20', status: 'normal',
      stats: { views: 1660, likes: 232, favorites: 104, comments: 27, checkins: 38 } },
    { id: 'd_020', shopId: 's_021', shopName: '老码头火锅', name: '越王楼牛油锅', desc: '本地牛油锅，香而不燥。', type: 'image',
      media: ['https://picsum.photos/seed/d_020a/800/1200', 'https://picsum.photos/seed/d_020b/800/1200'],
      cover: 'https://picsum.photos/seed/d_020a/800/1200', price: 68, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-14 18:30', status: 'normal',
      stats: { views: 1480, likes: 206, favorites: 92, comments: 24, checkins: 15 } },
    { id: 'd_016', shopId: 's_010', shopName: '青石桥海鲜大排档', name: '蒜蓉粉丝扇贝', desc: '现杀现做，粉丝吸满蒜蓉汁。', type: 'image',
      media: ['https://picsum.photos/seed/d_016a/800/1200', 'https://picsum.photos/seed/d_016b/800/1200'],
      cover: 'https://picsum.photos/seed/d_016a/800/1200', price: 78, priceTierId: 'tier_high', realTag: 'ad',
      tasteTags: ['清淡'], publishedAt: '2026-09-14 18:10', status: 'normal',
      stats: { views: 1520, likes: 196, favorites: 88, comments: 16, checkins: 11 } },
    { id: 'd_015', shopId: 's_009', shopName: '高新串串实验室', name: '手打虾滑', desc: '整只青虾手打，弹牙。', type: 'video',
      media: ['https://picsum.photos/seed/d_015a/800/1200'], cover: 'https://picsum.photos/seed/d_015a/800/1200',
      price: 46, priceTierId: 'tier_mid', realTag: 'real', tasteTags: ['清淡', '火锅'],
      publishedAt: '2026-09-14 17:30', status: 'normal',
      stats: { views: 2980, likes: 436, favorites: 228, comments: 52, checkins: 29 } },
    { id: 'd_019', shopId: 's_018', shopName: '湘遇小炒', name: '剁椒鱼头', desc: '剁椒自家腌，鱼头两斤起。', type: 'image',
      media: ['https://picsum.photos/seed/d_019a/800/1200', 'https://picsum.photos/seed/d_019b/800/1200'],
      cover: 'https://picsum.photos/seed/d_019a/800/1200', price: 88, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-14 12:50', status: 'normal',
      stats: { views: 1240, likes: 164, favorites: 72, comments: 21, checkins: 9 } },
    { id: 'd_012', shopId: 's_003', shopName: '川味坊', name: '蒜泥白肉', desc: '肉片薄可透光，蒜泥现舂。', type: 'image',
      media: ['https://picsum.photos/seed/d_012a/800/1200', 'https://picsum.photos/seed/d_012b/800/1200'],
      cover: 'https://picsum.photos/seed/d_012a/800/1200', price: 46, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-14 12:30', status: 'normal',
      stats: { views: 1280, likes: 154, favorites: 58, comments: 11, checkins: 7 } },
    { id: 'd_018', shopId: 's_016', shopName: '天府三街寿司郎', name: '回转寿司拼盘', desc: '八贯拼盘，师傅现场捏。', type: 'image',
      media: ['https://picsum.photos/seed/d_018a/800/1200', 'https://picsum.photos/seed/d_018b/800/1200'],
      cover: 'https://picsum.photos/seed/d_018a/800/1200', price: 88, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['日料', '清淡'], publishedAt: '2026-09-14 11:40', status: 'normal',
      stats: { views: 1060, likes: 138, favorites: 58, comments: 11, checkins: 7 } },
    { id: 'd_014', shopId: 's_006', shopName: '老字号甜水面', name: '甜水面', desc: '一根面有拇指粗，酱料甜中带辣。', type: 'image',
      media: ['https://picsum.photos/seed/d_014a/800/1200', 'https://picsum.photos/seed/d_014b/800/1200'],
      cover: 'https://picsum.photos/seed/d_014a/800/1200', price: 12, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['小吃', '面食', '甜点'], publishedAt: '2026-09-14 10:15', status: 'normal',
      stats: { views: 980, likes: 176, favorites: 84, comments: 15, checkins: 47 } },
    { id: 'd_013', shopId: 's_004', shopName: '一味面馆', name: '红油抄手', desc: '皮薄如纸，红油是自家舂的辣椒。', type: 'image',
      media: ['https://picsum.photos/seed/d_013a/800/1200', 'https://picsum.photos/seed/d_013b/800/1200'],
      cover: 'https://picsum.photos/seed/d_013a/800/1200', price: 18, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '面食'], publishedAt: '2026-09-14 07:40', status: 'normal',
      stats: { views: 1180, likes: 142, favorites: 58, comments: 9, checkins: 31 } },
    { id: 'd_017', shopId: 's_015', shopName: '抚琴豆花面', name: '豆花面', desc: '豆花嫩，红油香，面是细的。', type: 'image',
      media: ['https://picsum.photos/seed/d_017a/800/1200', 'https://picsum.photos/seed/d_017b/800/1200'],
      cover: 'https://picsum.photos/seed/d_017a/800/1200', price: 14, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '面食'], publishedAt: '2026-09-14 07:10', status: 'normal',
      stats: { views: 920, likes: 128, favorites: 52, comments: 10, checkins: 16 } },
    { id: 'd_029', shopId: 's_022', shopName: '炭匠烤肉', name: '大块牛排串', desc: '牛排切块串起来烤，分量足。', type: 'video',
      media: ['https://picsum.photos/seed/d_029a/800/1200'], cover: 'https://picsum.photos/seed/d_029a/800/1200',
      price: 118, priceTierId: 'tier_high', realTag: 'real', tasteTags: ['烧烤'], publishedAt: '2026-09-13 20:00',
      status: 'normal', stats: { views: 1020, likes: 144, favorites: 64, comments: 12, checkins: 6 } },
    { id: 'd_024', shopId: 's_012', shopName: '玉林路小酒馆', name: '烤鸡皮', desc: '烤到起泡，脆得响。', type: 'image',
      media: ['https://picsum.photos/seed/d_024a/800/1200', 'https://picsum.photos/seed/d_024b/800/1200'],
      cover: 'https://picsum.photos/seed/d_024a/800/1200', price: 28, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['烧烤'], publishedAt: '2026-09-13 19:50', status: 'normal',
      stats: { views: 2180, likes: 282, favorites: 128, comments: 38, checkins: 20 } },
    { id: 'd_026', shopId: 's_014', shopName: '建设路烤鱼', name: '蒜香烤鱼', desc: '蒜香版本，不吃辣也能吃。', type: 'image',
      media: ['https://picsum.photos/seed/d_026a/800/1200', 'https://picsum.photos/seed/d_026b/800/1200'],
      cover: 'https://picsum.photos/seed/d_026a/800/1200', price: 98, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['清淡', '火锅'], publishedAt: '2026-09-13 18:40', status: 'normal',
      stats: { views: 1820, likes: 248, favorites: 116, comments: 24, checkins: 16 } },
    { id: 'd_027', shopId: 's_017', shopName: '玉双路糖水铺', name: '姜撞奶', desc: '现撞现凝，姜味冲。', type: 'image',
      media: ['https://picsum.photos/seed/d_027a/800/1200', 'https://picsum.photos/seed/d_027b/800/1200'],
      cover: 'https://picsum.photos/seed/d_027a/800/1200', price: 22, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['甜点', '清淡'], publishedAt: '2026-09-13 13:30', status: 'normal',
      stats: { views: 860, likes: 214, favorites: 132, comments: 19, checkins: 41 } },
    { id: 'd_023', shopId: 's_008', shopName: '牛市口钵钵鸡', name: '红油钵钵鸡', desc: '经典红油，微甜收口。', type: 'image',
      media: ['https://picsum.photos/seed/d_023a/800/1200', 'https://picsum.photos/seed/d_023b/800/1200'],
      cover: 'https://picsum.photos/seed/d_023a/800/1200', price: 38, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '小吃'], publishedAt: '2026-09-13 12:20', status: 'normal',
      stats: { views: 1720, likes: 232, favorites: 110, comments: 20, checkins: 31 } },
    { id: 'd_021', shopId: 's_001', shopName: '蜀香小馆', name: '水煮牛肉', desc: '牛肉片现片，热油一泼。', type: 'image',
      media: ['https://picsum.photos/seed/d_021a/800/1200', 'https://picsum.photos/seed/d_021b/800/1200'],
      cover: 'https://picsum.photos/seed/d_021a/800/1200', price: 48, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-13 12:10', status: 'normal',
      stats: { views: 3120, likes: 388, favorites: 172, comments: 33, checkins: 21 } },
    { id: 'd_022', shopId: 's_007', shopName: '陈记冒菜', name: '藤椒鸡片', desc: '藤椒清麻，鸡肉嫩。', type: 'image',
      media: ['https://picsum.photos/seed/d_022a/800/1200', 'https://picsum.photos/seed/d_022b/800/1200'],
      cover: 'https://picsum.photos/seed/d_022a/800/1200', price: 42, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-13 11:50', status: 'normal',
      stats: { views: 1580, likes: 196, favorites: 88, comments: 15, checkins: 10 } },
    { id: 'd_025', shopId: 's_013', shopName: '春熙路茶餐厅', name: '菠萝油', desc: '现烤菠萝包夹冰黄油。', type: 'image',
      media: ['https://picsum.photos/seed/d_025a/800/1200', 'https://picsum.photos/seed/d_025b/800/1200'],
      cover: 'https://picsum.photos/seed/d_025a/800/1200', price: 18, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['甜点'], publishedAt: '2026-09-13 09:30', status: 'normal',
      stats: { views: 1340, likes: 172, favorites: 74, comments: 13, checkins: 9 } },
    { id: 'd_030', shopId: 's_002', shopName: '老李炭火烧烤', name: '烤脑花', desc: '麻辣重口，老饕最爱。', type: 'image',
      media: ['https://picsum.photos/seed/d_030a/800/1200', 'https://picsum.photos/seed/d_030b/800/1200'],
      cover: 'https://picsum.photos/seed/d_030a/800/1200', price: 45, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '烧烤'], publishedAt: '2026-09-12 19:20', status: 'normal',
      stats: { views: 2760, likes: 342, favorites: 138, comments: 27, checkins: 18 } },
    { id: 'd_032', shopId: 's_009', shopName: '高新串串实验室', name: '鲜毛肚', desc: '当天现发，七上八下。', type: 'video',
      media: ['https://picsum.photos/seed/d_032a/800/1200'], cover: 'https://picsum.photos/seed/d_032a/800/1200',
      price: 52, priceTierId: 'tier_high', realTag: 'real', tasteTags: ['麻辣', '火锅'],
      publishedAt: '2026-09-12 18:20', status: 'normal',
      stats: { views: 3320, likes: 486, favorites: 248, comments: 55, checkins: 31 } },
    { id: 'd_031', shopId: 's_005', shopName: '樱町日料', name: '三文鱼刺身拼盘', desc: '厚切三文鱼配现磨山葵。', type: 'image',
      media: ['https://picsum.photos/seed/d_031a/800/1200', 'https://picsum.photos/seed/d_031b/800/1200'],
      cover: 'https://picsum.photos/seed/d_031a/800/1200', price: 138, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['日料', '清淡'], publishedAt: '2026-09-12 12:00', status: 'normal',
      stats: { views: 1920, likes: 276, favorites: 198, comments: 42, checkins: 6 } },
    { id: 'd_034', shopId: 's_018', shopName: '湘遇小炒', name: '小炒黄牛肉', desc: '黄牛肉配小米辣，下饭。', type: 'image',
      media: ['https://picsum.photos/seed/d_034a/800/1200', 'https://picsum.photos/seed/d_034b/800/1200'],
      cover: 'https://picsum.photos/seed/d_034a/800/1200', price: 68, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-12 11:20', status: 'normal',
      stats: { views: 1180, likes: 154, favorites: 68, comments: 19, checkins: 8 } },
    { id: 'd_033', shopId: 's_011', shopName: '老成都锅盔', name: '牛肉锅盔', desc: '夹牛肉馅的，一个顶俩。', type: 'image',
      media: ['https://picsum.photos/seed/d_033a/800/1200', 'https://picsum.photos/seed/d_033b/800/1200'],
      cover: 'https://picsum.photos/seed/d_033a/800/1200', price: 14, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['小吃'], publishedAt: '2026-09-12 08:30', status: 'normal',
      stats: { views: 1080, likes: 146, favorites: 62, comments: 13, checkins: 26 } },
    { id: 'd_035', shopId: 's_020', shopName: '老绵阳米粉', name: '清汤米粉', desc: '清汤底更鲜，早上吃一碗。', type: 'image',
      media: ['https://picsum.photos/seed/d_035a/800/1200', 'https://picsum.photos/seed/d_035b/800/1200'],
      cover: 'https://picsum.photos/seed/d_035a/800/1200', price: 10, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['清淡', '汤类', '小吃'], publishedAt: '2026-09-12 06:50', status: 'normal',
      stats: { views: 1600, likes: 218, favorites: 98, comments: 25, checkins: 36 } },
    { id: 'd_039', shopId: 's_010', shopName: '青石桥海鲜大排档', name: '白灼虾', desc: '活虾白灼，蘸生抽小米辣。', type: 'image',
      media: ['https://picsum.photos/seed/d_039a/800/1200', 'https://picsum.photos/seed/d_039b/800/1200'],
      cover: 'https://picsum.photos/seed/d_039a/800/1200', price: 98, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['清淡'], publishedAt: '2026-09-11 19:30', status: 'normal',
      stats: { views: 1460, likes: 188, favorites: 82, comments: 15, checkins: 10 } },
    { id: 'd_036', shopId: 's_003', shopName: '川味坊', name: '麻婆豆腐盖饭', desc: '一人食版本，配一碗米饭。', type: 'image',
      media: ['https://picsum.photos/seed/d_036a/800/1200', 'https://picsum.photos/seed/d_036b/800/1200'],
      cover: 'https://picsum.photos/seed/d_036a/800/1200', price: 26, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-11 18:50', status: 'normal',
      stats: { views: 1360, likes: 158, favorites: 60, comments: 11, checkins: 7 } },
    { id: 'd_041', shopId: 's_021', shopName: '老码头火锅', name: '鲜鸭肠', desc: '烫八秒，脆得很。', type: 'image',
      media: ['https://picsum.photos/seed/d_041a/800/1200', 'https://picsum.photos/seed/d_041b/800/1200'],
      cover: 'https://picsum.photos/seed/d_041a/800/1200', price: 58, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-11 17:40', status: 'normal',
      stats: { views: 1420, likes: 198, favorites: 88, comments: 22, checkins: 14 } },
    { id: 'd_040', shopId: 's_016', shopName: '天府三街寿司郎', name: '玉子烧', desc: '现做的厚蛋烧，微甜。', type: 'image',
      media: ['https://picsum.photos/seed/d_040a/800/1200', 'https://picsum.photos/seed/d_040b/800/1200'],
      cover: 'https://picsum.photos/seed/d_040a/800/1200', price: 18, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['日料', '甜点'], publishedAt: '2026-09-11 12:10', status: 'normal',
      stats: { views: 1020, likes: 132, favorites: 54, comments: 10, checkins: 6 } },
    { id: 'd_038', shopId: 's_008', shopName: '牛市口钵钵鸡', name: '鸡杂钵钵鸡', desc: '鸡杂脆爽，重口党喜欢。', type: 'image',
      media: ['https://picsum.photos/seed/d_038a/800/1200', 'https://picsum.photos/seed/d_038b/800/1200'],
      cover: 'https://picsum.photos/seed/d_038a/800/1200', price: 42, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '小吃'], publishedAt: '2026-09-11 11:30', status: 'normal',
      stats: { views: 1700, likes: 228, favorites: 106, comments: 19, checkins: 30 } },
    { id: 'd_037', shopId: 's_004', shopName: '一味面馆', name: '担担面', desc: '干拌担担面，芽菜是灵魂。', type: 'image',
      media: ['https://picsum.photos/seed/d_037a/800/1200', 'https://picsum.photos/seed/d_037b/800/1200'],
      cover: 'https://picsum.photos/seed/d_037a/800/1200', price: 16, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣', '面食'], publishedAt: '2026-09-11 08:10', status: 'normal',
      stats: { views: 1140, likes: 136, favorites: 54, comments: 9, checkins: 29 } },
    { id: 'd_045', shopId: 's_012', shopName: '玉林路小酒馆', name: '烤韭菜', desc: '一把三块钱，蒜蓉味。', type: 'image',
      media: ['https://picsum.photos/seed/d_045a/800/1200', 'https://picsum.photos/seed/d_045b/800/1200'],
      cover: 'https://picsum.photos/seed/d_045a/800/1200', price: 15, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['烧烤'], publishedAt: '2026-09-10 20:30', status: 'normal',
      stats: { views: 2100, likes: 268, favorites: 120, comments: 35, checkins: 19 } },
    { id: 'd_048', shopId: 's_022', shopName: '炭匠烤肉', name: '烤羊排', desc: '整扇羊排烤，撒孜然。', type: 'video',
      media: ['https://picsum.photos/seed/d_048a/800/1200'], cover: 'https://picsum.photos/seed/d_048a/800/1200',
      price: 168, priceTierId: 'tier_ultra', realTag: 'real', tasteTags: ['烧烤'], publishedAt: '2026-09-10 19:20',
      status: 'normal', stats: { views: 980, likes: 138, favorites: 60, comments: 11, checkins: 5 } },
    { id: 'd_044', shopId: 's_007', shopName: '陈记冒菜', name: '干拌冒菜', desc: '不带汤，调料挂得住。', type: 'image',
      media: ['https://picsum.photos/seed/d_044a/800/1200', 'https://picsum.photos/seed/d_044b/800/1200'],
      cover: 'https://picsum.photos/seed/d_044a/800/1200', price: 32, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['麻辣'], publishedAt: '2026-09-10 19:10', status: 'normal',
      stats: { views: 1560, likes: 192, favorites: 84, comments: 14, checkins: 9 } },
    { id: 'd_042', shopId: 's_001', shopName: '蜀香小馆', name: '宫保鸡丁', desc: '荔枝口味，花生米现炸。', type: 'image',
      media: ['https://picsum.photos/seed/d_042a/800/1200', 'https://picsum.photos/seed/d_042b/800/1200'],
      cover: 'https://picsum.photos/seed/d_042a/800/1200', price: 38, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['酸甜'], publishedAt: '2026-09-10 18:40', status: 'normal',
      stats: { views: 3040, likes: 376, favorites: 166, comments: 31, checkins: 20 } },
    { id: 'd_047', shopId: 's_017', shopName: '玉双路糖水铺', name: '双皮奶', desc: '奶皮厚，红豆是自家煮的。', type: 'image',
      media: ['https://picsum.photos/seed/d_047a/800/1200', 'https://picsum.photos/seed/d_047b/800/1200'],
      cover: 'https://picsum.photos/seed/d_047a/800/1200', price: 20, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['甜点'], publishedAt: '2026-09-10 14:00', status: 'normal',
      stats: { views: 820, likes: 202, favorites: 124, comments: 18, checkins: 38 } },
    { id: 'd_046', shopId: 's_013', shopName: '春熙路茶餐厅', name: '丝袜奶茶', desc: '茶味重，不甜腻。', type: 'image',
      media: ['https://picsum.photos/seed/d_046a/800/1200', 'https://picsum.photos/seed/d_046b/800/1200'],
      cover: 'https://picsum.photos/seed/d_046a/800/1200', price: 16, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['甜点'], publishedAt: '2026-09-10 10:20', status: 'normal',
      stats: { views: 1300, likes: 166, favorites: 70, comments: 12, checkins: 8 } },
    { id: 'd_043', shopId: 's_006', shopName: '老字号甜水面', name: '三大炮', desc: '糯米团现捶，黄豆粉裹满。', type: 'image',
      media: ['https://picsum.photos/seed/d_043a/800/1200', 'https://picsum.photos/seed/d_043b/800/1200'],
      cover: 'https://picsum.photos/seed/d_043a/800/1200', price: 15, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['小吃', '甜点'], publishedAt: '2026-09-10 09:40', status: 'normal',
      stats: { views: 940, likes: 168, favorites: 80, comments: 14, checkins: 44 } },
    { id: 'd_049', shopId: 's_002', shopName: '老李炭火烧烤', name: '烤茄子', desc: '整只茄子烤软，蒜蓉铺满。', type: 'image',
      media: ['https://picsum.photos/seed/d_049a/800/1200', 'https://picsum.photos/seed/d_049b/800/1200'],
      cover: 'https://picsum.photos/seed/d_049a/800/1200', price: 22, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['烧烤', '清淡'], publishedAt: '2026-09-09 20:10', status: 'normal',
      stats: { views: 2700, likes: 330, favorites: 132, comments: 26, checkins: 17 } },
    { id: 'd_051', shopId: 's_009', shopName: '高新串串实验室', name: '冰粉', desc: '红糖冰粉配醪糟，解辣。', type: 'image',
      media: ['https://picsum.photos/seed/d_051a/800/1200', 'https://picsum.photos/seed/d_051b/800/1200'],
      cover: 'https://picsum.photos/seed/d_051a/800/1200', price: 12, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['甜点', '清淡'], publishedAt: '2026-09-09 19:00', status: 'normal',
      stats: { views: 3260, likes: 472, favorites: 242, comments: 54, checkins: 30 } },
    { id: 'd_050', shopId: 's_005', shopName: '樱町日料', name: '鳗鱼饭', desc: '蒲烧鳗鱼，酱汁偏甜。', type: 'image',
      media: ['https://picsum.photos/seed/d_050a/800/1200', 'https://picsum.photos/seed/d_050b/800/1200'],
      cover: 'https://picsum.photos/seed/d_050a/800/1200', price: 88, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['日料', '甜点'], publishedAt: '2026-09-09 18:30', status: 'normal',
      stats: { views: 1880, likes: 268, favorites: 192, comments: 40, checkins: 6 } },
    { id: 'd_054', shopId: 's_019', shopName: '东郊记忆西餐厅', name: '意面', desc: '番茄肉酱，面条有嚼劲。', type: 'image',
      media: ['https://picsum.photos/seed/d_054a/800/1200', 'https://picsum.photos/seed/d_054b/800/1200'],
      cover: 'https://picsum.photos/seed/d_054a/800/1200', price: 68, priceTierId: 'tier_high', realTag: 'real',
      tasteTags: ['清淡'], publishedAt: '2026-09-09 12:30', status: 'normal',
      stats: { views: 700, likes: 88, favorites: 40, comments: 7, checkins: 3 } },
    { id: 'd_053', shopId: 's_015', shopName: '抚琴豆花面', name: '素椒杂酱面', desc: '杂酱炒得干香，面有嚼劲。', type: 'image',
      media: ['https://picsum.photos/seed/d_053a/800/1200', 'https://picsum.photos/seed/d_053b/800/1200'],
      cover: 'https://picsum.photos/seed/d_053a/800/1200', price: 16, priceTierId: 'tier_mid', realTag: 'real',
      tasteTags: ['面食', '麻辣'], publishedAt: '2026-09-09 08:20', status: 'normal',
      stats: { views: 880, likes: 118, favorites: 48, comments: 9, checkins: 14 } },
  ],

  /* ---------- 评论（含商家回评 reply） ---------- */
  comments: [
    { id: 'c_001', dishId: 'd_001', userId: 'u_002', userName: '隔壁老王',
      avatar: 'https://picsum.photos/seed/u_002/100/100', content: '昨天去吃了，豆腐嫩得离谱，配米饭绝了。', at: '2026-09-15 08:34',
      reply: { content: '谢谢支持！下次来给您多加点花椒~', at: '2026-09-15 09:04' } },
    { id: 'c_002', dishId: 'd_001', userId: 'u_003', userName: '干饭人小李',
      avatar: 'https://picsum.photos/seed/u_003/100/100', content: '价格是不是涨了？之前好像 24。', at: '2026-09-15 09:34',
      reply: null },
    { id: 'c_003', dishId: 'd_002', userId: 'u_004', userName: '夜宵战神',
      avatar: 'https://picsum.photos/seed/u_004/100/100', content: '烤四十分钟是真的，等的时候有点久但值得。', at: '2026-09-15 05:34',
      reply: null },
    { id: 'c_004', dishId: 'd_007', userId: 'u_012', userName: '串串狂热者',
      avatar: 'https://picsum.photos/seed/u_012/100/100', content: '牛油锅底是真的香，就是吃完衣服味儿大。', at: '2026-09-15 09:34',
      reply: null },
    { id: 'c_005', dishId: 'd_004', userId: 'u_007', userName: '成都老饕',
      avatar: 'https://picsum.photos/seed/u_007/100/100', content: '这个价位能吃到这个品质，值。', at: '2026-09-15 07:34',
      reply: { content: '感谢认可，欢迎再来~', at: '2026-09-15 08:04' } },
    { id: 'c_006', dishId: 'd_006', userId: 'u_005', userName: '麻辣小丸子',
      avatar: 'https://picsum.photos/seed/u_005/100/100', content: '藤椒味比红油更清爽，推荐。', at: '2026-09-15 08:34',
      reply: null },
    { id: 'c_007', dishId: 'd_006', userId: 'u_001', userName: '小吃货',
      avatar: 'https://picsum.photos/seed/u_001/100/100', content: '按根算有点贵，一不小心就超预算。', at: '2026-09-15 06:34',
      reply: null },
    { id: 'c_008', dishId: 'd_009', userId: 'u_004', userName: '夜宵战神',
      avatar: 'https://picsum.photos/seed/u_004/100/100', content: '把把烧配冰啤酒，夏夜标配。', at: '2026-09-15 08:34',
      reply: null },
    { id: 'c_009', dishId: 'd_010', userId: 'u_006', userName: '一只吃货',
      avatar: 'https://picsum.photos/seed/u_006/100/100', content: '蒜香那半边比麻辣好吃，意外。', at: '2026-09-15 04:34',
      reply: null },
    { id: 'c_010', dishId: 'd_011', userId: 'u_013', userName: '只喝汤',
      avatar: 'https://picsum.photos/seed/u_013/100/100', content: '绵阳人表示这个是正宗的味道。', at: '2026-09-15 09:34',
      reply: { content: '老乡好！', at: '2026-09-15 10:04' } },
    { id: 'c_011', dishId: 'd_013', userId: 'u_001', userName: '小吃货',
      avatar: 'https://picsum.photos/seed/u_001/100/100', content: '抄手皮薄到能看见馅，好评。', at: '2026-09-15 07:34',
      reply: null },
    { id: 'c_012', dishId: 'd_014', userId: 'u_009', userName: '甜品控',
      avatar: 'https://picsum.photos/seed/u_009/100/100', content: '甜水面第一口惊艳，第三口有点腻。', at: '2026-09-15 05:34',
      reply: null },
    { id: 'c_013', dishId: 'd_015', userId: 'u_012', userName: '串串狂热者',
      avatar: 'https://picsum.photos/seed/u_012/100/100', content: '虾滑是真的手打，能吃到虾肉颗粒。', at: '2026-09-15 06:34',
      reply: null },
    { id: 'c_014', dishId: 'd_017', userId: 'u_008', userName: '深夜放毒',
      avatar: 'https://picsum.photos/seed/u_008/100/100', content: '这家店卫生一般，建议改进。', at: '2026-09-15 03:34',
      reply: null },
    { id: 'c_015', dishId: 'd_019', userId: 'u_007', userName: '成都老饕',
      avatar: 'https://picsum.photos/seed/u_007/100/100', content: '剁椒够劲，配米饭能干三碗。', at: '2026-09-15 07:34',
      reply: null },
    { id: 'c_016', dishId: 'd_021', userId: 'u_002', userName: '隔壁老王',
      avatar: 'https://picsum.photos/seed/u_002/100/100', content: '水煮牛肉的牛肉片很嫩，没柴。', at: '2026-09-14 14:34',
      reply: null },
    { id: 'c_017', dishId: 'd_024', userId: 'u_004', userName: '夜宵战神',
      avatar: 'https://picsum.photos/seed/u_004/100/100', content: '鸡皮烤得脆，下酒一流。', at: '2026-09-14 08:34',
      reply: null },
    { id: 'c_018', dishId: 'd_027', userId: 'u_009', userName: '甜品控',
      avatar: 'https://picsum.photos/seed/u_009/100/100', content: '姜撞奶姜味够冲，我喜欢。', at: '2026-09-14 04:34',
      reply: null },
    { id: 'c_019', dishId: 'd_030', userId: 'u_008', userName: '深夜放毒',
      avatar: 'https://picsum.photos/seed/u_008/100/100', content: '烤脑花处理得干净，没有腥味。', at: '2026-09-13 08:34',
      reply: null },
    { id: 'c_020', dishId: 'd_031', userId: 'u_007', userName: '成都老饕',
      avatar: 'https://picsum.photos/seed/u_007/100/100', content: '刺身厚度合适，山葵是现磨的。', at: '2026-09-13 03:34',
      reply: null },
    { id: 'c_021', dishId: 'd_032', userId: 'u_012', userName: '串串狂热者',
      avatar: 'https://picsum.photos/seed/u_012/100/100', content: '毛肚七上八下刚好，别烫久了。', at: '2026-09-12 22:34',
      reply: null },
    { id: 'c_022', dishId: 'd_035', userId: 'u_013', userName: '只喝汤',
      avatar: 'https://picsum.photos/seed/u_013/100/100', content: '清汤才是检验米粉的标准。', at: '2026-09-13 18:34',
      reply: null },
    { id: 'c_023', dishId: 'd_042', userId: 'u_005', userName: '麻辣小丸子',
      avatar: 'https://picsum.photos/seed/u_005/100/100', content: '宫保鸡丁酸甜口调得很准。', at: '2026-09-11 16:34',
      reply: null },
    { id: 'c_024', dishId: 'd_043', userId: 'u_009', userName: '甜品控',
      avatar: 'https://picsum.photos/seed/u_009/100/100', content: '三大炮现场捶的，很有仪式感。', at: '2026-09-11 11:34',
      reply: null },
    { id: 'c_025', dishId: 'd_048', userId: 'u_013', userName: '只喝汤',
      avatar: 'https://picsum.photos/seed/u_013/100/100', content: '羊排分量足，两个人吃刚好。', at: '2026-09-11 06:34',
      reply: null },
    { id: 'c_026', dishId: 'd_049', userId: 'u_003', userName: '干饭人小李',
      avatar: 'https://picsum.photos/seed/u_003/100/100', content: '茄子烤得偏油了，希望改进。', at: '2026-09-10 20:34',
      reply: { content: '收到，已经反馈给后厨了。', at: '2026-09-10 21:04' } },
    { id: 'c_027', dishId: 'd_051', userId: 'u_010', userName: '螺蛳粉星人',
      avatar: 'https://picsum.photos/seed/u_010/100/100', content: '冰粉解辣一流，必点。', at: '2026-09-10 10:34',
      reply: null },
    { id: 'c_028', dishId: 'd_053', userId: 'u_011', userName: '减脂餐战士',
      avatar: 'https://picsum.photos/seed/u_011/100/100', content: '杂酱面分量对减脂人士不太友好哈。', at: '2026-09-10 00:34',
      reply: null },
  ],

  /* ---------- 待审核商家 ---------- */
  pendingShops: [
    { id: 'p_004', name: '城南潮汕牛肉锅', logo: 'https://picsum.photos/seed/p_004logo/200/200',
      cover: 'https://picsum.photos/seed/p_004cover/800/600', address: '高新区府城大道 128 号', city: '成都市',
      district: '高新区', lat: 31, lng: 104.3, distance: null, phone: '028-88886666', hours: '11:00-23:00',
      cuisine: '火锅', intro: '现宰黄牛，八秒吊龙。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-15 09:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_003', name: '深夜豆浆油条', logo: 'https://picsum.photos/seed/p_003logo/200/200',
      cover: 'https://picsum.photos/seed/p_003cover/800/600', address: '锦江区东大街 41 号', city: '成都市', district: '锦江区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88889999', hours: '22:00-06:00', cuisine: '小吃',
      intro: '专做夜宵档，豆浆现磨。', status: 'pending', weight: 0, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-15 08:15', reviewedAt: null, reviewer: null,
      rejectReason: null, stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_002', name: '巷子口串串香', logo: 'https://picsum.photos/seed/p_002logo/200/200',
      cover: 'https://picsum.photos/seed/p_002cover/800/600', address: '金牛区抚琴西路 8 号', city: '成都市', district: '金牛区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88888888', hours: '16:00-03:00', cuisine: '火锅',
      intro: '老巷子里的苍蝇馆子，开了七年。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-14 10:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_001', name: '新开的螺蛳粉', logo: 'https://picsum.photos/seed/p_001logo/200/200',
      cover: 'https://picsum.photos/seed/p_001cover/800/600', address: '成华区建设路 66 号', city: '成都市', district: '成华区',
      lat: 31, lng: 104.3, distance: null, phone: '028-88887777', hours: '10:00-23:00', cuisine: '小吃',
      intro: '正宗柳州味道，酸笋每天现发。', status: 'pending', weight: 0, pinned: false, canPostToday: false, intervalHours: 24,
      dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-14 09:15', reviewedAt: null, reviewer: null,
      rejectReason: null, stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
    { id: 'p_005', name: '锦江老面馆', logo: 'https://picsum.photos/seed/p_005logo/200/200',
      cover: 'https://picsum.photos/seed/p_005cover/800/600', address: '锦江区梨花街 22 号', city: '成都市', district: '锦江区',
      lat: 31, lng: 104.3, distance: null, phone: '0816-2288999', hours: '06:30-20:00', cuisine: '面食',
      intro: '开了二十年的老面馆，杂酱面最出名。', status: 'pending', weight: 0, pinned: false, canPostToday: false,
      intervalHours: 24, dailyLimit: 1, lastPostAt: '', submittedAt: '2026-09-13 11:15', reviewedAt: null,
      reviewer: null, rejectReason: null,
      stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
  ],

  /* ---------- 举报 ---------- */
  reports: [
    { id: 'r_001', type: 'dish', targetId: 'd_003', targetName: '回锅肉', reason: '图片与实物不符', reporter: '干饭人小李',
      at: '2026-09-15 10:33', status: 'pending' },
    { id: 'r_002', type: 'comment', targetId: 'c_002', targetName: '评论：价格是不是涨了', reason: '恶意刷差评', reporter: '蜀香小馆',
      at: '2026-09-15 10:33', status: 'pending' },
    { id: 'r_003', type: 'dish', targetId: 'd_016', targetName: '蒜蓉粉丝扇贝', reason: '价格虚高，涉嫌虚假宣传', reporter: '麻辣小丸子',
      at: '2026-09-14 11:20', status: 'pending' },
    { id: 'r_004', type: 'comment', targetId: 'c_014', targetName: '评论：这家店卫生一般', reason: '恶意差评，与事实不符',
      reporter: '抚琴豆花面', at: '2026-09-14 11:20', status: 'pending' },
    { id: 'r_005', type: 'dish', targetId: 'd_052', targetName: '麻辣烤鱼', reason: '疑似盗用他人图片', reporter: '成都老饕',
      at: '2026-09-13 11:20', status: 'confirmed' },
    { id: 'r_006', type: 'comment', targetId: 'c_019', targetName: '评论：烤脑花处理得干净', reason: '广告嫌疑',
      reporter: '老李炭火烧烤', at: '2026-09-12 11:20', status: 'rejected' },
    { id: 'r_009', type: 'dish', targetId: 'd_048', targetName: '烤羊排', reason: '盗图', reporter: '串串狂热者',
      at: '2026-09-12 11:20', status: 'pending' },
    { id: 'r_007', type: 'dish', targetId: 'd_028', targetName: '战斧牛排', reason: '分量与描述不符', reporter: '一只吃货',
      at: '2026-09-11 11:20', status: 'confirmed' },
    { id: 'r_008', type: 'comment', targetId: 'c_026', targetName: '评论：茄子烤得偏油了', reason: '正常差评，被商家恶意举报',
      reporter: '甜品控', at: '2026-09-10 11:20', status: 'rejected' },
  ],

  /* ---------- 用户（按被举报次数倒序） ---------- */
  users: [
    { id: 'u_003', name: '干饭人小李', avatar: 'https://picsum.photos/seed/u_003/100/100', comments: 88, reports: 5,
      status: 'warned', at: '2026-06-20' },
    { id: 'u_008', name: '深夜放毒', avatar: 'https://picsum.photos/seed/u_008/100/100', comments: 94, reports: 4,
      status: 'banned', at: '2026-04-30' },
    { id: 'u_012', name: '串串狂热者', avatar: 'https://picsum.photos/seed/u_012/100/100', comments: 72, reports: 3,
      status: 'warned', at: '2026-05-06' },
    { id: 'u_007', name: '成都老饕', avatar: 'https://picsum.photos/seed/u_007/100/100', comments: 67, reports: 2,
      status: 'normal', at: '2026-05-18' },
    { id: 'u_005', name: '麻辣小丸子', avatar: 'https://picsum.photos/seed/u_005/100/100', comments: 33, reports: 1,
      status: 'normal', at: '2026-07-28' },
    { id: 'u_010', name: '螺蛳粉星人', avatar: 'https://picsum.photos/seed/u_010/100/100', comments: 18, reports: 1,
      status: 'normal', at: '2026-08-08' },
    { id: 'u_014', name: '面食之王', avatar: 'https://picsum.photos/seed/u_014/100/100', comments: 29, reports: 1,
      status: 'normal', at: '2026-07-03' },
    { id: 'u_001', name: '小吃货', avatar: 'https://picsum.photos/seed/u_001/100/100', comments: 12, reports: 0,
      status: 'normal', at: '2026-08-01' },
    { id: 'u_002', name: '隔壁老王', avatar: 'https://picsum.photos/seed/u_002/100/100', comments: 45, reports: 0,
      status: 'normal', at: '2026-07-12' },
    { id: 'u_004', name: '夜宵战神', avatar: 'https://picsum.photos/seed/u_004/100/100', comments: 26, reports: 0,
      status: 'normal', at: '2026-08-15' },
    { id: 'u_006', name: '一只吃货', avatar: 'https://picsum.photos/seed/u_006/100/100', comments: 51, reports: 0,
      status: 'normal', at: '2026-06-05' },
    { id: 'u_009', name: '甜品控', avatar: 'https://picsum.photos/seed/u_009/100/100', comments: 21, reports: 0,
      status: 'normal', at: '2026-08-22' },
    { id: 'u_011', name: '减脂餐战士', avatar: 'https://picsum.photos/seed/u_011/100/100', comments: 9, reports: 0,
      status: 'normal', at: '2026-09-01' },
    { id: 'u_013', name: '只喝汤', avatar: 'https://picsum.photos/seed/u_013/100/100', comments: 14, reports: 0,
      status: 'normal', at: '2026-08-19' },
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
    let f = { tiers: [], tastes: [] };
    try {
      const raw = localStorage.getItem('clientFilter');
      if (raw) {
        const o = JSON.parse(raw) || {};
        f = { tiers: o.tiers || [], tastes: o.tastes || [] };
      }
    } catch (e) {
      return { tiers: [], tastes: [] };   // 脏 JSON 直接当作没筛选
    }
    const v = this.sanitizeFilter(f);
    // 只有真的剔掉了失效项才回写，避免每次读取都产生一次写操作
    if (v.tiers.length !== f.tiers.length || v.tastes.length !== f.tastes.length) {
      this.setFilter(v);
    }
    return v;
  },

  /*
   * 校正筛选条件：剔除平台端已删除的档位与口味。纯函数，不写库。
   *
   * 为什么需要：平台改了价格档，用户浏览器里还存着旧 id。
   * 不校正的话首页顶部会显示「筛选：tier_old」这种看不懂的原始 id，
   * 而且永久筛空 —— 用户根本不知道该清哪个条件。
   *
   * 只减不增，不会把「没筛选」变成「有筛选」。
   * 数据源为空（适配层还没把后端档位灌进来）时跳过校验，
   * 否则会把用户的条件误清空。
   */
  sanitizeFilter(f) {
    const tiers = (f && f.tiers) || [];
    const tastes = (f && f.tastes) || [];
    return {
      tiers: (this.priceTiers && this.priceTiers.length)
        ? tiers.filter(id => this.priceTiers.some(t => t.id === id))
        : tiers.slice(),
      tastes: (this.tasteTags && this.tasteTags.length)
        ? tastes.filter(t => this.tasteTags.includes(t))
        : tastes.slice()
    };
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
    // 先校正一次：万一传进来的是带失效 id 的脏条件，
    // 也不能把 tier_xxx 这种原始 id 显示给用户
    const x = this.sanitizeFilter(f || this.getFilter());
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

/* ==========================================================
   平台端专用数据（自动生成）
   ----------------------------------------------------------
   后端在线时由 /admin/bootstrap 覆盖；这里是离线演示数据。
   整段由 server/tools/export_mock.py 生成，请勿手改。
   ========================================================== */
/* 已通过审核的商家：后端按 status ∈ {normal, muted, banned} 算好，这里直接用 */
MOCK.approvedShops = [
  { id: 's_009', name: '高新串串实验室', logo: 'https://picsum.photos/seed/s_009logo/200/200',
    cover: 'https://picsum.photos/seed/s_009cover/800/600', address: '高新区天府三街 199 号', city: '成都市', district: '高新区',
    lat: 30.55, lng: 104.06, distance: 5.2, phone: '028-85901234', hours: '16:00-01:00', cuisine: '火锅',
    intro: '锅底自己配，牛油现炒。', status: 'normal', weight: 95, pinned: true, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 09:50', submittedAt: '2026-09-13 08:40', reviewedAt: '2026-09-13 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 4, views: 3480, likes: 512, favorites: 264, comments: 58, checkins: 33 } },
  { id: 's_008', name: '牛市口钵钵鸡', logo: 'https://picsum.photos/seed/s_008logo/200/200',
    cover: 'https://picsum.photos/seed/s_008cover/800/600', address: '锦江区牛市口街 9 号', city: '成都市', district: '锦江区',
    lat: 30.69, lng: 104.11, distance: 2.4, phone: '028-85890123', hours: '11:00-23:00', cuisine: '小吃',
    intro: '藤椒味最正，签子按根算。', status: 'normal', weight: 70, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-12 08:40', reviewedAt: '2026-09-12 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 1780, likes: 246, favorites: 118, comments: 22, checkins: 34 } },
  { id: 's_007', name: '陈记冒菜', logo: 'https://picsum.photos/seed/s_007logo/200/200',
    cover: 'https://picsum.photos/seed/s_007cover/800/600', address: '武侯区双楠路 21 号', city: '成都市', district: '武侯区',
    lat: 30.62, lng: 104.04, distance: 1.6, phone: '028-85789012', hours: '10:30-21:30', cuisine: '川菜',
    intro: '一锅一煮，麻辣自选。', status: 'normal', weight: 80, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 10:20', submittedAt: '2026-09-11 08:40', reviewedAt: '2026-09-11 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 1640, likes: 208, favorites: 96, comments: 17, checkins: 12 } },
  { id: 's_001', name: '蜀香小馆', logo: 'https://picsum.photos/seed/s_001logo/200/200',
    cover: 'https://picsum.photos/seed/s_001cover/800/600', address: '武侯区科华北路 12 号', city: '成都市', district: '武侯区',
    lat: 30.65, lng: 104.07, distance: 0.8, phone: '028-85123456', hours: '11:00-22:00', cuisine: '川菜',
    intro: '开了十年的苍蝇馆子，麻婆豆腐是招牌。', status: 'normal', weight: 60, pinned: false, canPostToday: false,
    intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 09:30', submittedAt: '2026-09-10 08:40',
    reviewedAt: '2026-09-10 14:20', reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 3200, likes: 412, favorites: 188, comments: 36, checkins: 24 } },
  { id: 's_014', name: '建设路烤鱼', logo: 'https://picsum.photos/seed/s_014logo/200/200',
    cover: 'https://picsum.photos/seed/s_014cover/800/600', address: '成华区建设路 26 号', city: '成都市', district: '成华区',
    lat: 30.67, lng: 104.13, distance: 6.1, phone: '028-85456780', hours: '16:30-01:00', cuisine: '火锅',
    intro: '万州烤鱼，麻辣与蒜香双拼。', status: 'normal', weight: 60, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-10 08:40', reviewedAt: '2026-09-10 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 1880, likes: 264, favorites: 124, comments: 26, checkins: 18 } },
  { id: 's_002', name: '老李炭火烧烤', logo: 'https://picsum.photos/seed/s_002logo/200/200',
    cover: 'https://picsum.photos/seed/s_002cover/800/600', address: '武侯区一环路南三段 88 号', city: '成都市',
    district: '武侯区', lat: 30.64, lng: 104.08, distance: 1.2, phone: '028-85234567', hours: '17:00-02:00',
    cuisine: '烧烤', intro: '炭火现烤，五花肉厚切。', status: 'normal', weight: 90, pinned: true, canPostToday: false,
    intervalHours: 24, dailyLimit: 1, lastPostAt: '2026-09-15 08:45', submittedAt: '2026-09-09 08:40',
    reviewedAt: '2026-09-09 14:20', reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 2810, likes: 356, favorites: 142, comments: 28, checkins: 19 } },
  { id: 's_020', name: '老绵阳米粉', logo: 'https://picsum.photos/seed/s_020logo/200/200',
    cover: 'https://picsum.photos/seed/s_020cover/800/600', address: '成华区双桥路 18 号', city: '成都市', district: '成华区',
    lat: 30.66, lng: 104.11, distance: 1.4, phone: '028-86123456', hours: '06:00-14:00', cuisine: '小吃',
    intro: '米粉细滑，红汤清汤都行。', status: 'normal', weight: 80, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 07:20', submittedAt: '2026-09-09 08:40', reviewedAt: '2026-09-09 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1660, likes: 232, favorites: 104, comments: 27, checkins: 38 } },
  { id: 's_012', name: '玉林路小酒馆', logo: 'https://picsum.photos/seed/s_012logo/200/200',
    cover: 'https://picsum.photos/seed/s_012cover/800/600', address: '武侯区玉林西路 55 号', city: '成都市', district: '武侯区',
    lat: 30.61, lng: 104.05, distance: 0.9, phone: '028-85234560', hours: '18:00-03:00', cuisine: '烧烤',
    intro: '把把烧配冰啤酒。', status: 'normal', weight: 80, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-08 08:40', reviewedAt: '2026-09-08 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 2260, likes: 298, favorites: 134, comments: 41, checkins: 22 } },
  { id: 's_003', name: '川味坊', logo: 'https://picsum.photos/seed/s_003logo/200/200',
    cover: 'https://picsum.photos/seed/s_003cover/800/600', address: '锦江区春熙路 5 号', city: '成都市', district: '锦江区',
    lat: 30.66, lng: 104.09, distance: 2.1, phone: '028-85345678', hours: '10:00-21:00', cuisine: '川菜',
    intro: '家常川菜，回锅肉一绝。', status: 'normal', weight: 90, pinned: true, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 08:20', submittedAt: '2026-09-08 08:40', reviewedAt: '2026-09-08 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 1420, likes: 168, favorites: 62, comments: 12, checkins: 8 } },
  { id: 's_011', name: '老成都锅盔', logo: 'https://picsum.photos/seed/s_011logo/200/200',
    cover: 'https://picsum.photos/seed/s_011cover/800/600', address: '青羊区文殊院街 15 号', city: '成都市', district: '青羊区',
    lat: 30.67, lng: 104.06, distance: 4.6, phone: '028-85123450', hours: '07:30-19:00', cuisine: '小吃',
    intro: '军屯锅盔，现烤现卖。', status: 'normal', weight: 70, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 10:33', submittedAt: '2026-09-07 08:40', reviewedAt: '2026-09-07 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1120, likes: 158, favorites: 66, comments: 14, checkins: 28 } },
  { id: 's_021', name: '老码头火锅', logo: 'https://picsum.photos/seed/s_021logo/200/200',
    cover: 'https://picsum.photos/seed/s_021cover/800/600', address: '金牛区西安中路 9 号', city: '成都市', district: '金牛区',
    lat: 30.67, lng: 104.04, distance: 2.7, phone: '028-86234567', hours: '17:00-02:00', cuisine: '火锅',
    intro: '老码头牛油锅，本地人常去。', status: 'normal', weight: 70, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 18:30', submittedAt: '2026-09-07 08:40', reviewedAt: '2026-09-07 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1480, likes: 206, favorites: 92, comments: 24, checkins: 15 } },
  { id: 's_004', name: '一味面馆', logo: 'https://picsum.photos/seed/s_004logo/200/200',
    cover: 'https://picsum.photos/seed/s_004cover/800/600', address: '武侯区人民南路 33 号', city: '成都市', district: '武侯区',
    lat: 30.63, lng: 104.06, distance: 0.5, phone: '028-85456789', hours: '07:00-20:00', cuisine: '面食',
    intro: '红油抄手，皮薄馅大。', status: 'normal', weight: 70, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 07:40', submittedAt: '2026-09-06 08:40', reviewedAt: '2026-09-06 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1180, likes: 142, favorites: 58, comments: 9, checkins: 31 } },
  { id: 's_005', name: '樱町日料', logo: 'https://picsum.photos/seed/s_005logo/200/200',
    cover: 'https://picsum.photos/seed/s_005cover/800/600', address: '锦江区红星路三段 1 号', city: '成都市', district: '锦江区',
    lat: 30.67, lng: 104.1, distance: 3.4, phone: '028-85567890', hours: '11:30-22:00', cuisine: '日料',
    intro: '每日空运，蓝鳍金枪鱼限量。', status: 'normal', weight: 90, pinned: true, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-15 09:05', submittedAt: '2026-09-06 08:40', reviewedAt: '2026-09-06 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 3, views: 1960, likes: 288, favorites: 210, comments: 44, checkins: 6 } },
  { id: 's_006', name: '老字号甜水面', logo: 'https://picsum.photos/seed/s_006logo/200/200',
    cover: 'https://picsum.photos/seed/s_006cover/800/600', address: '青羊区宽窄巷子 8 号', city: '成都市', district: '青羊区',
    lat: 30.68, lng: 104.05, distance: 4.2, phone: '028-85678901', hours: '09:00-19:00', cuisine: '小吃',
    intro: '一根面拇指粗，酱料甜辣。', status: 'normal', weight: 70, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 10:15', submittedAt: '2026-09-05 08:40', reviewedAt: '2026-09-05 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 980, likes: 176, favorites: 84, comments: 15, checkins: 47 } },
  { id: 's_022', name: '炭匠烤肉', logo: 'https://picsum.photos/seed/s_022logo/200/200',
    cover: 'https://picsum.photos/seed/s_022cover/800/600', address: '武侯区外双楠 88 号', city: '成都市', district: '武侯区',
    lat: 30.6, lng: 104.03, distance: 3.3, phone: '028-86345678', hours: '17:30-01:00', cuisine: '烧烤',
    intro: '大块牛排串，分量足。', status: 'normal', weight: 60, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-13 20:00', submittedAt: '2026-09-05 08:40', reviewedAt: '2026-09-05 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1020, likes: 144, favorites: 64, comments: 12, checkins: 6 } },
  { id: 's_017', name: '玉双路糖水铺', logo: 'https://picsum.photos/seed/s_017logo/200/200',
    cover: 'https://picsum.photos/seed/s_017cover/800/600', address: '成华区玉双路 3 号', city: '成都市', district: '成华区',
    lat: 30.66, lng: 104.12, distance: 5.5, phone: '028-85789010', hours: '12:00-23:00', cuisine: '甜品',
    intro: '广式糖水，姜撞奶现撞。', status: 'normal', weight: 50, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-13 13:30', submittedAt: '2026-09-04 08:40', reviewedAt: '2026-09-04 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 860, likes: 214, favorites: 132, comments: 19, checkins: 41 } },
  { id: 's_010', name: '青石桥海鲜大排档', logo: 'https://picsum.photos/seed/s_010logo/200/200',
    cover: 'https://picsum.photos/seed/s_010cover/800/600', address: '锦江区青石桥中街 7 号', city: '成都市', district: '锦江区',
    lat: 30.66, lng: 104.08, distance: 3.8, phone: '028-85012345', hours: '17:00-02:00', cuisine: '粤菜',
    intro: '现杀现做，蒜蓉粉丝扇贝。', status: 'normal', weight: 60, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 18:10', submittedAt: '2026-09-03 08:40', reviewedAt: '2026-09-03 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1520, likes: 196, favorites: 88, comments: 16, checkins: 11 } },
  { id: 's_018', name: '湘遇小炒', logo: 'https://picsum.photos/seed/s_018logo/200/200',
    cover: 'https://picsum.photos/seed/s_018cover/800/600', address: '金牛区解放路二段 18 号', city: '成都市', district: '金牛区',
    lat: 30.68, lng: 104.03, distance: 6.8, phone: '028-85890120', hours: '11:00-21:30', cuisine: '湘菜',
    intro: '剁椒鱼头够辣。', status: 'normal', weight: 60, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 12:50', submittedAt: '2026-09-03 08:40', reviewedAt: '2026-09-03 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1240, likes: 164, favorites: 72, comments: 21, checkins: 9 } },
  { id: 's_019', name: '东郊记忆西餐厅', logo: 'https://picsum.photos/seed/s_019logo/200/200',
    cover: 'https://picsum.photos/seed/s_019cover/800/600', address: '成华区建设南支路 4 号', city: '成都市', district: '成华区',
    lat: 30.65, lng: 104.14, distance: 9.8, phone: '028-85901230', hours: '11:00-22:00', cuisine: '西餐',
    intro: '牛排现切，环境安静。', status: 'normal', weight: 50, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-13 19:10', submittedAt: '2026-09-02 08:40', reviewedAt: '2026-09-02 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 740, likes: 96, favorites: 44, comments: 8, checkins: 4 } },
  { id: 's_013', name: '春熙路茶餐厅', logo: 'https://picsum.photos/seed/s_013logo/200/200',
    cover: 'https://picsum.photos/seed/s_013cover/800/600', address: '锦江区中纱帽街 12 号', city: '成都市', district: '锦江区',
    lat: 30.65, lng: 104.09, distance: 2.9, phone: '028-85345670', hours: '10:00-22:00', cuisine: '粤菜',
    intro: '菠萝油和丝袜奶茶是招牌。', status: 'normal', weight: 60, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-13 09:30', submittedAt: '2026-09-01 08:40', reviewedAt: '2026-09-01 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1340, likes: 172, favorites: 74, comments: 13, checkins: 9 } },
  { id: 's_015', name: '抚琴豆花面', logo: 'https://picsum.photos/seed/s_015logo/200/200',
    cover: 'https://picsum.photos/seed/s_015cover/800/600', address: '金牛区抚琴西路 44 号', city: '成都市', district: '金牛区',
    lat: 30.7, lng: 104.05, distance: 7.3, phone: '028-85567890', hours: '06:30-14:00', cuisine: '面食',
    intro: '豆花嫩，红油香。', status: 'normal', weight: 60, pinned: false, canPostToday: true, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 07:10', submittedAt: '2026-08-31 08:40', reviewedAt: '2026-08-31 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 920, likes: 128, favorites: 52, comments: 10, checkins: 16 } },
  { id: 's_016', name: '天府三街寿司郎', logo: 'https://picsum.photos/seed/s_016logo/200/200',
    cover: 'https://picsum.photos/seed/s_016cover/800/600', address: '高新区天府三街 288 号', city: '成都市', district: '高新区',
    lat: 30.54, lng: 104.08, distance: 8.4, phone: '028-85678900', hours: '11:00-22:30', cuisine: '日料',
    intro: '回转寿司，人均亲民。', status: 'normal', weight: 60, pinned: false, canPostToday: false, intervalHours: 24,
    dailyLimit: 1, lastPostAt: '2026-09-14 11:40', submittedAt: '2026-08-30 08:40', reviewedAt: '2026-08-30 14:20',
    reviewer: '平台运营', rejectReason: null,
    stats: { dishes: 2, views: 1060, likes: 138, favorites: 58, comments: 11, checkins: 7 } },
];

/* 已驳回的商家：没有上线，所以 distance 为 null（给 0 会插到推荐榜首） */
MOCK.rejectedShops = [
  { id: 'p_r2', name: '皇家御膳私房菜', logo: 'https://picsum.photos/seed/p_r2logo/200/200',
    cover: 'https://picsum.photos/seed/p_r2cover/800/600', address: '武侯区某写字楼', city: '成都市', district: '武侯区',
    lat: 31, lng: 104.3, distance: null, phone: '028-00000000', hours: '不定', cuisine: '川菜', intro: '主打高端宴请。',
    status: 'rejected', weight: 0, pinned: false, canPostToday: false, intervalHours: 24, dailyLimit: 1,
    lastPostAt: '', submittedAt: '2026-09-11 09:00', reviewedAt: '2026-09-12 10:20', reviewer: '平台运营',
    rejectReason: '营业执照与经营主体不一致，且上传的门头照为效果图而非实拍',
    stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
  { id: 'p_r1', name: '无名小摊', logo: 'https://picsum.photos/seed/p_r1logo/200/200',
    cover: 'https://picsum.photos/seed/p_r1cover/800/600', address: '成都市某处', city: '成都市', district: '金牛区', lat: 31,
    lng: 104.3, distance: null, phone: '028-00000000', hours: '不定', cuisine: '小吃', intro: '', status: 'rejected',
    weight: 0, pinned: false, canPostToday: false, intervalHours: 24, dailyLimit: 1, lastPostAt: '',
    submittedAt: '2026-09-08 09:00', reviewedAt: '2026-09-09 10:20', reviewer: '平台运营',
    rejectReason: '门头图不清晰，无法辨认店铺招牌，且未填写详细地址',
    stats: { dishes: 0, views: 0, likes: 0, favorites: 0, comments: 0, checkins: 0 } },
];

/* 已下架内容：下架不是物理删除，记录仍在，只是客户端看不到 */
MOCK.removedDishes = [
  { id: 'd_028', shopId: 's_019', shopName: '东郊记忆西餐厅', name: '战斧牛排', desc: '整块战斧现切，五分熟最佳。', type: 'image',
    media: ['https://picsum.photos/seed/d_028a/800/1200', 'https://picsum.photos/seed/d_028b/800/1200'],
    cover: 'https://picsum.photos/seed/d_028a/800/1200', price: 268, priceTierId: 'tier_ultra', realTag: 'real',
    tasteTags: ['清淡'], publishedAt: '2026-09-13 19:10', status: 'removed',
    stats: { views: 740, likes: 96, favorites: 44, comments: 8, checkins: 4 } },
  { id: 'd_052', shopId: 's_014', shopName: '建设路烤鱼', name: '麻辣烤鱼', desc: '最辣的那一档，慎点。', type: 'image',
    media: ['https://picsum.photos/seed/d_052a/800/1200', 'https://picsum.photos/seed/d_052b/800/1200'],
    cover: 'https://picsum.photos/seed/d_052a/800/1200', price: 118, priceTierId: 'tier_high', realTag: 'real',
    tasteTags: ['麻辣', '火锅'], publishedAt: '2026-09-09 17:50', status: 'removed',
    stats: { views: 1800, likes: 242, favorites: 112, comments: 23, checkins: 15 } },
];

/* 全局默认发布规则（平台端「发布规则」页） */
MOCK.publishRule = { intervalHours: 24, dailyLimit: 1 };

/* 数据概览：导出时的快照，供离线模式首屏直接显示 */
MOCK.overview = { shopCount: 29, shopNormal: 22, shopMuted: 0, shopBanned: 0, shopPending: 5, dishCount: 52, userCount: 14, reportPending: 9, dishToday: 11, trend: [{ d: '周三', v: 5 }, { d: '周四', v: 7 }, { d: '周五', v: 6 }, { d: '周六', v: 6 }, { d: '周日', v: 8 }, { d: '周一', v: 9 }, { d: '今日', v: 11 }] };

/* 暴露到全局 */
window.MOCK = MOCK;
