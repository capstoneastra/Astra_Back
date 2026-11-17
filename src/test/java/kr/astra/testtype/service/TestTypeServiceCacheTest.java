package kr.astra.testtype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.equipment.service.SpecDefinitionService;
import kr.astra.organization.service.OrganizationService;
import kr.astra.testtype.entity.TestType;
import kr.astra.testtype.repository.ConditionAliasQuery;
import kr.astra.testtype.repository.TestConditionRuleRepository;
import kr.astra.testtype.repository.TestTypeRepository;
import org.junit.jupiter.api.Test;

/** 시험유형(15개, 마이그레이션으로만 바뀜)은 한 번 읽어 메모리에 둔다 */
class TestTypeServiceCacheTest {

    private final TestTypeRepository repository = mock(TestTypeRepository.class);
    private final TestTypeService service = new TestTypeService(repository, mock(TestConditionRuleRepository.class),
            mock(EquipmentService.class), mock(OrganizationService.class), mock(SpecDefinitionService.class),
            mock(ConditionAliasQuery.class));

    @Test
    void 전체_목록과_이름표는_한_번만_조회한다() {
        TestType vibration = mock(TestType.class);
        when(vibration.getCode()).thenReturn("VIBRATION");
        when(vibration.getNameKo()).thenReturn("진동시험");
        when(repository.findAllByOrderByDisplayOrderAscCodeAsc()).thenReturn(List.of(vibration));

        assertThat(service.findAllOrdered()).containsExactly(vibration);
        assertThat(service.findAllOrdered()).containsExactly(vibration);
        assertThat(service.nameMap()).containsEntry("VIBRATION", "진동시험");
        assertThat(service.nameMap()).hasSize(1);

        verify(repository, times(1)).findAllByOrderByDisplayOrderAscCodeAsc();
    }
}
