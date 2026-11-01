
package com.payanam.railwaysystem.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformAllocationController {

    private final DataSource dataSource;

    public PlatformAllocationController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private void logActivity(
            Connection con,
            String activityType,
            String details,
            String status) throws SQLException {

        String sql = """
            INSERT INTO activity_logs
                (activity_type, details, status)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, activityType);
            ps.setString(2, details);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    // Get all platform allocations
    @GetMapping("/api/allocations")
    public List<Map<String, Object>> getAllocations()
            throws SQLException {

        List<Map<String, Object>> allocations = new ArrayList<>();

        String sql = """
            SELECT a.id, t.train_number, t.train_name,
                   t.platform_length_required,
                   p.platform_number, p.platform_length,
                   a.arrival_time, a.departure_time,
                   a.clearance_minutes, a.status
            FROM platform_allocations a
            JOIN trains t ON a.train_id = t.id
            JOIN platforms p ON a.platform_id = p.id
            ORDER BY a.arrival_time
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();

                item.put("id", rs.getLong("id"));
                item.put("trainNumber", rs.getString("train_number"));
                item.put("trainName", rs.getString("train_name"));
                item.put("trainLength",
                        rs.getInt("platform_length_required"));
                item.put("platformNumber",
                        rs.getInt("platform_number"));
                item.put("platformLength",
                        rs.getInt("platform_length"));
                item.put("arrivalTime", rs.getTimestamp("arrival_time"));
                item.put("departureTime",
                        rs.getTimestamp("departure_time"));
                item.put("clearanceMinutes",
                        rs.getInt("clearance_minutes"));
                item.put("status", rs.getString("status"));

                allocations.add(item);
            }
        }

        return allocations;
    }

    // Automatically allocate platforms
    @PostMapping("/api/allocations/auto")
    public Map<String, Object> autoAllocate() throws SQLException {

        int allocated = 0;
        int pending = 0;
        List<String> messages = new ArrayList<>();

        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);

            try {
                String trainSql = """
                    SELECT id, train_number, train_name,
                           arrival_time, departure_time,
                           platform_length_required
                    FROM trains
                    ORDER BY arrival_time, id
                    """;

                String platformSql = """
                    SELECT id, platform_number, platform_length
                    FROM platforms
                    ORDER BY platform_number
                    """;

                List<Map<String, Object>> trains = new ArrayList<>();
                List<Map<String, Object>> platforms = new ArrayList<>();

                try (PreparedStatement ps = con.prepareStatement(trainSql);
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        Map<String, Object> train = new LinkedHashMap<>();

                        train.put("id", rs.getLong("id"));
                        train.put("number", rs.getString("train_number"));
                        train.put("name", rs.getString("train_name"));
                        train.put("arrival",
                                rs.getTime("arrival_time").toLocalTime());
                        train.put("departure",
                                rs.getTime("departure_time").toLocalTime());
                        train.put("length",
                                rs.getInt("platform_length_required"));

                        trains.add(train);
                    }
                }

                try (PreparedStatement ps =
                             con.prepareStatement(platformSql);
                     ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {
                        Map<String, Object> platform = new LinkedHashMap<>();

                        platform.put("id", rs.getLong("id"));
                        platform.put("number",
                                rs.getInt("platform_number"));
                        platform.put("length",
                                rs.getInt("platform_length"));

                        platforms.add(platform);
                    }
                }

                LocalDate today = LocalDate.now();

                for (Map<String, Object> train : trains) {
                    long trainId = (long) train.get("id");
                    int trainLength = (int) train.get("length");

                    LocalDateTime arrival = LocalDateTime.of(
                            today, (LocalTime) train.get("arrival"));

                    LocalDateTime departure = LocalDateTime.of(
                            today, (LocalTime) train.get("departure"));

                    if (!departure.isAfter(arrival)) {
                        departure = departure.plusDays(1);
                    }

                    String existingSql = """
                        SELECT id
                        FROM platform_allocations
                        WHERE train_id = ?
                          AND status = 'ALLOCATED'
                        """;

                    boolean alreadyAllocated;

                    try (PreparedStatement ps =
                                 con.prepareStatement(existingSql)) {
                        ps.setLong(1, trainId);

                        try (ResultSet rs = ps.executeQuery()) {
                            alreadyAllocated = rs.next();
                        }
                    }

                    if (alreadyAllocated) {
                        messages.add(train.get("number")
                                + " already allocated");
                        continue;
                    }

                    boolean assigned = false;

                    for (Map<String, Object> platform : platforms) {
                        int platformLength = (int) platform.get("length");

                        if (trainLength > platformLength) {
                            continue;
                        }

                        long platformId = (long) platform.get("id");

                        String overlapSql = """
                            SELECT COUNT(*)
                            FROM platform_allocations
                            WHERE platform_id = ?
                              AND status = 'ALLOCATED'
                              AND ? < DATE_ADD(
                                  departure_time,
                                  INTERVAL clearance_minutes MINUTE)
                              AND arrival_time < DATE_ADD(
                                  ?, INTERVAL 10 MINUTE)
                            """;

                        boolean overlaps;

                        try (PreparedStatement ps =
                                     con.prepareStatement(overlapSql)) {
                            ps.setLong(1, platformId);
                            ps.setTimestamp(2,
                                    java.sql.Timestamp.valueOf(arrival));
                            ps.setTimestamp(3,
                                    java.sql.Timestamp.valueOf(departure));

                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                overlaps = rs.getInt(1) > 0;
                            }
                        }

                        if (overlaps) {
                            continue;
                        }

                        String insertSql = """
                            INSERT INTO platform_allocations
                                (train_id, platform_id, arrival_time,
                                 departure_time, clearance_minutes, status)
                            VALUES (?, ?, ?, ?, 10, 'ALLOCATED')
                            """;

                        long allocationId;

                        try (PreparedStatement ps = con.prepareStatement(
                                insertSql,
                                PreparedStatement.RETURN_GENERATED_KEYS)) {

                            ps.setLong(1, trainId);
                            ps.setLong(2, platformId);
                            ps.setTimestamp(3,
                                    java.sql.Timestamp.valueOf(arrival));
                            ps.setTimestamp(4,
                                    java.sql.Timestamp.valueOf(departure));

                            ps.executeUpdate();

                            try (ResultSet keys = ps.getGeneratedKeys()) {
                                if (!keys.next()) {
                                    throw new SQLException(
                                            "Allocation ID was not generated");
                                }

                                allocationId = keys.getLong(1);
                            }
                        }

                        String details = "Allocation #" + allocationId
                                + ": Train " + train.get("number")
                                + " - " + train.get("name")
                                + " assigned to Platform "
                                + platform.get("number");

                        logActivity(con, "Platform Allocation",
                                details, "ALLOCATED");

                        messages.add(train.get("number")
                                + " assigned to Platform "
                                + platform.get("number"));

                        allocated++;
                        assigned = true;
                        break;
                    }

                    if (!assigned) {
                        String details = "Train " + train.get("number")
                                + " - " + train.get("name")
                                + " could not be assigned a platform";

                        logActivity(con, "Platform Allocation Pending",
                                details, "PENDING");

                        messages.add(train.get("number")
                                + " pending: no suitable platform/time slot");

                        pending++;
                    }
                }

                con.commit();

            } catch (Exception e) {
                con.rollback();

                if (e instanceof SQLException) {
                    throw (SQLException) e;
                }

                throw new SQLException("Allocation failed", e);

            } finally {
                con.setAutoCommit(true);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("newlyAllocated", allocated);
        response.put("pending", pending);
        response.put("messages", messages);

        return response;
    }

    // Manually allocate a platform
    @PostMapping("/api/allocations/manual")
    public ResponseEntity<Map<String, Object>> manualAllocate(
            @RequestBody Map<String, Object> request) {

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            long trainId = Long.parseLong(
                    String.valueOf(request.get("trainId")));
            long platformId = Long.parseLong(
                    String.valueOf(request.get("platformId")));

            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(false);

                try {
                    String trainSql = """
                        SELECT train_number, train_name, arrival_time,
                               departure_time, platform_length_required
                        FROM trains
                        WHERE id = ?
                        """;

                    String trainNumber;
                    String trainName;
                    LocalDateTime arrival;
                    LocalDateTime departure;
                    int requiredLength;

                    try (PreparedStatement ps =
                                 con.prepareStatement(trainSql)) {
                        ps.setLong(1, trainId);

                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                con.rollback();
                                result.put("success", false);
                                result.put("message",
                                        "Selected train was not found.");
                                return ResponseEntity.badRequest().body(result);
                            }

                            trainNumber = rs.getString("train_number");
                            trainName = rs.getString("train_name");
                            LocalTime arrivalTime =
                                    rs.getTime("arrival_time").toLocalTime();
                            LocalTime departureTime =
                                    rs.getTime("departure_time").toLocalTime();
                            requiredLength =
                                    rs.getInt("platform_length_required");

                            LocalDate today = LocalDate.now();
                            arrival = LocalDateTime.of(today, arrivalTime);
                            departure = LocalDateTime.of(today, departureTime);

                            if (!departure.isAfter(arrival)) {
                                departure = departure.plusDays(1);
                            }
                        }
                    }

                    String platformSql = """
                        SELECT platform_number, platform_length
                        FROM platforms
                        WHERE id = ?
                        """;

                    int platformNumber;
                    int platformLength;

                    try (PreparedStatement ps =
                                 con.prepareStatement(platformSql)) {
                        ps.setLong(1, platformId);

                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                con.rollback();
                                result.put("success", false);
                                result.put("message",
                                        "Selected platform was not found.");
                                return ResponseEntity.badRequest().body(result);
                            }

                            platformNumber =
                                    rs.getInt("platform_number");
                            platformLength =
                                    rs.getInt("platform_length");
                        }
                    }

                    if (requiredLength > platformLength) {
                        con.rollback();
                        result.put("success", false);
                        result.put("message",
                                "Train length exceeds the selected platform length.");
                        return ResponseEntity.badRequest().body(result);
                    }

                    String alreadySql = """
                        SELECT COUNT(*)
                        FROM platform_allocations
                        WHERE train_id = ?
                          AND status = 'ALLOCATED'
                        """;

                    try (PreparedStatement ps =
                                 con.prepareStatement(alreadySql)) {
                        ps.setLong(1, trainId);

                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();

                            if (rs.getInt(1) > 0) {
                                con.rollback();
                                result.put("success", false);
                                result.put("message",
                                        "This train is already allocated. Choose another train.");
                                return ResponseEntity.badRequest().body(result);
                            }
                        }
                    }

                    String overlapSql = """
                        SELECT COUNT(*)
                        FROM platform_allocations
                        WHERE platform_id = ?
                          AND status = 'ALLOCATED'
                          AND ? < DATE_ADD(
                              departure_time,
                              INTERVAL clearance_minutes MINUTE)
                          AND arrival_time < DATE_ADD(
                              ?, INTERVAL 10 MINUTE)
                        """;

                    try (PreparedStatement ps =
                                 con.prepareStatement(overlapSql)) {
                        ps.setLong(1, platformId);
                        ps.setTimestamp(2,
                                java.sql.Timestamp.valueOf(arrival));
                        ps.setTimestamp(3,
                                java.sql.Timestamp.valueOf(departure));

                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();

                            if (rs.getInt(1) > 0) {
                                con.rollback();
                                result.put("success", false);
                                result.put("message",
                                        "Platform time conflict: another train is using this platform or its 10-minute clearance period.");
                                return ResponseEntity.badRequest().body(result);
                            }
                        }
                    }

                    String insertSql = """
                        INSERT INTO platform_allocations
                            (train_id, platform_id, arrival_time,
                             departure_time, clearance_minutes, status)
                        VALUES (?, ?, ?, ?, 10, 'ALLOCATED')
                        """;

                    long allocationId;

                    try (PreparedStatement ps = con.prepareStatement(
                            insertSql,
                            PreparedStatement.RETURN_GENERATED_KEYS)) {

                        ps.setLong(1, trainId);
                        ps.setLong(2, platformId);
                        ps.setTimestamp(3,
                                java.sql.Timestamp.valueOf(arrival));
                        ps.setTimestamp(4,
                                java.sql.Timestamp.valueOf(departure));
                        ps.executeUpdate();

                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException(
                                        "Allocation ID was not generated.");
                            }

                            allocationId = keys.getLong(1);
                        }
                    }

                    String details = "Manual allocation #" + allocationId
                            + ": Train " + trainNumber + " - " + trainName
                            + " assigned to Platform " + platformNumber;

                    logActivity(con, "Platform Allocation",
                            details, "ALLOCATED");

                    con.commit();

                    result.put("success", true);
                    result.put("message", trainNumber
                            + " successfully allocated to Platform "
                            + platformNumber + ".");

                    return ResponseEntity.ok(result);

                } catch (SQLException | RuntimeException e) {
                    con.rollback();
                    throw e;

                } finally {
                    con.setAutoCommit(true);
                }
            }

        } catch (NumberFormatException e) {
            result.put("success", false);
            result.put("message",
                    "Please select a valid train and platform.");
            return ResponseEntity.badRequest().body(result);

        } catch (SQLException e) {
            result.put("success", false);
            result.put("message",
                    "Database error while allocating platform.");
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    // Cancel an existing allocation
    @PostMapping("/api/allocations/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelAllocation(
            @PathVariable long id) {

        Map<String, Object> result = new LinkedHashMap<>();

        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);

            try {
                String sql = """
                    UPDATE platform_allocations
                    SET status = 'CANCELLED'
                    WHERE id = ?
                      AND status = 'ALLOCATED'
                    """;

                int updated;

                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    updated = ps.executeUpdate();
                }

                if (updated == 0) {
                    con.rollback();
                    result.put("success", false);
                    result.put("message",
                            "Allocation not found or already cancelled.");
                    return ResponseEntity.badRequest().body(result);
                }

                logActivity(con,
                        "Platform Allocation Cancelled",
                        "Allocation #" + id + " cancelled",
                        "CANCELLED");

                con.commit();

                result.put("success", true);
                result.put("message",
                        "Allocation cancelled successfully.");

                return ResponseEntity.ok(result);

            } catch (SQLException e) {
                con.rollback();
                throw e;

            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            result.put("success", false);
            result.put("message",
                    "Database error while cancelling allocation.");
            return ResponseEntity.status(
                    HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
}
