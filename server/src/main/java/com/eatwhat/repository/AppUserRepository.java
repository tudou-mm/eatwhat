package com.eatwhat.repository;

import com.eatwhat.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    List<AppUser> findAllByOrderByReportCountDesc();
    AppUser findByPhone(String phone);
}
