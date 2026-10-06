package com.supermarket.repository;

import com.supermarket.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    @Modifying
    @Query("delete from AuthSession s where s.cashier.id = :cashierId")
    int deleteByCashierId(@Param("cashierId") Long cashierId);

    @Modifying
    @Query("delete from AuthSession s where s.lastSeenAt < :cutoff")
    int deleteIdleSince(@Param("cutoff") LocalDateTime cutoff);
}
