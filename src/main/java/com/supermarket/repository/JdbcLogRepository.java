package com.supermarket.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Repository
public class JdbcLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Map<String, Object>> saleLogMapper = (resultSet, rowNumber) -> {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("id", resultSet.getLong("id"));
        log.put("saleId", resultSet.getLong("sale_id"));
        log.put("message", resultSet.getString("message"));
        log.put("createdAt", resultSet.getTimestamp("created_at").toLocalDateTime());
        return log;
    };

    public JdbcLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    public CompletableFuture<Void> logSaleAsync(Long saleId, String message) {
        createSaleLog(saleId, message);
        return CompletableFuture.completedFuture(null);
    }

    public Map<String, Object> createSaleLog(Long saleId, String message) {
        jdbcTemplate.update(
                "INSERT INTO sale_logs (sale_id, message, created_at) VALUES (?, ?, ?)",
                saleId,
                message,
                LocalDateTime.now()
        );
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM sale_logs", Long.class);
        return findSaleLogById(id).orElseThrow(() -> new IllegalStateException("Created JDBC sale log could not be loaded"));
    }

    public List<Map<String, Object>> findSaleLogs() {
        return jdbcTemplate.query("SELECT * FROM sale_logs ORDER BY created_at DESC", saleLogMapper);
    }

    public Optional<Map<String, Object>> findSaleLogById(Long id) {
        List<Map<String, Object>> logs = jdbcTemplate.query(
                "SELECT * FROM sale_logs WHERE id = ?",
                saleLogMapper,
                id
        );
        return logs.stream().findFirst();
    }

    public Map<String, Object> updateSaleLog(Long id, String message) {
        int updatedRows = jdbcTemplate.update(
                "UPDATE sale_logs SET message = ? WHERE id = ?",
                message,
                id
        );
        if (updatedRows == 0) {
            throw new IllegalArgumentException("JDBC sale log not found with id: " + id);
        }
        return findSaleLogById(id).orElseThrow(() -> new IllegalStateException("Updated JDBC sale log could not be loaded"));
    }

    public void deleteSaleLog(Long id) {
        int deletedRows = jdbcTemplate.update("DELETE FROM sale_logs WHERE id = ?", id);
        if (deletedRows == 0) {
            throw new IllegalArgumentException("JDBC sale log not found with id: " + id);
        }
    }
}
