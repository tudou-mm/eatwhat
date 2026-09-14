package com.eatwhat.repository;

import com.eatwhat.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ShopRepository extends JpaRepository<Shop, String> {
    List<Shop> findByCityOrderByWeightDesc(String city);
    List<Shop> findByStatus(String status);
}
