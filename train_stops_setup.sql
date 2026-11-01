-- Run this once in MySQL Workbench after selecting railway_db.
-- This stores the station-by-station route for each existing train.
CREATE TABLE IF NOT EXISTS train_stops (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    train_id BIGINT NOT NULL,
    stop_order INT NOT NULL,
    station_name VARCHAR(120) NOT NULL,
    arrival_time TIME NULL,
    departure_time TIME NULL,
    halt_minutes INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_train_stops_train
        FOREIGN KEY (train_id) REFERENCES trains(id)
        ON DELETE CASCADE,
    CONSTRAINT uq_train_stop_order UNIQUE (train_id, stop_order)
);

-- After running the CREATE TABLE above, get existing train IDs:
-- SELECT id, train_number, train_name FROM trains ORDER BY id;
-- Then insert the REAL route stations and timetable for each train.
-- Example format only (replace 1 and the sample station/times with verified data):
-- INSERT INTO train_stops
-- (train_id, stop_order, station_name, arrival_time, departure_time, halt_minutes)
-- VALUES
-- (1, 1, 'Origin Station', NULL, '06:00:00', 0),
-- (1, 2, 'Payanam Junction', '08:30:00', '08:40:00', 10),
-- (1, 3, 'Destination Station', '11:15:00', NULL, 0);
