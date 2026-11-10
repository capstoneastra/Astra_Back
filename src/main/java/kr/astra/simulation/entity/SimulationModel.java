package kr.astra.simulation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * simulation_models: 가상 시뮬레이션 모델 카탈로그 (seed 20종, 읽기 전용). 전부 DEMO — 실장비 검증 아님.
 * is_implemented 는 seed 값(false)이고, 실제로 계산하는지는 코드(SimulationEngine.supports)가 기준입니다.
 */
@Getter
@Entity
@Table(name = "simulation_models")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulationModel {

    @Id
    @Column(name = "model_type", length = 50)
    private String modelType;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "required_input_fields", nullable = false, columnDefinition = "text[]")
    private List<String> requiredInputFields;

    /** 화면에 반드시 노출 */
    @Column(nullable = false, columnDefinition = "text")
    private String limitation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SimulationCapability capability;

    @Column(name = "model_version", nullable = false, length = 20)
    private String modelVersion;

    @Column(name = "is_implemented", nullable = false)
    private boolean implemented;
}
