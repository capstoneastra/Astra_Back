package kr.astra.matching.service;

import java.util.*;
import kr.astra.equipment.dto.EquipmentCandidate;
import kr.astra.equipment.service.*;
import kr.astra.global.exception.*;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.matching.dto.MatchResponse;
import kr.astra.matching.entity.*;
import kr.astra.matching.repository.*;
import kr.astra.requirement.dto.RequirementSnapshot;
import kr.astra.requirement.service.RequirementApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j @Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class MatchingService {
    public static final String DISCLAIMER = "최종 시험 가능 여부는 기관 검토로 확정됩니다.";
    private final RequirementApplicationService requirements;
    private final EquipmentQueryService equipmentQuery;
    private final EquipmentService equipment;
    private final RuleMatcher matcher;
    private final MatchRunRepository runs;
    private final MatchResultRepository results;
    private final kr.astra.llm.service.MatchExplanationService explanations;
    private record Candidate(EquipmentCandidate equipment, RuleMatcher.Evaluation evaluation) {}

    @Transactional
    public MatchResponse run(CurrentUser user, Long requirementId, String region, int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        RequirementSnapshot snapshot = requirements.snapshot(user, requirementId);
        var rules = requirements.rules(snapshot.testTypeCode());
        List<Candidate> candidates = equipmentQuery.findCandidates(snapshot.testTypeCode(), region).stream()
                // 자기 소속 조직 장비에는 의뢰할 수 없으므로 추천에서 뺌
                .filter(e -> user.isAdmin() || !java.util.Objects.equals(e.institutionId(), user.organizationId()))
                .map(e -> new Candidate(e, matcher.evaluate(rules, snapshot.conditions(), equipmentQuery.getSpecs(e.id()),
                        equipment.accredited(e.id(), snapshot.testTypeCode()))))
                .sorted(Comparator.comparingInt((Candidate c) -> verdictOrder(c.evaluation.verdict()))
                        .thenComparing(c -> c.evaluation.score(), Comparator.reverseOrder())
                        .thenComparing(c -> c.equipment.id()))
                .limit(limit).toList();
        MatchRun run = new MatchRun(requirementId, user.userId(), snapshot);
        Map<Long,String> descriptions = explain(candidates);
        int rank = 1;
        for (Candidate c : candidates) {
            var e = c.evaluation;
            String description = descriptions.get(c.equipment.id());
            String reason = e.checks().stream().map(check -> check.conditionKey() + ": " + check.result())
                    .collect(java.util.stream.Collectors.joining(", ")) + ". "
                    + (description == null ? "" : description.replace(DISCLAIMER, "").strip() + " ") + DISCLAIMER;
            if (c.equipment.availabilityNeedsConfirmation()) reason += " 가용 상태는 기관 확인이 필요합니다.";
            run.getResults().add(new MatchResult(run, c.equipment.id(), rank++, e.score(), e.verdict(),
                    e.usedMockData() || c.equipment.isMock(), reason, e.checks()));
        }
        runs.saveAndFlush(run);
        Map<Long, EquipmentCandidate> summaries = new HashMap<>();
        candidates.forEach(c -> summaries.put(c.equipment.id(), c.equipment));
        return response(run, summaries);
    }

    public MatchResponse get(CurrentUser user, Long id) {
        MatchRun run = runs.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requirements.get(user, run.getRequirementId());
        return response(run, summaries(run));
    }

    public PageResponse<MatchResponse> history(CurrentUser user, Long requirementId, Pageable pageable) {
        requirements.get(user, requirementId);
        return PageResponse.from(runs.findByRequirementId(requirementId,
                PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt", "id"))), r -> response(r, summaries(r)));
    }

    public MatchResponse.Result validateSelection(CurrentUser user, Long requirementId, Long equipmentId, Long resultId) {
        requirements.get(user, requirementId);
        MatchResult r = results.findById(resultId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Check access to both resources before checking their relationship.
        requirements.get(user, r.getRun().getRequirementId());
        if (!r.getRun().getRequirementId().equals(requirementId) || !r.getEquipmentId().equals(equipmentId))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "조건·장비·매칭 결과의 연결이 일치하지 않습니다.", null);
        if (!kr.astra.requirement.service.RequirementSnapshots.same(r.getRun().getRequirementSnapshot(),requirements.snapshot(user,requirementId)))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,"매칭 후 시험조건이 변경되었습니다. 매칭을 다시 실행해 주세요.",null);
        return result(r, summary(r.getEquipmentId()));
    }

    /**
     * AI 설명은 이미 확정된 규칙 판정을 풀어 쓰는 부가 정보라, 실패하면 설명 없이 규칙 결과만 응답합니다
     * (Gemini 과부하·한도 초과로 매칭 전체가 502 가 되지 않게).
     */
    private Map<Long,String> explain(List<Candidate> candidates) {
        try {
            return explanations.explain(candidates.stream().map(c -> new kr.astra.llm.service.MatchExplanationService.Input(
                    c.equipment.id(),c.evaluation.verdict(),c.evaluation.checks())).toList());
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.AI_UPSTREAM_ERROR) throw e;
            log.warn("매칭 AI 설명 실패, 규칙 판정만 응답합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private int verdictOrder(MatchVerdict v) {
        return switch (v) { case SUITABLE -> 0; case PARTIAL -> 1; case UNKNOWN -> 2; case UNSUITABLE -> 3; };
    }
    private Map<Long, EquipmentCandidate> summaries(MatchRun r) {
        Map<Long, EquipmentCandidate> map = new HashMap<>();
        r.getResults().forEach(result -> map.put(result.getEquipmentId(), summary(result.getEquipmentId())));
        return map;
    }
    private EquipmentCandidate summary(Long id) {
        try {
            var e = equipment.getDetail(id);
            return new EquipmentCandidate(e.id(), e.externalId(), e.name(), e.institution().id(),
                    e.institution().name(), e.region(), e.isMock(), e.availability(), e.usage().availabilityNeedsConfirmation());
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EQUIPMENT_NOT_FOUND) throw e;
            // Historical checks remain available when BE-A no longer exposes an inactive equipment.
            return new EquipmentCandidate(id, null, null, null, null, null, false);
        }
    }
    private MatchResponse response(MatchRun r, Map<Long, EquipmentCandidate> equipment) {
        return new MatchResponse(r.getId(), r.getRequirementId(), r.getAlgorithmVersion(), r.getCreatedAt(),
                r.getResults().stream().map(result -> result(result, equipment.get(result.getEquipmentId()))).toList(), DISCLAIMER);
    }
    private MatchResponse.Result result(MatchResult r, EquipmentCandidate equipment) {
        return new MatchResponse.Result(r.getId(), r.getRank(), r.getScore(), r.getVerdict(), r.isUsedMockData(),
                equipment, r.getReason(), r.getChecks().stream().map(MatchConditionCheck::response).toList());
    }
}
