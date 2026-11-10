package kr.astra.simulation.entity;

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

/** simulation_runs: 시뮬레이션 실행 기록. 결과마다 DEMO 경고(warning)를 반드시 남깁니다 (장비 데이터 README v0.6 정책) */
@Getter
@Entity
@Table(name = "simulation_runs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "preset_id", length = 20)
    private String presetId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "requirement_id")
    private Long requirementId;

    @Column(name = "model_type", nullable = false, length = 50)
    private String modelType;

    @Column(name = "model_version", nullable = false, length = 20)
    private String modelVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_parameters", nullable = false, columnDefinition = "jsonb")
    private String inputParameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimulationRunStatus status;

    @Column(name = "is_mock", nullable = false)
    private boolean mock;

    @Column(columnDefinition = "text")
    private String warning;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public SimulationRun(String presetId, Long equipmentId, Long userId, Long requirementId, String modelType,
                         String modelVersion, String inputParametersJson, String outputJson,
                         SimulationRunStatus status, boolean mock, String warning) {
        this.presetId = presetId;
        this.equipmentId = equipmentId;
        this.userId = userId;
        this.requirementId = requirementId;
        this.modelType = modelType;
        this.modelVersion = modelVersion;
        this.inputParameters = inputParametersJson;
        this.output = outputJson;
        this.status = status;
        this.mock = mock;
        this.warning = warning;
    }
}
