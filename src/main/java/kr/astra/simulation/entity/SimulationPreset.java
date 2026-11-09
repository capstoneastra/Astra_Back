package kr.astra.simulation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * simulation_presets: 장비별 시연 프리셋 (seed, 읽기 전용). JSON 컬럼은 문자열로 받고 서비스에서 변환합니다.
 * specs_snapshot = [{"key","label","value"|"min","max","unit","sourceType":"MOCK",...}]
 */
@Getter
@Entity
@Table(name = "simulation_presets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulationPreset {

    @Id
    @Column(name = "preset_id", length = 20)
    private String presetId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "model_type", nullable = false, length = 50)
    private String modelType;

    @Column(name = "simulation_type", nullable = false, length = 50)
    private String simulationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_parameters", nullable = false, columnDefinition = "jsonb")
    private String inputParameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specs_snapshot", columnDefinition = "jsonb")
    private String specsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String assumptions;

    @Column(name = "is_mock", nullable = false)
    private boolean mock;
}
