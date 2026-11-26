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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

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
    private final TransactionOperations tx;
    private record Candidate(EquipmentCandidate equipment, RuleMatcher.Evaluation evaluation) {}

    /**
     * 조회·판정·AI 설명은 트랜잭션 없이 하고(각 조회는 자기 읽기 트랜잭션), 저장만 짧은 트랜잭션으로 묶습니다.
     * AI 설명(최대 45초) 동안 DB 커넥션을 잡지 않고, 자동 추진의 실행 행 잠금 안에서도 부르지 않습니다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MatchResponse run(CurrentUser user, Long requirementId, String region, int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        RequirementSnapshot snapshot = requirements.snapshot(user, requirementId);
        var rules = requirements.rules(snapshot.testTypeCode());
        Long excluded = excludedInstitution(user);
        List<EquipmentCandidate> found = equipmentQuery.findCandidates(snapshot.testTypeCode(), region).stream()
                // 자기 소속 조직 장비에는 의뢰할 수 없으므로 추천에서 뺌
                .filter(e -> excluded == null || !excluded.equals(e.institutionId())).toList();
        List<Long> ids = found.stream().map(EquipmentCandidate::id).toList();
        // 후보 수와 무관하게 사양·공인 여부는 쿼리 한 번씩
        Map<Long, List<kr.astra.equipment.dto.EquipmentSpecView>> specs = ids.isEmpty() ? Map.of() : equipmentQuery.getSpecs(ids);
        Map<Long, Boolean> accredited = ids.isEmpty() ? Map.of() : equipment.accredited(ids, snapshot.testTypeCode());
        List<Candidate> candidates = found.stream()
                .map(e -> new Candidate(e, matcher.evaluate(rules, snapshot.conditions(),
                        specs.getOrDefault(e.id(), List.of()), accredited.get(e.id()))))
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
            String reason = e.checks().stream().map(check -> check.label() + ": " + check.resultText())
                    .collect(java.util.stream.Collectors.joining(", ")) + ". "
                    + (description == null ? "" : description.replace(DISCLAIMER, "").strip() + " ") + DISCLAIMER;
            if (c.equipment.availabilityNeedsConfirmation()) reason += " 가용 상태는 기관 확인이 필요합니다.";
            run.getResults().add(new MatchResult(run, c.equipment.id(), rank++, e.score(), e.verdict(),
                    e.usedMockData() || c.equipment.isMock(), reason, e.checks()));
        }
        tx.execute(status -> runs.saveAndFlush(run));
        Map<Long, EquipmentCandidate> summaries = new HashMap<>();
        candidates.forEach(c -> summaries.put(c.equipment.id(), c.equipment));
        return response(run, summaries);
    }

    /** 매칭과 같은 기준(활성·가용·시험유형·자기 조직 제외)의 후보 수. 자동 추진 SEARCH 단계용 */
    public long countCandidates(CurrentUser user, String testTypeCode, String region) {
        return equipmentQuery.countCandidates(testTypeCode, region, excludedInstitution(user));
    }

    /** 관리자가 아니면 소속 조직 장비를 후보에서 뺌 (소속이 없으면 뺄 것 없음) */
    private static Long excludedInstitution(CurrentUser user) {
        return user.isAdmin() ? null : user.organizationId();
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
        return result(r, summaries(List.of(r.getEquipmentId())).get(r.getEquipmentId()));
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
            if (!kr.astra.llm.service.AiFallback.recoverable(e)) throw e;
            log.warn("매칭 AI 설명 실패, 규칙 판정만 응답합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private int verdictOrder(MatchVerdict v) {
        return switch (v) { case SUITABLE -> 0; case PARTIAL -> 1; case UNKNOWN -> 2; case UNSUITABLE -> 3; };
    }
    private Map<Long, EquipmentCandidate> summaries(MatchRun r) {
        return summaries(r.getResults().stream().map(MatchResult::getEquipmentId).toList());
    }
    /** 결과 수와 무관하게 한 번에 조회. 비활성·삭제된 장비도 이력의 판정 근거는 볼 수 있게 id 만 남김 */
    private Map<Long, EquipmentCandidate> summaries(List<Long> ids) {
        Map<Long, EquipmentCandidate> found = ids.isEmpty() ? Map.of() : equipmentQuery.findSummaries(new LinkedHashSet<>(ids));
        Map<Long, EquipmentCandidate> map = new HashMap<>();
        ids.forEach(id -> map.put(id, found.getOrDefault(id, new EquipmentCandidate(id, null, null, null, null, null, false))));
        return map;
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
