package kr.astra.equipment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;
import kr.astra.equipment.dto.EquipmentInputs.SpecInput;
import kr.astra.equipment.entity.SpecDefinition;
import kr.astra.equipment.repository.SpecDefinitionRepository;
import kr.astra.global.exception.BusinessException;
import kr.astra.requirement.entity.ConditionValueType;
import org.junit.jupiter.api.Test;

class SpecDefinitionServiceTest {
    private final SpecDefinitionRepository repository = mock(SpecDefinitionRepository.class);
    private final SpecDefinitionService service = new SpecDefinitionService(repository);

    @Test
    void RF_원문_GHz를_진동시험과_같은_Hz로_저장한다() {
        definition("frequency", "Hz", ConditionValueType.RANGE);
        var result = service.normalize(range("frequency", "75", "110", "GHz"));
        assertThat(result.unit()).isEqualTo("Hz");
        assertThat(result.min()).isEqualByComparingTo("75000000000");
        assertThat(result.max()).isEqualByComparingTo("110000000000");
    }

    @Test
    void 없는_키와_다른_차원의_단위와_혼합_값을_거부한다() {
        assertThatThrownBy(() -> service.normalize(range("invented", "1", "2", "Hz")))
                .isInstanceOf(BusinessException.class);
        definition("frequency", "Hz", ConditionValueType.RANGE);
        assertThatThrownBy(() -> service.normalize(range("frequency", "1", "2", "kg")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.normalize(new SpecInput("frequency", "주파수", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.TEN, null, "Hz"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.normalize(range("frequency", "10", "1", "Hz")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 숫자_사양에_텍스트나_범위를_대신_저장하지_않는다() {
        definition("max_payload", "kg", ConditionValueType.NUMBER);
        assertThatThrownBy(() -> service.normalize(new SpecInput("max_payload", "시료 질량", null,
                null, null, "40", "kg"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.normalize(range("max_payload", "1", "40", "kg")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 같은_키의_사전은_한_번만_조회한다() {
        definition("frequency", "Hz", ConditionValueType.RANGE);
        service.normalize(range("frequency", "1", "2", "Hz"));
        service.normalize(range("frequency", "3", "4", "kHz"));
        verify(repository, times(1)).findById("frequency");
    }

    private void definition(String key, String unit, ConditionValueType type) {
        SpecDefinition d = mock(SpecDefinition.class);
        when(d.getNameKo()).thenReturn(key);
        when(d.getBaseUnit()).thenReturn(unit);
        when(d.getValueType()).thenReturn(type);
        when(repository.findById(key)).thenReturn(Optional.of(d));
    }
    private SpecInput range(String key, String min, String max, String unit) {
        return new SpecInput(key, key, null, new BigDecimal(min), new BigDecimal(max), null, unit);
    }
}
