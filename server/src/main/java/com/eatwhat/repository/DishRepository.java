package com.eatwhat.repository;

import com.eatwhat.entity.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DishRepository extends JpaRepository<Dish, String> {
    List<Dish> findByShopIdOrderByPublishedTsDesc(String shopId);
    List<Dish> findByStatusOrderByPublishedTsDesc(String status);
    long countByShopIdAndPublishedTsAfter(String shopId, Long ts);
}
