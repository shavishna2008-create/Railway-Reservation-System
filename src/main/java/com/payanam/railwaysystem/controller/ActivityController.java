
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {

    private final DataSource dataSource;

    public ActivityController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // GET ALL ACTIVITY LOGS
    @GetMapping("/api/activities")
    public List<Map<String, Object>> getActivities()
            throws SQLException {

        List<Map<String, Object>> activities = new ArrayList<>();

        String sql = """
            SELECT id, activity_type, details, status, created_at
            FROM activity_logs
            ORDER BY id DESC
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> activity = new LinkedHashMap<>();

                activity.put("id", rs.getLong("id"));
                activity.put("activityType",
                        rs.getString("activity_type"));
                activity.put("details", rs.getString("details"));
                activity.put("status", rs.getString("status"));
                activity.put("createdAt", rs.getTimestamp("created_at"));

                activities.add(activity);
            }
        }

        return activities;
    }

    // ADD A NEW ACTIVITY
    @PostMapping("/api/activities")
    public Map<String, Object> addActivity(
            @RequestBody Map<String, Object> request)
            throws SQLException {

        Object typeValue = request.get("activityType");
        Object detailsValue = request.get("details");

        if (typeValue == null || detailsValue == null
                || typeValue.toString().isBlank()
                || detailsValue.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "activityType and details are required");
        }

        String type = typeValue.toString().trim();
        String details = detailsValue.toString().trim();
        String status = request.get("status") == null
                ? "RECORDED"
                : request.get("status").toString().trim();

        if (type.length() > 50 || details.length() > 500
                || status.length() > 30) {
            throw new IllegalArgumentException(
                    "Activity fields exceed their maximum length");
        }

        String sql = """
            INSERT INTO activity_logs
                (activity_type, details, status)
            VALUES (?, ?, ?)
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, type);
            ps.setString(2, details);
            ps.setString(3, status);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", keys.getLong(1));
                    result.put("activityType", type);
                    result.put("details", details);
                    result.put("status", status);
                    result.put("message", "Activity recorded successfully");
                    return result;
                }
            }
        }

        throw new SQLException("Could not save activity");
    }
}
