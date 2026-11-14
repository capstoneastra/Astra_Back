package kr.astra.equipment.entity;

import jakarta.persistence.*;
import kr.astra.requirement.entity.ConditionValueType;
import lombok.*;

@Entity @Table(name = "spec_definitions") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpecDefinition {
    @Id @Column(name = "spec_key", length = 80) private String specKey;
    @Column(name = "name_ko", nullable = false, length = 100) private String nameKo;
    @Column(name = "base_unit", length = 30) private String baseUnit;
    @Enumerated(EnumType.STRING) @Column(name = "value_type", nullable = false, length = 10)
    private ConditionValueType valueType;
}
