package kr.astra.request.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.organization.dto.OrganizationSummaryResponse;
import kr.astra.organization.service.OrganizationService;
import kr.astra.request.dto.RequestResponses.EquipmentRef;
import kr.astra.request.dto.RequestResponses.OrgRef;
import kr.astra.request.dto.RequestResponses.PersonRef;
import kr.astra.request.dto.RequestResponses.Revision;
import kr.astra.request.dto.RequestResponses.Summary;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.user.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entity → 응답 DTO. 장비·기관·사람 이름은 다른 도메인 서비스로 한 번에 모아서 조회합니다 (목록에서 행마다 조회하지 않음).
 */
@Component
public class RequestViews {

    private final EquipmentService equipmentService;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final RequestContent content;
    /**
     * 장비 조회 전용 별도 트랜잭션. EquipmentService 는 @Transactional 이라 비활성 장비에서 404 를 던지면
     * 참여 중인 트랜잭션을 rollback-only 로 만들어 버리므로, 조회를 따로 떼어 실패가 의뢰 조회에 번지지 않게 합니다.
     */
    private final TransactionTemplate equipmentLookupTx;

    public RequestViews(EquipmentService equipmentService, OrganizationService organizationService,
                        UserService userService, RequestContent content, PlatformTransactionManager txManager) {
        this.equipmentService = equipmentService;
        this.organizationService = organizationService;
        this.userService = userService;
        this.content = content;
        this.equipmentLookupTx = new TransactionTemplate(txManager);
        this.equipmentLookupTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.equipmentLookupTx.setReadOnly(true);
    }

    /** 한 번의 응답을 만드는 동안 쓰는 이름 캐시 */
    public final class Names {
        private final Map<Long, EquipmentRef> equipment = new HashMap<>();
        private final Map<Long, OrganizationSummaryResponse> orgs;
        private final Map<Long, String> people;

        private Names(Collection<Long> orgIds, Collection<Long> userIds) {
            this.orgs = orgIds.isEmpty() ? Map.of() : organizationService.findSummaries(orgIds);
            this.people = userIds.isEmpty() ? Map.of() : userService.findNames(userIds);
        }

        public EquipmentRef equipment(Long id) {
            return equipment.computeIfAbsent(id, RequestViews.this::loadEquipment);
        }

        public OrgRef org(Long id) {
            if (id == null) {
                return null;
            }
            OrganizationSummaryResponse o = orgs.get(id);
            return new OrgRef(id, o == null ? null : o.name());
        }

        public String orgName(Long id) {
            OrganizationSummaryResponse o = id == null ? null : orgs.get(id);
            return o == null ? null : o.name();
        }

        public PersonRef person(Long id) {
            return id == null ? null : new PersonRef(id, people.get(id));
        }
    }

    /** 의뢰 목록에 나오는 기관·회사·사람 이름을 한 번에 조회 */
    public Names names(Collection<TestRequest> requests, Collection<Long> extraUserIds) {
        Set<Long> orgIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>(extraUserIds);
        for (TestRequest r : requests) {
            orgIds.add(r.getInstitutionId());
            if (r.getCompanyOrgId() != null) {
                orgIds.add(r.getCompanyOrgId());
            }
            userIds.add(r.getRequesterId());
            if (r.getAssigneeId() != null) {
                userIds.add(r.getAssigneeId());
            }
        }
        return new Names(orgIds, userIds);
    }

    public Names names(TestRequest request, Collection<Long> extraUserIds) {
        return names(List.of(request), extraUserIds);
    }

    /** 사람 이름만 필요할 때 (이력·제출본 목록) */
    public Names people(Collection<Long> userIds) {
        return new Names(List.of(), userIds.stream().filter(Objects::nonNull).toList());
    }

    private EquipmentRef loadEquipment(Long id) {
        try {
            EquipmentDetailResponse e = equipmentLookupTx.execute(status -> equipmentService.getDetail(id));
            return new EquipmentRef(e.id(), e.externalId(), e.name(), e.isMock());
        } catch (BusinessException notActive) {
            // 의뢰 뒤에 기관이 장비를 비활성화한 경우. 의뢰 이력은 그대로 보여 줌
            return new EquipmentRef(id, null, "(비활성 장비)", false);
        }
    }

    public Summary summary(TestRequest r, Names n) {
        return new Summary(r.getId(), r.getRequestNo(), r.getTitle(), r.getStatus(), r.getVersion(),
                r.getCurrentRevisionNo(), n.equipment(r.getEquipmentId()), n.org(r.getInstitutionId()),
                n.person(r.getRequesterId()), n.orgName(r.getCompanyOrgId()), r.getDesiredStartDate(),
                r.getDesiredEndDate(), r.getSubmittedAt(), r.getCreatedAt(), r.getUpdatedAt());
    }

    public Revision revision(TestRequestRevision rev, Names n, boolean withContent) {
        if (rev == null) {
            return null;
        }
        return new Revision(rev.getRevisionNo(), rev.isAiGenerated(), rev.isSubmitted(), rev.getChangeSummary(),
                n.person(rev.getAuthorId()), rev.getCreatedAt(), rev.getSubmittedAt(),
                withContent ? content.read(rev.getContent()) : null);
    }

    public static Set<Long> authorIds(Stream<TestRequestRevision> revisions) {
        Set<Long> ids = new HashSet<>();
        revisions.filter(Objects::nonNull).forEach(r -> ids.add(r.getAuthorId()));
        return ids;
    }
}
