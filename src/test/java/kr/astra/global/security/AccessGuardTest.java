package kr.astra.global.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.user.entity.UserRole;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** 권한 규칙 (api-spec §0-4): ADMIN 마스터, 기관 담당자는 소속 기관만, 기업은 본인 데이터만 */
class AccessGuardTest {

    private static final long INSTITUTION_A = 2L;
    private static final long INSTITUTION_B = 6L;

    private final CurrentUser admin = new CurrentUser(1L, UserRole.ADMIN, 100L);
    private final CurrentUser managerA = new CurrentUser(2L, UserRole.INSTITUTION_MANAGER, INSTITUTION_A);
    private final CurrentUser company = new CurrentUser(3L, UserRole.COMPANY_USER, 200L);

    @Test
    void 로그인하지_않으면_401() {
        assertCode(() -> AccessGuard.requireLogin(null), ErrorCode.AUTH_REQUIRED);
        assertCode(() -> AccessGuard.requireInstitution(null, INSTITUTION_A), ErrorCode.AUTH_REQUIRED);
    }

    @Test
    void 관리자는_모든_검사를_통과한다() {
        assertThatCode(() -> {
            AccessGuard.requireAdmin(admin);
            AccessGuard.requireRole(admin, UserRole.COMPANY_USER);
            AccessGuard.requireInstitution(admin, INSTITUTION_B);
            AccessGuard.requireOwner(admin, company.userId());
            AccessGuard.requireOwnerOrInstitution(admin, company.userId(), INSTITUTION_B);
        }).doesNotThrowAnyException();
    }

    @Test
    void 기관_담당자는_소속_기관만_통과한다() {
        assertThatCode(() -> AccessGuard.requireInstitution(managerA, INSTITUTION_A)).doesNotThrowAnyException();
        assertCode(() -> AccessGuard.requireInstitution(managerA, INSTITUTION_B), ErrorCode.FORBIDDEN);
        assertCode(() -> AccessGuard.requireAdmin(managerA), ErrorCode.FORBIDDEN);
    }

    @Test
    void 기업은_소속_조직_id_가_같아도_기관_권한이_없다() {
        CurrentUser companyWithSameOrgId = new CurrentUser(4L, UserRole.COMPANY_USER, INSTITUTION_A);
        assertCode(() -> AccessGuard.requireInstitution(companyWithSameOrgId, INSTITUTION_A), ErrorCode.FORBIDDEN);
    }

    @Test
    void 기업은_본인_데이터만_통과한다() {
        assertThatCode(() -> AccessGuard.requireOwner(company, company.userId())).doesNotThrowAnyException();
        assertCode(() -> AccessGuard.requireOwner(company, 99L), ErrorCode.FORBIDDEN);
        assertCode(() -> AccessGuard.requireRole(company, UserRole.INSTITUTION_MANAGER), ErrorCode.FORBIDDEN);
    }

    @Test
    void 의뢰처럼_양쪽이_보는_데이터는_의뢰자_또는_해당_기관_담당자만() {
        long requesterId = company.userId();
        assertThatCode(() -> {
            AccessGuard.requireOwnerOrInstitution(company, requesterId, INSTITUTION_A);
            AccessGuard.requireOwnerOrInstitution(managerA, requesterId, INSTITUTION_A);
        }).doesNotThrowAnyException();
        assertCode(() -> AccessGuard.requireOwnerOrInstitution(managerA, requesterId, INSTITUTION_B),
                ErrorCode.FORBIDDEN);
        CurrentUser otherCompany = new CurrentUser(5L, UserRole.COMPANY_USER, 201L);
        assertCode(() -> AccessGuard.requireOwnerOrInstitution(otherCompany, requesterId, INSTITUTION_A),
                ErrorCode.FORBIDDEN);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
