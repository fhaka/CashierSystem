package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.service.SessionService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Program version, database version and where backups go, for the Operations screen and support. */
@RestController
@RequestMapping("/system")
public class SystemController {

    private final SessionService sessionService;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<Flyway> flyway;
    private final String backupDir;

    public SystemController(SessionService sessionService, ObjectProvider<BuildProperties> buildProperties,
                            ObjectProvider<Flyway> flyway, @Value("${pos.backup.dir:backups}") String backupDir) {
        this.sessionService = sessionService;
        this.buildProperties = buildProperties;
        this.flyway = flyway;
        this.backupDir = backupDir;
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, String>> info(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        Map<String, String> info = new LinkedHashMap<>();
        BuildProperties build = buildProperties.getIfAvailable();
        info.put("version", build == null ? "dev" : build.getVersion());
        info.put("built", build == null || build.getTime() == null ? ""
                : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(build.getTime()));
        Flyway migrations = flyway.getIfAvailable();
        MigrationInfo current = migrations == null ? null : migrations.info().current();
        info.put("databaseVersion", current == null ? "" : "v" + current.getVersion());
        info.put("backupFolder", Path.of(backupDir).toAbsolutePath().toString());
        info.put("java", System.getProperty("java.version"));
        return ApiResponse.ok("System information", info);
    }
}
