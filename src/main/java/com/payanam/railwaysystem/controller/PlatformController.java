
package com.payanam.railwaysystem.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformController {

    private final DataSource dataSource;

    public PlatformController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/platforms")
    public List<Map<String, Object>> getPlatforms()
            throws SQLException {

        List<Map<String, Object>> platforms = new ArrayList<>();

        String sql = """
            SELECT id, platform_number, platform_length, status
            FROM platforms
            ORDER BY platform_number
            """;

        try (
            Connection connection = dataSource.getConnection();
            PreparedStatement statement =
                    connection.prepareStatement(sql);
            ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                Map<String, Object> platform =
                        new LinkedHashMap<>();

                platform.put("id", rs.getLong("id"));
                platform.put(
                        "platform_number",
                        rs.getInt("platform_number"));
                platform.put(
                        "platform_length",
                        rs.getInt("platform_length"));
                platform.put("status", rs.getString("status"));

                platforms.add(platform);
            }
        }

        return platforms;
    }
}
