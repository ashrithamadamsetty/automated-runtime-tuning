package com.opentext.automatedruntimetuning.web;

import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    private final DataSource dataSource;

    public HealthCheckController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/db-health")
    public String dbHealth() {
        try (Connection conn = dataSource.getConnection()) {
            return "Database connection OK";
        } catch (Exception e) {
            return "Database error: " + e.getMessage();
        }
    }
}