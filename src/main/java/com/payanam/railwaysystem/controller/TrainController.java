package com.payanam.railwaysystem.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrainController {

    private final DataSource dataSource;

    public TrainController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/trains")
    public List<Map<String, Object>> getTrains() throws SQLException {

        List<Map<String, Object>> trains = new ArrayList<>();

        String sql = "SELECT * FROM trains ORDER BY id";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                Map<String, Object> train = new LinkedHashMap<>();

                train.put("id", resultSet.getLong("id"));
                train.put("train_number", resultSet.getString("train_number"));
                train.put("train_name", resultSet.getString("train_name"));
                train.put("arrival_time", resultSet.getTime("arrival_time").toString());
                train.put("departure_time", resultSet.getTime("departure_time").toString());
                train.put("platform_length_required", resultSet.getInt("platform_length_required"));
                train.put("seat_capacity", resultSet.getInt("seat_capacity"));

                trains.add(train);
            }
        }

        return trains;
    }


    // Returns the ordered station-by-station route for one train.
    @GetMapping("/api/trains/{trainId}/route")
    public List<Map<String, Object>> getTrainRoute(@PathVariable long trainId) throws SQLException {
        List<Map<String, Object>> stops = new ArrayList<>();
        String sql = """
                SELECT stop_order, station_name, arrival_time, departure_time, halt_minutes
                FROM train_stops
                WHERE train_id = ?
                ORDER BY stop_order
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, trainId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> stop = new LinkedHashMap<>();
                    stop.put("stopOrder", rs.getInt("stop_order"));
                    stop.put("stationName", rs.getString("station_name"));
                    java.sql.Time arrival = rs.getTime("arrival_time");
                    java.sql.Time departure = rs.getTime("departure_time");
                    stop.put("arrivalTime", arrival == null ? null : arrival.toString());
                    stop.put("departureTime", departure == null ? null : departure.toString());
                    stop.put("haltMinutes", rs.getInt("halt_minutes"));
                    stops.add(stop);
                }
            }
        }
        return stops;
    }

    @GetMapping("/api/db-test")
    public String testDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return "MySQL Database Connected Successfully!";
        } catch (SQLException e) {
            return "Database connection failed: " + e.getMessage();
        }
    }
}