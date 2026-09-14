package com.eatwhat.repository;

import com.eatwhat.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByStatusOrderByAtDesc(String status);
    List<Report> findAllByOrderByAtDesc();
}
