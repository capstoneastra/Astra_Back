package kr.astra.llm.service;

import java.util.*;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.LlmClient;
import kr.astra.llm.dto.*;
import kr.astra.llm.prompt.PromptLoader;
import kr.astra.matching.service.MatchingService;
import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.service.TestRequestService;
import kr.astra.requirement.service.RequirementApplicationService;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AiDraftService {
    public static final String RECEIPT_PENDING = "BE-B 초안 저장 결과 조회 계약이 없어 DRAFT 및 AI revision을 확인할 수 없습니다.";
    private final RequirementApplicationService requirements;
    private final MatchingService matching;
    private final EquipmentService equipment;
    private final UserService users;
    private final ObjectProvider<TestRequestService> requests;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StructuredJson json;
    public record Prepared(CreateDraftCommand command, List<String> aiNotes,
            kr.astra.requirement.dto.RequirementSnapshot snapshot) {}

    public void validate(CurrentUser actor, AiDraftRequest input) {
        var requirement = requirements.snapshot(actor,input.requirementId());
        var selected = equipment.getDetail(input.equipmentId());
        if (!selected.testTypes().contains(requirement.testTypeCode()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"해당 장비가 지원하는 시험유형이 아닙니다.",null);
        if (input.matchResultId() != null)
            matching.validateSelection(actor,input.requirementId(),input.equipmentId(),input.matchResultId());
    }

    public Prepared prepare(CurrentUser actor, AiDraftRequest input) {
        validate(actor,input);
        var snapshot = requirements.snapshot(actor,input.requirementId());
        Long owner = requirements.ownerId(actor,input.requirementId());
        var applicant = users.getMe(owner);
        var selected = equipment.getDetail(input.equipmentId());
        Object match = input.matchResultId() == null ? Map.of() :
                matching.validateSelection(actor,input.requirementId(),input.equipmentId(),input.matchResultId());
        var schema = StructuredJson.object(Map.of("notes",Map.of("type","string"),
                "aiNotes",Map.of("type","array","items",Map.of("type","string"))));
        var reply = llm.complete(new LlmClient.Request(prompts.load("draft-request"),
                json.write(Map.of("requirement",snapshot,"equipment",selected,"match",match)),schema,"medium"));
        var root = json.parse(reply.text()); json.fields(root,"notes","aiNotes");
        String notes = json.text(root,"notes",true,4000);
        var advice = root.get("aiNotes");
        if (advice == null || !advice.isArray() || advice.size() > 20) throw StructuredJson.invalid();
        List<String> aiNotes = new ArrayList<>();
        for (var n : advice) {
            if (!n.isString() || n.asString().isBlank() || n.asString().length() > 500) throw StructuredJson.invalid();
            aiNotes.add(n.asString());
        }
        if (selected.isMock() || (match instanceof kr.astra.matching.dto.MatchResponse.Result m && m.usedMockData()))
            aiNotes.add("DEMO 사양이 포함되어 있습니다. 기관에 실제 사양을 확인해 주세요.");
        aiNotes.add(MatchingService.DISCLAIMER);
        Map<String,Object> content = new LinkedHashMap<>();
        content.put("applicant",fields("companyName",applicant.organization() == null ? null : applicant.organization().name(),
                "contactName",applicant.name(),"phone",applicant.phone(),"email",applicant.email()));
        content.put("test",fields("testTypeCode",snapshot.testTypeCode(),"purpose",snapshot.purpose()));
        content.put("sample",fields("name",snapshot.sampleName(),"quantity",snapshot.sampleQuantity()));
        content.put("conditions",snapshot.conditions());
        content.put("schedule",Map.of()); // Dates and standard were not supplied; do not invent them.
        content.put("equipment",fields("id",selected.id(),"externalId",selected.externalId(),"name",selected.name()));
        content.put("notes",notes);
        Map<String,Object> serviceRequirements = new LinkedHashMap<>();
        snapshot.conditions().stream().filter(c -> "accredited_required".equals(c.key())
                && c.status() == kr.astra.requirement.entity.ConditionStatus.CONFIRMED)
                .findFirst().ifPresent(c -> serviceRequirements.put("accreditedRequired", "필요".equals(c.valueText())));
        content.put("serviceRequirements",serviceRequirements);
        return new Prepared(new CreateDraftCommand(owner,selected.id(),snapshot.requirementId(),input.matchResultId(),
                snapshot.title() == null ? "AI 시험 의뢰 초안" : snapshot.title(),content,true),List.copyOf(aiNotes),snapshot);
    }

    /** Joins the pipeline checkpoint transaction so the returned ID and run state commit together. */
    @Transactional
    public Long create(CurrentUser actor, AiDraftRequest input, Prepared prepared) {
        if (!kr.astra.requirement.service.RequirementSnapshots.same(prepared.snapshot(),requirements.lockSnapshot(actor,input.requirementId())))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,"초안 생성 중 시험조건이 변경되었습니다. 다시 생성해 주세요.",null);
        validate(actor,input);
        var command = prepared.command();
        if (!Objects.equals(command.requirementId(),input.requirementId())
                || !Objects.equals(command.equipmentId(),input.equipmentId())
                || !Objects.equals(command.matchResultId(),input.matchResultId())
                || !Objects.equals(command.requesterId(),requirements.ownerId(actor,input.requirementId()))
                || !command.aiGenerated()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        TestRequestService service = requests.getIfAvailable();
        if (service == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"BE-B TestRequestService 구현이 연결되지 않았습니다.",null);
        Long id = service.createDraft(command);
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"BE-B 초안 저장 ID를 확인할 수 없습니다.",null);
        return id;
    }

    public AiDraftResponse createForApi(CurrentUser actor, AiDraftRequest input) {
        validate(actor,input);
        // Do not create an orphan draft and then fail the response: receipt support is a prerequisite.
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,RECEIPT_PENDING,Map.of("dependency","TestRequestService draft receipt"));
    }

    private Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i+1] != null) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
}
