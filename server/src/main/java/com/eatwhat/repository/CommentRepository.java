package com.eatwhat.repository;

import com.eatwhat.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, String> {
    List<Comment> findByDishIdAndStatusOrderByAtTsDesc(String dishId, String status);
    List<Comment> findByUserIdOrderByAtTsDesc(String userId);
    long countByUserId(String userId);
}
