package com.supermarket.repository;

import com.supermarket.model.BackupLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackupLogRepository extends JpaRepository<BackupLog, Long> {

    List<BackupLog> findTop10ByOrderByCreatedAtDesc();
}
