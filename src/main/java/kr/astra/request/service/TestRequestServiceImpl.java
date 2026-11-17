package kr.astra.request.service;

import java.util.Map;
import kr.astra.equipment.dto.EquipmentDetailResponse;
import kr.astra.equipment.service.EquipmentService;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.request.dto.CreateDraftCommand;
import kr.astra.request.dto.DraftReceipt;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.entity.TestRequestRevision;
import kr.astra.request.repository.RequestNoGenerator;
import kr.astra.request.repository.TestRequestRepository;
import kr.astra.request.repository.TestRequestRevisionRepository;
import kr.astra.user.dto.UserAccount;
import kr.astra.user.entity.UserRole;
import kr.astra.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 의뢰 생성 (회원이 직접 / BE-C AI 초안·자동 추진 공용). TestRequestService 구현.
 * 기업·기관·대학 회원 모두 의뢰할 수 있지만, 자기 소속 조직이 보유한 장비에는 의뢰할 수 없습니다.
 */
@Service
@RequiredArgsConstructor
public class TestRequestServiceImpl implements TestRequestService {

    static final String DEFAULT_TITLE = "시험 의뢰";

    private final TestRequestRepository requestRepository;
    private final TestRequestRevisionRepository revisionRepository;
    private final RequestNoGenerator requestNoGenerator;
    private final RequestWorkflow workflow;
    private final RequestContent content;
    private final EquipmentService equipmentService;
    private final UserService userService;

    @Override
    @Transactional
    public Long createDraft(CreateDraftCommand command) {
        if (command == null || command.requesterId() == null || command.equipmentId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "의뢰자와 장비는 필수입니다.", null);
        }
        UserAccount requester = userService.getActiveAccount(command.requesterId());
        if (requester.role() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "플랫폼 관리자 계정으로는 시험 의뢰를 만들 수 없습니다.", null);
        }
        EquipmentDetailResponse equipment = equipmentService.getDetail(command.equipmentId());
        if (equipment.institution().id().equals(requester.organizationId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "소속 조직이 보유한 장비에는 의뢰할 수 없습니다.",
                    Map.of("field", "equipmentId"));
        }

        Map<String, Object> body = command.content() == null ? Map.of() : command.content();
        content.validate(body);
        RequestContent.Schedule schedule = content.schedule(body);
        String title = command.title() == null || command.title().isBlank() ? DEFAULT_TITLE : command.title().strip();
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }

        TestRequest request = requestRepository.save(new TestRequest(requestNoGenerator.next(), requester.id(),
                requester.organizationId(), equipment.institution().id(), equipment.id(), command.requirementId(),
                command.matchResultId(), title, schedule.start(), schedule.end()));
        revisionRepository.save(new TestRequestRevision(request.getId(), 1, content.write(body),
                command.aiGenerated(), command.aiGenerated() ? "AI 초안" : null, requester.id()));
        workflow.recordCreated(request, requester.id(), command.aiGenerated() ? "AI 의뢰서 초안 생성" : "의뢰 작성 시작");
        return request.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public DraftReceipt getDraftReceipt(Long requestId) {
        TestRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        TestRequestRevision revision = revisionRepository.findByRequestIdAndRevisionNo(requestId, 1)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUEST_NOT_FOUND));
        return new DraftReceipt(request.getId(), request.getRequestNo(), request.getStatus(), request.getVersion(),
                revision.getRevisionNo(), revision.isAiGenerated(), content.read(revision.getContent()));
    }
}
