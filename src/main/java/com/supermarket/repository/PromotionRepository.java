package com.supermarket.repository;

import com.supermarket.model.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    List<Promotion> findByActiveTrue();

    List<Promotion> findAllByOrderByActiveDescCreatedAtDesc();
}
