package kr.astra.requirement.service;

import java.util.*;
import kr.astra.global.exception.*;
import kr.astra.global.security.*;
import kr.astra.requirement.dto.*;
import kr.astra.requirement.entity.*;
import kr.astra.requirement.repository.RequirementRepository;
import kr.astra.testtype.dto.ConditionRuleResponse;
import kr.astra.testtype.service.TestTypeService;
import kr.astra.user.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequirementApplicationService implements RequirementService {
    private final RequirementRepository repository;
    private final TestTypeService testTypes;
    private final ConditionValidator validator;

    @Transactional
    public RequirementResponse create(CurrentUser user, RequirementInput input) {
        AccessGuard.requireRole(user, UserRole.COMPANY_USER);
        return createForSession(user, user.userId(), null, input);
    }

    @Transactional
    public RequirementResponse createExtracted(CurrentUser user, RequirementInput input) {
        AccessGuard.requireRole(user, UserRole.COMPANY_USER);
        TestRequirement r = new TestRequirement(user.userId(), null, input);
        replace(r, input.conditions(), rules(input.testTypeCode()), ConditionSource.AI_EXTRACTED);
        return response(repository.saveAndFlush(r));
    }

    /** Called by the chat service after locking and authorizing the session. */
    @Transactional
    public RequirementResponse createForSession(CurrentUser actor, Long ownerId, Long sessionId, RequirementInput input) {
        AccessGuard.requireRole(actor, UserRole.COMPANY_USER);
        AccessGuard.requireOwner(actor, ownerId);
        List<ConditionRuleResponse> rules = rules(input.testTypeCode());
        TestRequirement requirement = new TestRequirement(ownerId, sessionId, input);
        replace(requirement, input.conditions() == null ? List.of() : input.conditions(), rules,
                sessionId == null ? ConditionSource.USER : ConditionSource.AI_EXTRACTED);
        return response(repository.saveAndFlush(requirement));
    }

    public RequirementResponse get(CurrentUser user, Long id) { return response(owned(user, id, false)); }

    public Optional<RequirementResponse> forSession(CurrentUser user, Long sessionId) {
        AccessGuard.requireRole(user, UserRole.COMPANY_USER);
        return repository.findFirstByChatSessionIdOrderByIdDesc(sessionId).map(r -> {
            AccessGuard.requireOwner(user, r.getUserId());
            return response(r);
        });
    }

    @Transactional
    public RequirementResponse update(CurrentUser user, Long id, String title, String purpose,
            String sampleName, Integer sampleQuantity) {
        TestRequirement r = owned(user, id, true);
        editable(r);
        r.update(title == null ? r.getTitle() : title, purpose == null ? r.getPurpose() : purpose,
                sampleName == null ? r.getSampleName() : sampleName,
                sampleQuantity == null ? r.getSampleQuantity() : sampleQuantity);
        repository.flush();
        return response(r);
    }

    @Transactional
    public RequirementResponse saveConditions(CurrentUser user, Long id, List<ConditionInput> inputs) {
        TestRequirement r = owned(user, id, true);
        editable(r);
        replace(r, inputs, rules(r.getTestTypeCode()), ConditionSource.USER);
        repository.flush();
        return response(r);
    }

    /** AI must not overwrite values explicitly entered by the user. */
    @Transactional
    public RequirementResponse mergeExtracted(CurrentUser user, Long id, List<ConditionInput> inputs) {
        TestRequirement r = owned(user, id, true);
        editable(r);
        List<ConditionRuleResponse> rules = rules(r.getTestTypeCode());
        List<ConditionInput> checked = validator.validate(inputs, rules);
        Map<String, RequirementCondition> existing = new HashMap<>();
        r.getConditions().forEach(c -> existing.put(c.getConditionKey(), c));
        for (ConditionInput c : checked) {
            RequirementCondition old = existing.get(c.key());
            if (old != null && old.getSource() == ConditionSource.USER) continue;
            if (old != null) old.update(c, ConditionSource.AI_EXTRACTED);
            else r.getConditions().add(new RequirementCondition(r, rule(rules, c.key()), c, ConditionSource.AI_EXTRACTED));
        }
        repository.flush();
        return response(r);
    }

    public AnalysisResponse analyze(CurrentUser user, Long id) {
        TestRequirement r = owned(user, id, false);
        return analyze(r);
    }

    private AnalysisResponse analyze(TestRequirement r) {
        Map<String, RequirementCondition> byKey = new HashMap<>();
        r.getConditions().forEach(c -> byKey.put(c.getConditionKey(), c));
        List<AnalysisResponse.Missing> missing = new ArrayList<>();
        List<AnalysisResponse.Unconfirmed> unconfirmed = new ArrayList<>();
        for (ConditionRuleResponse rule : rules(r.getTestTypeCode())) {
            RequirementCondition c = byKey.get(rule.conditionKey());
            if (rule.isRequired() && (c == null || c.getStatus() == ConditionStatus.MISSING))
                missing.add(new AnalysisResponse.Missing(rule.conditionKey(), rule.label(), rule.label() + "을(를) 알려 주세요."));
            if (c != null && c.getStatus() == ConditionStatus.UNCONFIRMED)
                unconfirmed.add(new AnalysisResponse.Unconfirmed(c.getConditionKey(), c.getLabel(),
                        c.getNote() == null ? "사용자 확인이 필요합니다." : c.getNote()));
        }
        return new AnalysisResponse(r.getId(), r.getTestTypeCode(), response(r).conditions(), missing,
                unconfirmed, missing.isEmpty() && unconfirmed.isEmpty());
    }

    @Transactional
    public RequirementResponse confirm(CurrentUser user, Long id) {
        TestRequirement r = owned(user, id, true);
        editable(r);
        if (!analyze(r).readyForMatching()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "필수 조건과 미확인 조건을 먼저 확인해 주세요.", null);
        r.confirm();
        repository.flush();
        return response(r);
    }

    @Override
    public RequirementSnapshot getSnapshot(Long id) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        CurrentUser user = authentication != null && authentication.getPrincipal() instanceof CurrentUser u ? u : null;
        return snapshot(user, id);
    }

    public RequirementSnapshot snapshot(CurrentUser user, Long id) {
        TestRequirement r = owned(user, id, false);
        return snapshot(r);
    }

    @Transactional
    public RequirementSnapshot lockSnapshot(CurrentUser user, Long id) {
        return snapshot(owned(user,id,true));
    }

    private RequirementSnapshot snapshot(TestRequirement r) {
        return new RequirementSnapshot(r.getId(), r.getTestTypeCode(), r.getTitle(), r.getPurpose(),
                r.getSampleName(), r.getSampleQuantity(), r.getConditions().stream().map(RequirementCondition::snapshot).toList());
    }

    public Long ownerId(CurrentUser user, Long id) { return owned(user, id, false).getUserId(); }

    public List<ConditionRuleResponse> rules(String code) {
        if (code == null || code.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        List<ConditionRuleResponse> rules = testTypes.getConditionRules(code);
        if (rules.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "조건 규칙이 없는 시험유형입니다.", null);
        return rules;
    }

    private TestRequirement owned(CurrentUser user, Long id, boolean lock) {
        AccessGuard.requireRole(user, UserRole.COMPANY_USER);
        TestRequirement r = (lock ? repository.lockById(id) : repository.findById(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        AccessGuard.requireOwner(user, r.getUserId());
        return r;
    }

    private void editable(TestRequirement r) {
        if (r.getStatus() != RequirementStatus.DRAFT) throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                "DRAFT 시험조건만 변경할 수 있습니다.", null);
    }

    private void replace(TestRequirement r, List<ConditionInput> inputs, List<ConditionRuleResponse> rules,
            ConditionSource source) {
        List<ConditionInput> checked = validator.validate(inputs, rules);
        Map<String, RequirementCondition> existing = new HashMap<>();
        r.getConditions().forEach(c -> existing.put(c.getConditionKey(), c));
        Set<String> keys = new HashSet<>();
        for (ConditionInput c : checked) {
            keys.add(c.key());
            if (existing.containsKey(c.key())) existing.get(c.key()).update(c, source);
            else r.getConditions().add(new RequirementCondition(r, rule(rules, c.key()), c, source));
        }
        r.getConditions().removeIf(c -> !keys.contains(c.getConditionKey()));
    }

    private ConditionRuleResponse rule(List<ConditionRuleResponse> rules, String key) {
        return rules.stream().filter(r -> r.conditionKey().equals(key)).findFirst().orElseThrow();
    }

    private RequirementResponse response(TestRequirement r) {
        return new RequirementResponse(r.getId(), r.getChatSessionId(), r.getTestTypeCode(), r.getTitle(),
                r.getPurpose(), r.getSampleName(), r.getSampleQuantity(), r.getStatus(),
                r.getConditions().stream().map(RequirementCondition::response).toList(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
