

package com.payanam.railwaysystem.controller;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {

    private final DataSource dataSource;

    public ReservationController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ==========================================
    // ACTIVITY LOG
    // ==========================================

    private void logActivity(
            Connection connection,
            String type,
            String details,
            String status) throws SQLException {

        String sql = """
            INSERT INTO activity_logs (activity_type, details, status)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setString(1, type);
            ps.setString(2, details);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // SAVE NOTIFICATION
    // ==========================================

    private void saveNotification(
            Connection connection,
            long passengerId,
            String message,
            String type) throws SQLException {

        String sql = """
            INSERT INTO notifications
                (passenger_id, message, notification_type)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setLong(1, passengerId);
            ps.setString(2, message);
            ps.setString(3, type);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // GET TRAIN SEAT AVAILABILITY
    // ==========================================

    @GetMapping("/api/trains/{trainId}/seat-availability")
    public Map<String, Object> getSeatAvailability(
            @PathVariable long trainId) throws SQLException {

        String sql = """
            SELECT
                t.id AS trainId,
                t.train_number AS trainNumber,
                t.train_name AS trainName,
                t.seat_capacity AS totalSeats,

                COUNT(
                    CASE WHEN r.status = 'CONFIRMED'
                    THEN r.id END
                ) AS bookedSeats,

                COUNT(
                    CASE WHEN r.status = 'WAITING'
                    THEN r.id END
                ) AS waitingPassengers

            FROM trains t

            LEFT JOIN reservations r
                ON t.id = r.train_id

            WHERE t.id = ?

            GROUP BY
                t.id,
                t.train_number,
                t.train_name,
                t.seat_capacity
            """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setLong(1, trainId);

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    throw new IllegalArgumentException("Train not found");
                }

                int totalSeats = rs.getInt("totalSeats");
                int bookedSeats = rs.getInt("bookedSeats");

                Map<String, Object> result = new LinkedHashMap<>();

                result.put("trainId", rs.getLong("trainId"));
                result.put("trainNumber", rs.getString("trainNumber"));
                result.put("trainName", rs.getString("trainName"));
                result.put("totalSeats", totalSeats);
                result.put("bookedSeats", bookedSeats);

                result.put(
                    "availableSeats",
                    Math.max(0, totalSeats - bookedSeats)
                );

                result.put(
                    "waitingPassengers",
                    rs.getInt("waitingPassengers")
                );

                result.put(
                    "status",
                    bookedSeats >= totalSeats
                        ? "FULL"
                        : "AVAILABLE"
                );

                return result;
            }
        }
    }

    // ==========================================
    // BOOK SINGLE TICKET
    // ==========================================

    @PostMapping("/api/reservations")
    public Map<String, Object> bookTicket(
            @RequestBody Map<String, Object> request)
            throws SQLException {

        String passengerName =
                request.get("passengerName") == null
                        ? ""
                        : request.get("passengerName")
                                 .toString().trim();

        String ageText =
                request.get("age") == null
                        ? ""
                        : request.get("age").toString();

        String phone =
                request.get("phone") == null
                        ? ""
                        : request.get("phone")
                                 .toString().trim();

        if (passengerName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Passenger name is required");
        }

        if (passengerName.length() > 100) {
            throw new IllegalArgumentException(
                    "Name must be under 100 characters");
        }

        int age;

        try {
            age = Integer.parseInt(ageText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Enter a valid age");
        }

        if (age < 1 || age > 120) {
            throw new IllegalArgumentException(
                    "Age must be between 1 and 120");
        }

        if (phone.length() > 15) {
            throw new IllegalArgumentException(
                    "Phone number is too long");
        }

        long trainId;

        try {
            trainId = Long.parseLong(
                    request.get("trainId").toString());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Please select a train");
        }

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                int capacity;
                String trainName;

                // Lock the train row to prevent concurrent overbooking
                String trainSql = """
                    SELECT train_name, seat_capacity
                    FROM trains
                    WHERE id = ?
                    FOR UPDATE
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(trainSql)) {

                    ps.setLong(1, trainId);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            throw new SQLException("Train not found");
                        }

                        trainName = rs.getString("train_name");
                        capacity = rs.getInt("seat_capacity");
                    }
                }

                // Save passenger
                long passengerId;

                String passengerSql = """
                    INSERT INTO passengers
                        (passenger_name, age, phone)
                    VALUES (?, ?, ?)
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(
                                     passengerSql,
                                     Statement.RETURN_GENERATED_KEYS)) {

                    ps.setString(1, passengerName);
                    ps.setInt(2, age);

                    if (phone.isEmpty()) {
                        ps.setNull(3, Types.VARCHAR);
                    } else {
                        ps.setString(3, phone);
                    }

                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {

                        if (!keys.next()) {
                            throw new SQLException(
                                    "Could not save passenger");
                        }

                        passengerId = keys.getLong(1);
                    }
                }

                // Find occupied seats
                Set<Integer> occupiedSeats = new HashSet<>();

                String seatSql = """
                    SELECT seat_number
                    FROM reservations
                    WHERE train_id = ?
                      AND status = 'CONFIRMED'
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(seatSql)) {

                    ps.setLong(1, trainId);

                    try (ResultSet rs = ps.executeQuery()) {

                        while (rs.next()) {

                            int seat = rs.getInt("seat_number");

                            if (!rs.wasNull()) {
                                occupiedSeats.add(seat);
                            }
                        }
                    }
                }

                // Find first available seat
                Integer seatNumber = null;

                for (int seat = 1; seat <= capacity; seat++) {

                    if (!occupiedSeats.contains(seat)) {
                        seatNumber = seat;
                        break;
                    }
                }

                String status = seatNumber != null
                        ? "CONFIRMED"
                        : "WAITING";

                // Create reservation
                long reservationId;

                String insertSql = """
                    INSERT INTO reservations
                        (passenger_id, train_id, seat_number, status)
                    VALUES (?, ?, ?, ?)
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(
                                     insertSql,
                                     Statement.RETURN_GENERATED_KEYS)) {

                    ps.setLong(1, passengerId);
                    ps.setLong(2, trainId);

                    if (seatNumber == null) {
                        ps.setNull(3, Types.INTEGER);
                    } else {
                        ps.setInt(3, seatNumber);
                    }

                    ps.setString(4, status);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {

                        if (!keys.next()) {
                            throw new SQLException(
                                    "Could not create reservation");
                        }

                        reservationId = keys.getLong(1);
                    }
                }

                // Activity log
                String activityType = seatNumber != null
                        ? "Reservation Booking"
                        : "Waiting List";

                String details = seatNumber != null
                        ? "Reservation #" + reservationId
                            + ": " + passengerName
                            + " booked " + trainName
                            + ", seat " + seatNumber
                        : "Reservation #" + reservationId
                            + ": " + passengerName
                            + " added to waiting list for "
                            + trainName;

                logActivity(
                        connection,
                        activityType,
                        details,
                        status
                );

                // Booking notification
                String notificationMessage;

                if ("CONFIRMED".equals(status)) {

                    notificationMessage =
                            "Your ticket for " + trainName
                            + " is confirmed. Seat number: "
                            + seatNumber;

                } else {

                    notificationMessage =
                            "Your booking for " + trainName
                            + " is in the waiting list.";
                }

                saveNotification(
                        connection,
                        passengerId,
                        notificationMessage,
                        "CONFIRMED".equals(status)
                                ? "BOOKING"
                                : "WAITING"
                );

                connection.commit();

                Map<String, Object> response = new LinkedHashMap<>();

                response.put("reservationId", reservationId);
                response.put("passengerId", passengerId);
                response.put("trainId", trainId);
                response.put("passengerName", passengerName);
                response.put("trainName", trainName);
                response.put("status", status);
                response.put("seatNumber", seatNumber);
                response.put("notification", notificationMessage);

                return response;

            } catch (Exception e) {

                connection.rollback();

                if (e instanceof SQLException) {
                    throw (SQLException) e;
                }

                if (e instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e;
                }

                throw new SQLException(
                        "Booking failed: " + e.getMessage(), e);

            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ==========================================
    // GROUP BOOKING
    // ==========================================

    @PostMapping("/api/reservations/group")
    public Map<String, Object> bookMultipleTickets(
            @RequestBody Map<String, Object> request)
            throws SQLException {

        Object trainValue = request.get("trainId");
        Object passengersValue = request.get("passengers");

        if (trainValue == null) {
            throw new IllegalArgumentException(
                    "Please select a train");
        }

        if (!(passengersValue instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Passenger list is required");
        }

        long trainId;

        try {
            trainId = Long.parseLong(trainValue.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid train ID");
        }

        List<?> passengers = (List<?>) passengersValue;

        if (passengers.isEmpty() || passengers.size() > 6) {
            throw new IllegalArgumentException(
                    "Enter between 1 and 6 passengers");
        }

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                int capacity;
                String trainName;

                // Lock train row
                String trainSql = """
                    SELECT train_name, seat_capacity
                    FROM trains
                    WHERE id = ?
                    FOR UPDATE
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(trainSql)) {

                    ps.setLong(1, trainId);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            throw new IllegalArgumentException(
                                    "Train not found");
                        }

                        trainName = rs.getString("train_name");
                        capacity = rs.getInt("seat_capacity");
                    }
                }

                // Existing occupied seats
                Set<Integer> occupiedSeats = new HashSet<>();

                String seatSql = """
                    SELECT seat_number
                    FROM reservations
                    WHERE train_id = ?
                      AND status = 'CONFIRMED'
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(seatSql)) {

                    ps.setLong(1, trainId);

                    try (ResultSet rs = ps.executeQuery()) {

                        while (rs.next()) {

                            int seat = rs.getInt("seat_number");

                            if (!rs.wasNull()) {
                                occupiedSeats.add(seat);
                            }
                        }
                    }
                }

                List<Map<String, Object>> tickets = new ArrayList<>();

                for (Object passengerObject : passengers) {

                    if (!(passengerObject instanceof Map<?, ?>)) {
                        throw new IllegalArgumentException(
                                "Invalid passenger details");
                    }

                    Map<?, ?> passenger = (Map<?, ?>) passengerObject;

                    Object nameValue = passenger.get("passengerName");
                    Object ageValue = passenger.get("age");
                    Object phoneValue = passenger.get("phone");

                    String passengerName = nameValue == null
                            ? ""
                            : nameValue.toString().trim();

                    if (passengerName.isEmpty()
                            || passengerName.length() > 100) {

                        throw new IllegalArgumentException(
                                "Enter a valid passenger name "
                                + "(maximum 100 characters)");
                    }

                    int age;

                    try {
                        age = Integer.parseInt(
                                ageValue == null
                                        ? ""
                                        : ageValue.toString());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Enter a valid age for "
                                + passengerName);
                    }

                    if (age < 1 || age > 120) {
                        throw new IllegalArgumentException(
                                "Age must be between 1 and 120 for "
                                + passengerName);
                    }

                    String phone = phoneValue == null
                            ? ""
                            : phoneValue.toString().trim();

                    if (phone.length() > 15) {
                        throw new IllegalArgumentException(
                                "Phone number must be 15 characters "
                                + "or fewer for " + passengerName);
                    }

                    // Save passenger
                    long passengerId;

                    String passengerSql = """
                        INSERT INTO passengers
                            (passenger_name, age, phone)
                        VALUES (?, ?, ?)
                        """;

                    try (PreparedStatement ps =
                                 connection.prepareStatement(
                                         passengerSql,
                                         Statement.RETURN_GENERATED_KEYS)) {

                        ps.setString(1, passengerName);
                        ps.setInt(2, age);

                        if (phone.isEmpty()) {
                            ps.setNull(3, Types.VARCHAR);
                        } else {
                            ps.setString(3, phone);
                        }

                        ps.executeUpdate();

                        try (ResultSet keys = ps.getGeneratedKeys()) {

                            if (!keys.next()) {
                                throw new SQLException(
                                        "Could not save passenger");
                            }

                            passengerId = keys.getLong(1);
                        }
                    }

                    // Allocate seat
                    Integer seatNumber = null;

                    for (int seat = 1; seat <= capacity; seat++) {

                        if (!occupiedSeats.contains(seat)) {
                            seatNumber = seat;
                            break;
                        }
                    }

                    String status = seatNumber == null
                            ? "WAITING"
                            : "CONFIRMED";

                    // Save reservation
                    long reservationId;

                    String reservationSql = """
                        INSERT INTO reservations
                            (passenger_id, train_id, seat_number, status)
                        VALUES (?, ?, ?, ?)
                        """;

                    try (PreparedStatement ps =
                                 connection.prepareStatement(
                                         reservationSql,
                                         Statement.RETURN_GENERATED_KEYS)) {

                        ps.setLong(1, passengerId);
                        ps.setLong(2, trainId);

                        if (seatNumber == null) {
                            ps.setNull(3, Types.INTEGER);
                        } else {
                            ps.setInt(3, seatNumber);
                        }

                        ps.setString(4, status);
                        ps.executeUpdate();

                        try (ResultSet keys = ps.getGeneratedKeys()) {

                            if (!keys.next()) {
                                throw new SQLException(
                                        "Could not create reservation");
                            }

                            reservationId = keys.getLong(1);
                        }
                    }

                    if (seatNumber != null) {
                        occupiedSeats.add(seatNumber);
                    }

                    // Activity log
                    String activityType = seatNumber == null
                            ? "Waiting List"
                            : "Reservation Booking";

                    String details = seatNumber == null
                            ? "Reservation #" + reservationId
                                + ": " + passengerName
                                + " added to waiting list for "
                                + trainName
                            : "Reservation #" + reservationId
                                + ": " + passengerName
                                + " booked " + trainName
                                + ", seat " + seatNumber;

                    logActivity(
                            connection,
                            activityType,
                            details,
                            status
                    );

                    // Notification
                    String notificationMessage;

                    if ("CONFIRMED".equals(status)) {

                        notificationMessage =
                                "Your ticket for " + trainName
                                + " is confirmed. Seat number: "
                                + seatNumber;

                    } else {

                        notificationMessage =
                                "Your booking for " + trainName
                                + " is in the waiting list.";
                    }

                    saveNotification(
                            connection,
                            passengerId,
                            notificationMessage,
                            "CONFIRMED".equals(status)
                                    ? "BOOKING"
                                    : "WAITING"
                    );

                    Map<String, Object> ticket = new LinkedHashMap<>();

                    ticket.put("reservationId", reservationId);
                    ticket.put("passengerId", passengerId);
                    ticket.put("passengerName", passengerName);
                    ticket.put("trainName", trainName);
                    ticket.put("status", status);
                    ticket.put("seatNumber", seatNumber);
                    ticket.put("notification", notificationMessage);

                    tickets.add(ticket);
                }

                connection.commit();

                Map<String, Object> response = new LinkedHashMap<>();

                response.put("trainId", trainId);
                response.put("trainName", trainName);
                response.put("passengerCount", tickets.size());
                response.put("tickets", tickets);

                response.put(
                        "message",
                        "Group booking processed successfully");

                return response;

            } catch (Exception e) {

                connection.rollback();

                if (e instanceof SQLException) {
                    throw (SQLException) e;
                }

                if (e instanceof IllegalArgumentException) {
                    throw (IllegalArgumentException) e;
                }

                throw new SQLException(
                        "Group booking failed", e);

            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ==========================================
    // VIEW ALL RESERVATIONS
    // ==========================================

    @GetMapping("/api/reservations")
    public List<Map<String, Object>> getReservations()
            throws SQLException {

        List<Map<String, Object>> reservations = new ArrayList<>();

        String sql = """
            SELECT r.id, p.passenger_name, t.train_name,
                   r.seat_number, r.status, r.booking_time
            FROM reservations r
            JOIN passengers p ON r.passenger_id = p.id
            JOIN trains t ON r.train_id = t.id
            ORDER BY r.id
            """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {

                Map<String, Object> reservation = new LinkedHashMap<>();

                reservation.put("id", rs.getLong("id"));
                reservation.put(
                        "passengerName",
                        rs.getString("passenger_name"));
                reservation.put(
                        "trainName",
                        rs.getString("train_name"));
                reservation.put(
                        "seatNumber",
                        rs.getObject("seat_number"));
                reservation.put(
                        "status",
                        rs.getString("status"));
                reservation.put(
                        "bookingTime",
                        rs.getTimestamp("booking_time"));

                reservations.add(reservation);
            }
        }

        return reservations;
    }

    // ==========================================
    // CANCEL TICKET AND PROMOTE WAITING PASSENGER
    // ==========================================

    @PostMapping("/api/reservations/{id}/cancel")
    public Map<String, Object> cancelReservation(
            @PathVariable long id) throws SQLException {

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {
                long trainId;
                long passengerId;

                String status;
                Integer seatNumber;
                String passengerName;
                String trainName;

                String findSql = """
                    SELECT r.train_id, r.passenger_id,
                           r.status, r.seat_number,
                           p.passenger_name, t.train_name
                    FROM reservations r
                    JOIN passengers p ON r.passenger_id = p.id
                    JOIN trains t ON r.train_id = t.id
                    WHERE r.id = ?
                    FOR UPDATE
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(findSql)) {

                    ps.setLong(1, id);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            throw new SQLException(
                                    "Reservation not found");
                        }

                        trainId = rs.getLong("train_id");
                        passengerId = rs.getLong("passenger_id");
                        status = rs.getString("status");

                        seatNumber = (Integer) rs.getObject("seat_number");

                        passengerName = rs.getString("passenger_name");
                        trainName = rs.getString("train_name");
                    }
                }

                if (!"CONFIRMED".equals(status)
                        || seatNumber == null) {

                    throw new SQLException(
                            "Only confirmed tickets can be cancelled");
                }

                // Cancel reservation
                String cancelSql = """
                    UPDATE reservations
                    SET status = 'CANCELLED', seat_number = NULL
                    WHERE id = ?
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(cancelSql)) {

                    ps.setLong(1, id);
                    ps.executeUpdate();
                }

                logActivity(
                        connection,
                        "Reservation Cancellation",
                        "Reservation #" + id + ": "
                                + passengerName
                                + " cancelled ticket for "
                                + trainName,
                        "CANCELLED"
                );

                saveNotification(
                        connection,
                        passengerId,
                        "Your ticket for " + trainName
                                + " has been cancelled.",
                        "CANCELLATION"
                );

                // Find first waiting passenger
                Long waitingId = null;
                Long waitingPassengerId = null;
                String waitingPassenger = null;

                String waitingSql = """
                    SELECT r.id, r.passenger_id, p.passenger_name
                    FROM reservations r
                    JOIN passengers p ON r.passenger_id = p.id
                    WHERE r.train_id = ?
                      AND r.status = 'WAITING'
                    ORDER BY r.booking_time, r.id
                    LIMIT 1 FOR UPDATE
                    """;

                try (PreparedStatement ps =
                             connection.prepareStatement(waitingSql)) {

                    ps.setLong(1, trainId);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (rs.next()) {
                            waitingId = rs.getLong("id");
                            waitingPassengerId = rs.getLong("passenger_id");
                            waitingPassenger = rs.getString("passenger_name");
                        }
                    }
                }

                // Promote waiting passenger
                if (waitingId != null) {

                    String promoteSql = """
                        UPDATE reservations
                        SET status = 'CONFIRMED', seat_number = ?
                        WHERE id = ? AND status = 'WAITING'
                        """;

                    try (PreparedStatement ps =
                                 connection.prepareStatement(promoteSql)) {

                        ps.setInt(1, seatNumber);
                        ps.setLong(2, waitingId);
                        ps.executeUpdate();
                    }

                    logActivity(
                            connection,
                            "Waiting List Promotion",
                            "Reservation #" + waitingId + ": "
                                    + waitingPassenger
                                    + " promoted to seat "
                                    + seatNumber
                                    + " on " + trainName,
                            "CONFIRMED"
                    );

                    saveNotification(
                            connection,
                            waitingPassengerId,
                            "Good news! Your ticket for "
                                    + trainName
                                    + " is confirmed. Seat number: "
                                    + seatNumber,
                            "CONFIRMATION"
                    );
                }

                connection.commit();

                Map<String, Object> response = new LinkedHashMap<>();

                response.put("cancelledReservationId", id);

                response.put(
                        "message",
                        waitingId != null
                                ? "Ticket cancelled; waiting passenger promoted"
                                : "Ticket cancelled; no waiting passenger");

                response.put(
                        "notification",
                        "CANCELLATION notification saved");

                if (waitingId != null) {
                    response.put("promotedReservationId", waitingId);
                    response.put("promotedPassenger", waitingPassenger);
                    response.put("promotedSeatNumber", seatNumber);
                }

                return response;

            } catch (Exception e) {

                connection.rollback();

                if (e instanceof SQLException) {
                    throw (SQLException) e;
                }

                throw new SQLException(
                        "Cancellation failed: " + e.getMessage(), e);

            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ==========================================
    // VIEW NOTIFICATIONS
    // ==========================================

    @GetMapping("/api/notifications")
    public List<Map<String, Object>> getNotifications()
            throws SQLException {

        List<Map<String, Object>> notifications = new ArrayList<>();

        String sql = """
            SELECT n.id, n.passenger_id, p.passenger_name,
                   n.message, n.notification_type,
                   n.is_read, n.created_at
            FROM notifications n
            JOIN passengers p ON n.passenger_id = p.id
            ORDER BY n.id DESC
            """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {

                Map<String, Object> notification = new LinkedHashMap<>();

                notification.put("id", rs.getLong("id"));
                notification.put(
                        "passengerId",
                        rs.getLong("passenger_id"));
                notification.put(
                        "passengerName",
                        rs.getString("passenger_name"));
                notification.put(
                        "message",
                        rs.getString("message"));
                notification.put(
                        "type",
                        rs.getString("notification_type"));
                notification.put(
                        "isRead",
                        rs.getBoolean("is_read"));
                notification.put(
                        "createdAt",
                        rs.getTimestamp("created_at"));

                notifications.add(notification);
            }
        }

        return notifications;
    }

    // ==========================================
    // MARK NOTIFICATION AS READ
    // ==========================================

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Object> markNotificationAsRead(
            @PathVariable long id) throws SQLException {

        Map<String, Object> response = new LinkedHashMap<>();

        String sql = """
            UPDATE notifications
            SET is_read = TRUE
            WHERE id = ?
            """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setLong(1, id);

            int updated = ps.executeUpdate();

            if (updated > 0) {
                response.put("success", true);
                response.put("message", "Notification marked as read");
            } else {
                response.put("success", false);
                response.put("message", "Notification not found");
            }
        }

        return response;
    }
    @DeleteMapping("/api/notifications/{id}")
public Map<String, Object> deleteNotification(
        @PathVariable long id) throws SQLException {

    Map<String, Object> response = new LinkedHashMap<>();

    String sql = "DELETE FROM notifications WHERE id = ?";

    try (Connection connection = dataSource.getConnection();
         PreparedStatement ps = connection.prepareStatement(sql)) {

        ps.setLong(1, id);

        int deleted = ps.executeUpdate();

        if (deleted > 0) {
            response.put("success", true);
            response.put("message", "Notification dismissed successfully");
        } else {
            response.put("success", false);
            response.put("message", "Notification not found");
        }
    }

    return response;
}
}
