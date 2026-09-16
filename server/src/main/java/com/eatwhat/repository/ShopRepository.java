package com.eatwhat.repository;

import com.eatwhat.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, String> {
    List<Shop> findByCityOrderByWeightDesc(String city);
    List<Shop> findByStatus(String status);

    /** 商家登录：同一个电话理论上只对应一家店，取第一条即可（多店预留见 docs/00） */
    Optional<Shop> findFirstByPhone(String phone);

    /** 入驻去重：同一个电话不该反复提交申请 */
    boolean existsByPhoneAndStatus(String phone, String status);
}
