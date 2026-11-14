package kr.astra.equipment.service;

import java.util.*;
import kr.astra.equipment.dto.EquipmentInputs.SpecInput;
import kr.astra.equipment.entity.SpecDefinition;
import kr.astra.equipment.repository.SpecDefinitionRepository;
import kr.astra.global.exception.*;
import kr.astra.requirement.entity.ConditionValueType;
import kr.astra.requirement.service.UnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class SpecDefinitionService {
    private final SpecDefinitionRepository definitions;

    public record Definition(String specKey, String nameKo, String baseUnit, ConditionValueType valueType) {}
    public List<Definition> list() {
        return definitions.findAll().stream().sorted(Comparator.comparing(SpecDefinition::getSpecKey))
                .map(d -> new Definition(d.getSpecKey(), d.getNameKo(), d.getBaseUnit(), d.getValueType())).toList();
    }
    public SpecInput normalize(SpecInput input) {
        var d = definitions.findById(input.key()).orElseThrow(() -> invalid(input.key()));
        boolean number = input.value() != null && input.min() == null && input.max() == null && input.valueText() == null;
        boolean range = input.value() == null && input.min() != null && input.max() != null
                && input.min().compareTo(input.max()) <= 0 && input.valueText() == null;
        boolean text = input.value() == null && input.min() == null && input.max() == null
                && input.valueText() != null && !input.valueText().isBlank();
        if (d.getValueType() == ConditionValueType.NUMBER && !number
                || d.getValueType() == ConditionValueType.RANGE && !range
                || d.getValueType() == ConditionValueType.TEXT && !text) throw invalid(input.key());
        try {
            if (text && !Objects.equals(input.unit(), d.getBaseUnit())) throw invalid(input.key());
            return new SpecInput(input.key(), d.getNameKo(), UnitConverter.convert(input.value(), input.unit(), d.getBaseUnit()),
                    UnitConverter.convert(input.min(), input.unit(), d.getBaseUnit()),
                    UnitConverter.convert(input.max(), input.unit(), d.getBaseUnit()), input.valueText(), d.getBaseUnit());
        } catch (IllegalArgumentException e) { throw invalid(input.key()); }
    }
    private BusinessException invalid(String key) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "사양 사전의 키·값 형식·단위를 확인해 주세요.", Map.of("specKey", key));
    }
}
