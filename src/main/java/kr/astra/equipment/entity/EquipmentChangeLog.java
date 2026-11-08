package kr.astra.equipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * equipment_change_logs: 장비 변경 이력. changes 는 {"필드": {"before": .., "after": ..}} JSON.
 * JSON 변환은 서비스에서 하고 여기에는 문자열로 저장합니다.
 */
@Getter
@Entity
@Table(name = "equipment_change_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EquipmentChangeAction action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String changes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public EquipmentChangeLog(Long equipmentId, Long changedBy, EquipmentChangeAction action, String changesJson) {
        this.equipmentId = equipmentId;
        this.changedBy = changedBy;
        this.action = action;
        this.changes = changesJson;
    }
}
