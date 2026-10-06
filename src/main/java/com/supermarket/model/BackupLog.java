package com.supermarket.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "backup_logs")
public class BackupLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String status;

    @Column(length = 1000)
    private String message;

    public BackupLog() {
    }

    public BackupLog(LocalDateTime createdAt, String filePath, String status, String message) {
        this.createdAt = createdAt;
        this.filePath = filePath;
        this.status = status;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
