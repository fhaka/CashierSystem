package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.AuditEvent;
import com.supermarket.service.AuditService;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** The audit log, for the Super Admin. Dates as yyyy-MM-dd; by default the last 7 days. */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private final AuditService auditService;
    private final SessionService sessionService;

    public AuditController(AuditService auditService, SessionService sessionService) {
        this.auditService = auditService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<AuditEvent>> search(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(defaultValue = "500") int limit
    ) {
        sessionService.requireSuperAdmin(token);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        return ApiResponse.ok("Audit log loaded",
                auditService.search(start.atStartOfDay(), end.atTime(LocalTime.MAX), action, cashierId, limit));
    }
}
