package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.BackupLog;
import com.supermarket.service.BackupService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/backups")
public class BackupController {

    private final BackupService backupService;
    private final SessionService sessionService;

    public BackupController(BackupService backupService, SessionService sessionService) {
        this.backupService = backupService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<BackupLog>> findRecentBackups(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("Backups loaded", backupService.findRecentBackups());
    }

    @PostMapping("/run")
    public ApiResponse<BackupLog> runBackup(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("Backup completed", backupService.runManualBackup());
    }
}
