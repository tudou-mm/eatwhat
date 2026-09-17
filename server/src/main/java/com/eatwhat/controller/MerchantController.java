package com.eatwhat.controller;

import com.eatwhat.auth.AuthContext;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.Comment;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 商家端接口。
 *
 * 出参一律过 {@link Views} —— 前端读嵌套（shop.stats.views），
 * 实体是扁平（statViews），直接返回实体不报错但页面静默空白。
 *
 * 关于归属校验：{@link com.eatwhat.auth.AuthInterceptor} 已经按 URL 里的
 * shopId 拦了一道，这里再按「菜/评论实际属于哪家店」拦第二道。
 * 两层是有意的 —— 前者拦不住 POST body 里的店铺 ID，后者拦不住还没取到对象的场景。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantController {

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;
    private final ConfigService configService;
    private final Views views;

    public MerchantController(ShopService shopService, DishService dishService,
                              CommentService commentService, ConfigService configService,
                              Views views) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
        this.configService = configService;
        this.views = views;
    }

    /**
     * 商家端启动聚合接口。
     * ------------------------------------------------------------------
     * 为什么不能复用 /client/bootstrap：
     * 那个接口给的是「客户端可见」的数据 —— 只含在营店铺、且不含待审核
     * 和被驳回的店。商家一旦处于 pending / rejected 状态，
     * 用客户端的数据源会直接 getShop 返回 null，整页空白。
     * 商家还必须在自己的后台里看到**已下架**的菜品（那正是他要处理的），
     * 而客户端可见范围里没有 removed。
     */
    @GetMapping("/bootstrap")
    public R<Map<String, Object>> bootstrap(@RequestParam String shopId) {
        Shop s = shopService.get(shopId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shop", views.shop(s));
        // 含 removed —— 商家要能看到自己被下架的内容
        m.put("dishes", views.dishes(dishService.listByShop(shopId)));
        m.put("comments", shopComments(shopId));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("priceTiers", configService.priceTiers());
        cfg.put("tasteTags", configService.tasteTags());
        cfg.put("cuisines", configService.cuisines());
        cfg.put("publishRule", configService.publishRule());
        m.put("config", cfg);

        // 发布闸门信息一并带回，工作台首屏就能画倒计时，不用再打一次接口
        m.put("canPostToday", Boolean.TRUE.equals(s.getCanPostToday()));
        m.put("cooldownSeconds", dishService.remainingCooldownSeconds(s));
        m.put("intervalHours", s.getIntervalHours() == null ? 24 : s.getIntervalHours());
        m.put("dailyLimit", s.getDailyLimit() == null ? 1 : s.getDailyLimit());
        return R.ok(m);
    }

    /**
     * 入驻申请：落成一家「待审核」店铺（客户端在审核通过前看不到它）。
     *
     * 这是**唯一一个公开的商家接口**（WebConfig 里排除了鉴权）——
     * 申请的人此刻还没有账号，拦掉就成了「想入驻先登录」。
     * 代价是没有防刷，上线前要加图形验证码或频控。
     *
     * <p>额外返回实体（不只是视图）供 {@code DataSeeder} 复用：
     * 演示数据里的待审核店铺就是这个方法建出来的，保证「演示的待审」
     * 和「真实入驻的待审」字段口径完全一致。
     */
    public Shop applyPending(Map<String, Object> body) {
        return shopService.createPending(body);
    }

    @PostMapping("/apply")
    public R<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        return R.ok(views.shop(applyPending(body)));
    }

    /** 工作台：店铺状态 + 冷却剩余秒数 + 今日数据 */
    @GetMapping("/dashboard/{shopId}")
    public R<Map<String, Object>> dashboard(@PathVariable String shopId) {
        AuthContext.assertSelf(shopId);
        Shop s = shopService.get(shopId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shop", views.shop(s));
        m.put("canPostToday", Boolean.TRUE.equals(s.getCanPostToday()));
        m.put("cooldownSeconds", dishService.remainingCooldownSeconds(s));
        m.put("intervalHours", s.getIntervalHours() == null ? 24 : s.getIntervalHours());
        m.put("dailyLimit", s.getDailyLimit() == null ? 1 : s.getDailyLimit());
        m.put("dishes", views.dishes(dishService.listByShop(shopId)));
        return R.ok(m);
    }

    /** 发布前校验：能不能发、还差多久 */
    @GetMapping("/can-publish/{shopId}")
    public R<Map<String, Object>> canPublish(@PathVariable String shopId) {
        AuthContext.assertSelf(shopId);
        Shop s = shopService.get(shopId);
        Map<String, Object> m = new LinkedHashMap<>();
        long remain = dishService.remainingCooldownSeconds(s);
        m.put("can", remain == 0 && !Boolean.FALSE.equals(s.getCanPostToday()));
        m.put("cooldownSeconds", remain);
        m.put("status", s.getStatus());
        return R.ok(m);
    }

    /** 发布菜品 */
    @PostMapping("/dish")
    public R<Map<String, Object>> publish(@RequestBody Map<String, Object> body) {
        // 发到哪家店一律以 token 为准，**不接受请求体指定** ——
        // 否则改一个 shopId 就能往别家店发内容
        String me = AuthContext.shopId();
        if (me != null) body.put("shopId", me);
        return R.ok(views.dish(dishService.publish(body)));
    }

    /** 编辑菜品 */
    @PutMapping("/dish/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        ownDish(id);
        return R.ok(views.dish(dishService.update(id, body)));
    }

    /**
     * 商家下架自己的菜品。逻辑下架（status=removed），不物理删除 ——
     * 「超 24h 权重归零但不物理删除」是同一条口径。
     */
    @PostMapping("/dish/{id}/remove")
    public R<Map<String, Object>> removeDish(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(views.dish(setOwnDishStatus(id, body, "removed")));
    }

    /** 恢复自己下架的菜品（下架可逆，不然商家误操作就没救了） */
    @PostMapping("/dish/{id}/restore")
    public R<Map<String, Object>> restoreDish(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(views.dish(setOwnDishStatus(id, body, "normal")));
    }

    /** 改菜品状态的公共部分：先校验归属，防止商家动别家的菜 */
    private Dish setOwnDishStatus(String id, Map<String, Object> body, String status) {
        Dish d = ownDish(id);
        String claimed = body == null ? null : String.valueOf(body.get("shopId"));
        if (claimed != null && !"null".equals(claimed) && !claimed.isBlank()
                && !claimed.equals(d.getShopId())) {
            throw new BizException(403, "只能操作本店的菜品");
        }
        return dishService.setStatus(id, status);
    }

    /** 取出这道菜，并确认它属于当前登录的店 */
    private Dish ownDish(String id) {
        Dish d = dishService.get(id);
        String me = AuthContext.shopId();
        if (me != null && !me.equals(d.getShopId())) {
            throw new BizException(403, "只能操作本店的菜品");
        }
        return d;
    }

    /** 这条评论挂在的菜，必须是我家的 */
    private Comment ownComment(String id) {
        Comment c = commentService.get(id);
        String me = AuthContext.shopId();
        if (me != null) {
            Dish d = dishService.get(c.getDishId());
            if (!me.equals(d.getShopId())) {
                throw new BizException(403, "只能回复本店菜品的评论");
            }
        }
        return c;
    }

    /** 我的菜品列表（含已下架） */
    @GetMapping("/dishes/{shopId}")
    public R<List<Map<String, Object>>> dishes(@PathVariable String shopId) {
        AuthContext.assertSelf(shopId);
        return R.ok(views.dishes(dishService.listByShop(shopId)));
    }

    /** 店铺信息 */
    @GetMapping("/shop/{shopId}")
    public R<Map<String, Object>> shop(@PathVariable String shopId) {
        AuthContext.assertSelf(shopId);
        return R.ok(views.shop(shopService.get(shopId)));
    }

    /** 更新店铺信息 */
    @PutMapping("/shop/{shopId}")
    public R<Map<String, Object>> updateShop(@PathVariable String shopId,
                                             @RequestBody Map<String, Object> body) {
        AuthContext.assertSelf(shopId);
        Shop s = shopService.get(shopId);
        if (body.get("name") != null) s.setName(body.get("name").toString());
        if (body.get("cuisine") != null) s.setCuisine(body.get("cuisine").toString());
        if (body.get("intro") != null) s.setIntro(body.get("intro").toString());
        if (body.get("address") != null) s.setAddress(body.get("address").toString());
        if (body.get("phone") != null) s.setPhone(body.get("phone").toString());
        if (body.get("hours") != null) s.setHours(body.get("hours").toString());
        if (body.get("cover") != null) s.setCover(body.get("cover").toString());
        if (body.get("logo") != null) s.setLogo(body.get("logo").toString());
        // 坐标：改了经纬度就必须重算 distance —— 否则地图上挪了两公里，
        // 客户端「附近」里还是按旧位置排，两边对不上。
        // 只认「两个都传了」的情况，传半边会让店铺落到 (新lat, 旧lng) 这种不存在的位置。
        Double lat = shopService.toDouble(body.get("lat"));
        Double lng = shopService.toDouble(body.get("lng"));
        boolean moved = lat != null && lng != null
                && (!lat.equals(s.getLat()) || !lng.equals(s.getLng()));
        if (lat != null) s.setLat(lat);
        if (lng != null) s.setLng(lng);
        if (moved) {
            Shop saved = shopService.save(s);
            return R.ok(views.shop(shopService.refreshDistance(saved)));
        }
        return R.ok(views.shop(shopService.save(s)));
    }

    /** 我的评论（该店所有菜品下的评论） */
    @GetMapping("/comments/{shopId}")
    public R<List<Map<String, Object>>> comments(@PathVariable String shopId) {
        AuthContext.assertSelf(shopId);
        return R.ok(shopComments(shopId));
    }

    /** 商家回评 */
    @PostMapping("/comment/{id}/reply")
    public R<Map<String, Object>> reply(@PathVariable String id,
                                        @RequestBody Map<String, Object> body) {
        ownComment(id);
        return R.ok(views.comment(commentService.reply(id, String.valueOf(body.get("content")))));
    }

    /** 清除回评 */
    @DeleteMapping("/comment/{id}/reply")
    public R<Map<String, Object>> clearReply(@PathVariable String id) {
        ownComment(id);
        return R.ok(views.comment(commentService.clearReply(id)));
    }

    /**
     * 本店全部菜品下的评论。
     * 用 {@link Views#comment} 统一结构（前端读 c.reply.content 嵌套），
     * 额外补一个 dishName —— 商家端列表要显示"哪道菜"的评论，
     * 缺了它页面只能显示空白。
     */
    private List<Map<String, Object>> shopComments(String shopId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : dishService.listByShop(shopId)) {
            for (Comment c : commentService.listByDish(d.getId())) {
                Map<String, Object> m = views.comment(c);
                m.put("dishName", d.getName());
                out.add(m);
            }
        }
        return out;
    }
}
