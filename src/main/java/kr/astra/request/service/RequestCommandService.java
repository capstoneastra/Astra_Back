package kr.astra.request.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.AccessGuard;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.service.MatchingService;
import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.dto.RequestInputs;
import kr.astra.request.dto.RequestResponses.ActionResult;
import kr.astra.request.dto.RequestResponses.DraftSaved;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.requirement.dto.RequirementSnapshot;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.dto.UserMeResponse;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 의뢰자 쪽 의뢰 작성·제출·취소 (api-spec §5). 기업·기관·대학 회원 모두 의뢰자가 될 수 있습니다 */
@Service
@RequiredArgsConstructor
@Transactional
public class RequestCommandService {

    private final TestRequestServiceImpl testRequestService;
    private final RequestWorkflow workflow;
    private final RequestContent content;
    private final EquipmentService equipmentService;
    private final UserService userService;
    private final RequirementApplicationService requirementService;
    private final MatchingService matchingService;
    private final kr.astra.request.repository.TestResultRepository resultRepository;
    private final kr.astra.global.config.RulesProperties rules;

    /**
     * POST /test-requests. 시험조건(requirementId)이 있으면 조건을 의뢰서 content.conditions 에 옮겨 담습니다.
     * matchResultId 는 그 시험조건의 매칭 결과 중 이 장비 행이어야 합니다 (BE-C 검증 재사용).
     */
    public Long create(CurrentUser actor, RequestInputs.Create input) {
        AccessGuard.requireLogin(actor);
        if (input.matchResultId() != null && input.requirementId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "matchResultId 를 보내려면 requirementId 도 필요합니다.",
                    Map.of("field", "requirementId"));
        }
        EquipmentDetailResponse equipment = equipmentService.getDetail(input.equipmentId());
        RequirementSnapshot requirement = null;
        if (input.requirementId() != null) {
            requirement = requirementService.snapshot(actor, input.requirementId()); // 본인 시험조건인지 확인
            if (input.matchResultId() != null) {
                matchingService.validateSelection(actor, input.requirementId(), input.equipmentId(),
                        input.matchResultId());
            }
        }
        UserMeResponse me = userService.getMe(actor.userId());
        String title = firstNonBlank(input.title(), requirement == null ? null : requirement.title(),
                equipment.name() + " 시험 의뢰");
        return testRequestService.createDraft(new CreateDraftCommand(actor.userId(), equipment.id(),
                input.requirementId(), input.matchResultId(), title, initialContent(me, equipment, requirement),
                false), equipment);
    }

    /** PUT /test-requests/{id}/draft — 작업본 저장. 제출은 하지 않습니다. */
    public DraftSaved saveDraft(CurrentUser actor, Long requestId, RequestInputs.SaveDraft input) {
        TestRequest request = workflow.lock(requestId);
        AccessGuard.requireOwner(actor, request.getRequesterId());
        RequestWorkflow.checkVersion(request, input.version());
        content.validate(input.content());

        TestRequestRevision working = workflow.editableRevision(request, actor.userId());
        working.updateContent(content.write(input.content()), input.changeSummary(), actor.userId());
        workflow.saveRevision(working);
        if (request.getStatus() == RequestStatus.DRAFT) {
            // 접수 전에는 제목·희망 일정을 바로 반영. 보완 중에는 보완 제출할 때 반영
            RequestContent.Schedule s = content.schedule(input.content());
            request.updateDraftInfo(input.title(), s.start(), s.end());
        }
        workflow.bumpVersion(request);
        return new DraftSaved(request.getId(), request.getStatus(), request.getVersion(), working.getRevisionNo(),
                content.read(working.getContent()));
    }

    /** POST /test-requests/{id}/submit — DRAFT → SUBMITTED, 1번 제출본 고정, 장비 보유 조직 구성원 전원에게 알림 */
    public ActionResult submit(CurrentUser actor, Long requestId, Integer version) {
        TestRequest request = workflow.begin(requestId, RequestAction.SUBMIT, actor, version);
        TestRequestRevision revision = workflow.currentRevision(request);
        Map<String, Object> body = content.read(revision.getContent());
        content.validate(body);
        if (rules.strict()) {
            content.requireForSubmit(body);
        }
        RequestContent.Schedule s = content.schedule(body);
        request.updateDraftInfo(null, s.start(), s.end());
        revision.submit(OffsetDateTime.now());
        workflow.saveRevision(revision);
        workflow.transition(request, RequestAction.SUBMIT, actor, null);
        return result(request);
    }

    /** 예상 이용료를 적을 수 있는 상태: 검토 중 ~ 일정 조율 중 (회의 결정: 검토 중에 기관이 입력) */
    private static final java.util.Set<RequestStatus> FEE_EDITABLE = java.util.EnumSet.of(RequestStatus.UNDER_REVIEW,
            RequestStatus.SUPPLEMENT_REQUESTED, RequestStatus.RESUBMITTED, RequestStatus.SCHEDULING);

    /** PUT /test-requests/{id}/estimated-fee — 장비 보유 조직이 예상 이용료(원)와 메모를 적음 */
    public void updateEstimatedFee(CurrentUser actor, Long requestId, RequestInputs.EstimatedFee input) {
        TestRequest request = workflow.lock(requestId);
        workflow.authorize(RequestAction.Party.INSTITUTION, actor, request);
        if (!FEE_EDITABLE.contains(request.getStatus())) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                    "예상 이용료는 검토 중부터 일정 조율 중까지 적을 수 있습니다.",
                    Map.of("currentStatus", request.getStatus()));
        }
        RequestWorkflow.checkVersion(request, input.version());
        request.updateEstimatedFee(input.amount(), input.note(), OffsetDateTime.now());
        workflow.bumpVersion(request);
    }

    /**
     * PUT /test-requests/{id}/result — 장비 보유 조직이 시험 결과·성적서를 등록(다시 보내면 고침).
     * 일정 확정(CONFIRMED) 뒤에만 가능하고, 의뢰자에게 RESULT_REGISTERED 알림. 결과가 있어야 완료할 수 있음
     */
    public void registerResult(CurrentUser actor, Long requestId, RequestInputs.Result input) {
        TestRequest request = workflow.lock(requestId);
        workflow.authorize(RequestAction.Party.INSTITUTION, actor, request);
        if (request.getStatus() != RequestStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS, "시험 일정이 확정된 의뢰에만 결과를 등록할 수 있습니다.",
                    Map.of("currentStatus", request.getStatus()));
        }
        OffsetDateTime now = OffsetDateTime.now();
        kr.astra.request.entity.TestResult result = resultRepository.findByRequestId(requestId)
                .orElseGet(() -> new kr.astra.request.entity.TestResult(requestId, actor.userId(), now));
        boolean first = result.getId() == null;
        result.update(input.summary().strip(), blankToNull(input.reportNo()), blankToNull(input.reportUrl()),
                actor.userId(), now);
        resultRepository.saveAndFlush(result);
        workflow.notifyRequester(request, kr.astra.notification.entity.NotificationType.RESULT_REGISTERED,
                "시험 결과가 " + (first ? "등록" : "수정") + "되었습니다.");
        workflow.bumpVersion(request);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** POST /test-requests/{id}/cancel — DRAFT~SCHEDULING → CANCELED. 열린 보완 요청·제안 중 예약은 함께 닫힘 */
    public ActionResult cancel(CurrentUser actor, Long requestId, RequestInputs.Cancel input) {
        TestRequest request = workflow.begin(requestId, RequestAction.CANCEL, actor, input == null ? null : input.version());
        workflow.transition(request, RequestAction.CANCEL, actor, input == null ? null : input.reason());
        return result(request);
    }

    private static ActionResult result(TestRequest request) {
        return RequestWorkflow.result(request);
    }

    // ------------------------------------------------------------ 의뢰서 본문 초기값 (api-spec §5 스키마)

    private static Map<String, Object> initialContent(UserMeResponse me, EquipmentDetailResponse equipment,
                                                      RequirementSnapshot requirement) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("applicant", fields("companyName", me.organization() == null ? null : me.organization().name(),
                "contactName", me.name(), "phone", me.phone(), "email", me.email()));
        // 실사용자는 보통 신청자 본인이라 미리 채움(수정 가능). 회계 담당자는 제출 전에 직접 입력
        c.put("actualUser", fields("name", me.name(), "phone", me.phone(), "email", me.email()));
        c.put("accounting", new LinkedHashMap<>());
        c.put("test", requirement == null ? new LinkedHashMap<>()
                : fields("testTypeCode", requirement.testTypeCode(), "purpose", requirement.purpose()));
        c.put("sample", requirement == null ? new LinkedHashMap<>()
                : fields("name", requirement.sampleName(), "quantity", requirement.sampleQuantity()));
        c.put("conditions", requirement == null ? new ArrayList<>() : conditions(requirement.conditions()));
        c.put("schedule", new LinkedHashMap<>());
        c.put("equipment", fields("id", equipment.id(), "externalId", equipment.externalId(), "name",
                equipment.name()));
        c.put("notes", "");
        return c;
    }

    private static List<Map<String, Object>> conditions(List<RequirementSnapshot.Condition> conditions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RequirementSnapshot.Condition c : conditions) {
            out.add(fields("key", c.key(), "label", c.label(), "value", plain(c.value()), "min", plain(c.min()),
                    "max", plain(c.max()), "valueText", c.valueText(), "unit", c.unit(),
                    "status", c.status() == null ? null : c.status().name()));
        }
        return out;
    }

    /** 15.000 → 15 처럼 JSON 에 깔끔하게 (100 이 1E+2 가 되지 않게 scale 0 이상 유지) */
    private static BigDecimal plain(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                m.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return m;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return TestRequestServiceImpl.DEFAULT_TITLE;
    }
}
