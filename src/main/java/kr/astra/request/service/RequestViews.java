package kr.astra.request.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import kr.astra.equipment.entity.Equipment;
import kr.astra.equipment.repository.EquipmentRepository;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Entity → 응답 DTO. 장비·기관·사람 이름은 한 번에 모아서 조회합니다 (목록에서 행마다 조회하지 않음).
 * 장비는 표시용 4개 값만 필요하므로 EquipmentRepository 로 같은 트랜잭션에서 한 번에 읽습니다
 * (비활성 장비도 포함해서 읽고 "(비활성 장비)" 로 표시 — 예외를 던지지 않으므로 트랜잭션을 따로 열 필요 없음).
 */
@Component
@RequiredArgsConstructor
public class RequestViews {

    static final String INACTIVE_EQUIPMENT = "(비활성 장비)";

    private final EquipmentRepository equipmentRepository;
    private final OrganizationService organizationService;
    private final UserService userService;
    private final RequestContent content;

    /** 한 번의 응답을 만드는 동안 쓰는 이름 캐시 */
    public final class Names {
        private final Map<Long, EquipmentRef> equipment;
        private final Map<Long, OrganizationSummaryResponse> orgs;
        private final Map<Long, String> people;

        private Names(Collection<Long> equipmentIds, Collection<Long> orgIds, Collection<Long> userIds) {
            this.equipment = loadEquipment(equipmentIds);
            this.orgs = orgIds.isEmpty() ? Map.of() : organizationService.findSummaries(orgIds);
            this.people = userIds.isEmpty() ? Map.of() : userService.findNames(userIds);
        }

        /** 미리 모으지 않은 장비는 그때 한 건 조회 */
        public EquipmentRef equipment(Long id) {
            EquipmentRef ref = equipment.get(id);
            if (ref == null) {
                ref = loadEquipment(List.of(id)).get(id);
                equipment.put(id, ref);
            }
            return ref;
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

    /** 의뢰 목록에 나오는 장비·기관·회사·사람 이름을 한 번에 조회 */
    public Names names(Collection<TestRequest> requests, Collection<Long> extraUserIds) {
        Set<Long> equipmentIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>(extraUserIds);
        for (TestRequest r : requests) {
            equipmentIds.add(r.getEquipmentId());
            orgIds.add(r.getInstitutionId());
            if (r.getCompanyOrgId() != null) {
                orgIds.add(r.getCompanyOrgId());
            }
            userIds.add(r.getRequesterId());
            if (r.getAssigneeId() != null) {
                userIds.add(r.getAssigneeId());
            }
        }
        return new Names(equipmentIds, orgIds, userIds);
    }

    public Names names(TestRequest request, Collection<Long> extraUserIds) {
        return names(List.of(request), extraUserIds);
    }

    /** 사람 이름만 필요할 때 (이력·제출본 목록) */
    public Names people(Collection<Long> userIds) {
        return new Names(List.of(), List.of(), userIds.stream().filter(Objects::nonNull).toList());
    }

    /** 장비 이름만 필요할 때 (대시보드 다가오는 예약) */
    public Names equipmentNames(Collection<Long> equipmentIds) {
        return new Names(equipmentIds.stream().filter(Objects::nonNull).toList(), List.of(), List.of());
    }

    /** 장비 id → 표시용 참조. 없거나 비활성(의뢰 뒤에 비활성화)인 장비는 "(비활성 장비)" 로 이력은 그대로 보여 줌 */
    private Map<Long, EquipmentRef> loadEquipment(Collection<Long> ids) {
        Map<Long, EquipmentRef> refs = new HashMap<>();
        if (ids.isEmpty()) {
            return refs;
        }
        for (Equipment e : equipmentRepository.findAllById(Set.copyOf(ids))) {
            if (e.isActive()) {
                refs.put(e.getId(), new EquipmentRef(e.getId(), e.getExternalId(), e.getName(), e.isMock()));
            }
        }
        for (Long id : ids) {
            refs.putIfAbsent(id, new EquipmentRef(id, null, INACTIVE_EQUIPMENT, false));
        }
        return refs;
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
