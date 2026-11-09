package kr.astra.requirement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.repository.RequirementRepository;
import kr.astra.requirement.service.*;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
class RequirementServiceTest {
    final CurrentUser owner=new CurrentUser(1L,UserRole.COMPANY_USER,null);
    final RequirementRepository repository=mock(RequirementRepository.class);
    final TestTypeService types=mock(TestTypeService.class);
    final RequirementApplicationService service=new RequirementApplicationService(repository,types,new ConditionValidator());
    final ConditionRuleResponse rule=new ConditionRuleResponse("mass","질량",ConditionValueType.NUMBER,"kg",true,"payload",ConditionComparator.MAX_LIMIT);
    TestRequirement requirement;
    ConditionInput c(ConditionStatus status) {
        return new ConditionInput("mass",status == ConditionStatus.MISSING ? null : BigDecimal.TEN,null,null,null,"kg",status,null,null);
    }
    @BeforeEach void setup() {
        requirement=new TestRequirement(1L,null,new RequirementInput("VIBRATION","시험",null,null,1,List.of()));
        when(repository.findById(7L)).thenReturn(Optional.of(requirement));
        when(repository.lockById(7L)).thenReturn(Optional.of(requirement));
        when(types.getConditionRules("VIBRATION")).thenReturn(List.of(rule));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void missingAndUnconfirmedPreventConfirmation() {
        assertThat(service.analyze(owner,7L).missing()).hasSize(1);
        assertThatThrownBy(() -> service.confirm(owner,7L)).isInstanceOf(BusinessException.class);
        service.saveConditions(owner,7L,List.of(c(ConditionStatus.UNCONFIRMED)));
        assertThat(service.analyze(owner,7L).unconfirmed()).hasSize(1);
        assertThatThrownBy(() -> service.confirm(owner,7L)).isInstanceOf(BusinessException.class);
    }
    @Test void confirmedConditionsCanBeConfirmedButNeverEditedOrReconfirmed() {
        service.saveConditions(owner,7L,List.of(c(ConditionStatus.CONFIRMED)));
        assertThat(service.confirm(owner,7L).status()).isEqualTo(RequirementStatus.CONFIRMED);
        assertThatThrownBy(() -> service.saveConditions(owner,7L,List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(owner,7L,"new",null,null,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.confirm(owner,7L)).isInstanceOf(BusinessException.class);
    }
    @Test void aiCannotOverwriteUserValue() {
        service.saveConditions(owner,7L,List.of(c(ConditionStatus.CONFIRMED)));
        service.mergeExtracted(owner,7L,List.of(c(ConditionStatus.MISSING)));
        assertThat(service.get(owner,7L).conditions().getFirst().value()).isEqualByComparingTo("10");
        assertThat(service.get(owner,7L).conditions().getFirst().source()).isEqualTo(ConditionSource.USER);
    }
    @Test void noPrincipalOtherOwnerAndInstitutionDeniedButAdminAllowed() {
        assertThatThrownBy(() -> service.get(null,7L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(new CurrentUser(2L,UserRole.COMPANY_USER,null),7L))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.get(new CurrentUser(1L,UserRole.INSTITUTION_MANAGER,9L),7L)).isInstanceOf(BusinessException.class);
        assertThat(service.get(new CurrentUser(99L,UserRole.ADMIN,null),7L)).isNotNull();
    }
    @Test void publishedSnapshotContractUsesAuthenticatedPrincipal() {
        assertThatThrownBy(() -> service.getSnapshot(7L)).isInstanceOf(BusinessException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,List.of()));
        assertThat(service.getSnapshot(7L).testTypeCode()).isEqualTo("VIBRATION");
    }
    @Test void replaceRemovesOmittedConditionsAndRejectsWholeInvalidBatchBeforeMutation() {
        service.saveConditions(owner,7L,List.of(c(ConditionStatus.CONFIRMED)));
        assertThatThrownBy(() -> service.saveConditions(owner,7L,List.of(c(ConditionStatus.CONFIRMED),c(ConditionStatus.MISSING))))
                .isInstanceOf(BusinessException.class);
        assertThat(requirement.getConditions()).hasSize(1);
        service.saveConditions(owner,7L,List.of());
        assertThat(service.analyze(owner,7L).readyForMatching()).isFalse();
    }
}
