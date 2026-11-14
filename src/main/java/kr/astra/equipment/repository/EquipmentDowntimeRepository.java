package kr.astra.equipment.repository;

import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class EquipmentDowntimeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public record Downtime(Long id, Long equipmentId, OffsetDateTime startAt, OffsetDateTime endAt, String reason,
                           Long createdBy, OffsetDateTime createdAt) {}
    public List<Downtime> overlapping(Long id, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query("SELECT * FROM equipment_downtimes WHERE equipment_id=:id AND start_at<:to AND end_at>:from ORDER BY start_at,id",
                Map.of("id",id,"from",from,"to",to), (rs,n) -> new Downtime(rs.getLong("id"),rs.getLong("equipment_id"),
                        rs.getObject("start_at",OffsetDateTime.class),rs.getObject("end_at",OffsetDateTime.class),rs.getString("reason"),
                        rs.getLong("created_by"),rs.getObject("created_at",OffsetDateTime.class)));
    }
    public Long insert(Long id, OffsetDateTime start, OffsetDateTime end, String reason, Long actor) {
        return jdbc.queryForObject("INSERT INTO equipment_downtimes(equipment_id,start_at,end_at,reason,created_by) "
                + "VALUES (:id,:start,:end,:reason,:actor) RETURNING id",
                Map.of("id",id,"start",start,"end",end,"reason",reason,"actor",actor),Long.class);
    }
    public int delete(Long equipmentId, Long id) {
        return jdbc.update("DELETE FROM equipment_downtimes WHERE equipment_id=:equipment AND id=:id", Map.of("equipment",equipmentId,"id",id));
    }
}
