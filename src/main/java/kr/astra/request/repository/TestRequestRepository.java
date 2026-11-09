package kr.astra.request.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.TestRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestRequestRepository extends JpaRepository<TestRequest, Long> {

    /** 상태를 바꾸기 전에 행을 잠급니다 (같은 의뢰에 동시에 버튼을 눌러도 하나씩 처리) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TestRequest r WHERE r.id = :id")
    Optional<TestRequest> findByIdForUpdate(@Param("id") Long id);

    // 기업: 내 의뢰
    Page<TestRequest> findAllByRequesterId(Long requesterId, Pageable pageable);

    Page<TestRequest> findAllByRequesterIdAndStatus(Long requesterId, RequestStatus status, Pageable pageable);

    // 기관: 우리 기관 접수함 (한 번도 제출 안 한 작성 중 의뢰는 보이지 않음)
    Page<TestRequest> findAllByInstitutionIdAndSubmittedAtIsNotNull(Long institutionId, Pageable pageable);

    Page<TestRequest> findAllByInstitutionIdAndStatusAndSubmittedAtIsNotNull(Long institutionId, RequestStatus status,
                                                                             Pageable pageable);

    // 관리자: 전체
    Page<TestRequest> findAllByStatus(RequestStatus status, Pageable pageable);

    // 대시보드 (RequestDashboardQuery)
    List<TestRequest> findAllByRequesterIdAndStatusIn(Long requesterId, Collection<RequestStatus> statuses,
                                                      Pageable pageable);

    List<TestRequest> findAllByInstitutionIdAndStatusIn(Long institutionId, Collection<RequestStatus> statuses,
                                                        Pageable pageable);

    @Query("SELECT r.status, COUNT(r) FROM TestRequest r WHERE r.requesterId = :id GROUP BY r.status")
    List<Object[]> countStatusByRequester(@Param("id") Long requesterId);

    @Query("""
            SELECT r.status, COUNT(r) FROM TestRequest r
            WHERE r.institutionId = :id AND r.submittedAt IS NOT NULL
            GROUP BY r.status
            """)
    List<Object[]> countStatusByInstitution(@Param("id") Long institutionId);

    @Query("SELECT r.status, COUNT(r) FROM TestRequest r GROUP BY r.status")
    List<Object[]> countStatusAll();
}
