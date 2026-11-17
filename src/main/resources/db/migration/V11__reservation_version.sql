-- 예약 낙관적 잠금: 오래된 사본으로 상태를 덮어쓰지 못하게 (Reservation.@Version)
ALTER TABLE reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
