package com.eatwhat.controller;

import com.eatwhat.common.R;
import com.eatwhat.entity.Comment;
import com.eatwhat.entity.Dish;
import com.eatwhat.entity.Shop;
import com.eatwhat.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 商家端接口。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantController {

    private final ShopService shopService;
    private final DishService dishService;
    private final CommentService commentService;

    public MerchantController(ShopService shopService, DishService dishService,
                              CommentService commentService) {
        this.shopService = shopService;
        this.dishService = dishService;
        this.commentService = commentService;
    }

    /** 工作台：店铺状态 + 冷却剩余秒数 + 今日数据 */
    @GetMapping("/dashboard/{shopId}")
    public R<Map<String, Object>> dashboard(@PathVariable String shopId) {
        Shop s = shopService.get(shopId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shop", s);
        m.put("canPostToday", s.getCanPostToday());
        m.put("cooldownSeconds", dishService.remainingCooldownSeconds(s));
        m.put("intervalHours", s.getIntervalHours());
        m.put("dailyLimit", s.getDailyLimit());
        m.put("dishes", dishService.listByShop(shopId));
        return R.ok(m);
    }

    /** 发布前校验：能不能发、还差多久 */
    @GetMapping("/can-publish/{shopId}")
    public R<Map<String, Object>> canPublish(@PathVariable String shopId) {
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
    public R<Dish> publish(@RequestBody Map<String, Object> body) {
        return R.ok(dishService.publish(body));
    }

    /** 编辑菜品 */
    @PutMapping("/dish/{id}")
    public R<Dish> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(dishService.update(id, body));
    }

    /** 我的菜品列表 */
    @GetMapping("/dishes/{shopId}")
    public R<List<Dish>> dishes(@PathVariable String shopId) {
        return R.ok(dishService.listByShop(shopId));
    }

    /** 店铺信息 */
    @GetMapping("/shop/{shopId}")
    public R<Shop> shop(@PathVariable String shopId) {
        return R.ok(shopService.get(shopId));
    }

    /** 更新店铺信息（敏感字段会触发重新审核，这里先落库） */
    @PutMapping("/shop/{shopId}")
    public R<Shop> updateShop(@PathVariable String shopId, @RequestBody Map<String, Object> body) {
        Shop s = shopService.get(shopId);
        if (body.get("name") != null) s.setName(body.get("name").toString());
        if (body.get("cuisine") != null) s.setCuisine(body.get("cuisine").toString());
        if (body.get("intro") != null) s.setIntro(body.get("intro").toString());
        if (body.get("address") != null) s.setAddress(body.get("address").toString());
        if (body.get("phone") != null) s.setPhone(body.get("phone").toString());
        if (body.get("hours") != null) s.setHours(body.get("hours").toString());
        if (body.get("cover") != null) s.setCover(body.get("cover").toString());
        if (body.get("logo") != null) s.setLogo(body.get("logo").toString());
        return R.ok(shopService.save(s));
    }

    /** 我的评论（该店所有菜品下的评论） */
    @GetMapping("/comments/{shopId}")
    public R<List<Map<String, Object>>> comments(@PathVariable String shopId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dish d : dishService.listByShop(shopId)) {
            for (Comment c : commentService.listByDish(d.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("dishId", c.getDishId());
                m.put("dishName", d.getName());
                m.put("userName", c.getUserName());
                m.put("avatar", c.getAvatar());
                m.put("content", c.getContent());
                m.put("at", c.getAt());
                m.put("replyContent", c.getReplyContent());
                m.put("replyAt", c.getReplyAt());
                out.add(m);
            }
        }
        return R.ok(out);
    }

    /** 商家回评 */
    @PostMapping("/comment/{id}/reply")
    public R<Comment> reply(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return R.ok(commentService.reply(id, String.valueOf(body.get("content"))));
    }

    /** 清除回评 */
    @DeleteMapping("/comment/{id}/reply")
    public R<Comment> clearReply(@PathVariable String id) {
        return R.ok(commentService.clearReply(id));
    }
}
