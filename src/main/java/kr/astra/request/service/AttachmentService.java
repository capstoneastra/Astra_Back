package kr.astra.request.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.astra.global.exception.BusinessException;
import kr.astra.global.exception.ErrorCode;
import kr.astra.global.security.CurrentUser;
import kr.astra.global.storage.LocalFileStorage;
import kr.astra.notification.entity.NotificationType;
import kr.astra.request.dto.RequestResponses.AttachmentView;
import kr.astra.request.entity.Attachment;
import kr.astra.request.entity.AttachmentCategory;
import kr.astra.request.entity.RequestAction;
import kr.astra.request.entity.RequestStatus;
import kr.astra.request.entity.TestRequest;
import kr.astra.request.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 의뢰 첨부파일 (api-spec §5). 파일은 서버 디스크(LocalFileStorage), 정보는 attachments 표.
 * 성적서(RESULT_REPORT)는 장비 보유 조직이 일정 확정 뒤에 올리고, 올리면 의뢰자에게 알림.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttachmentService {

    /** 올릴 수 있는 파일 (실행 파일·스크립트는 받지 않음) */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "gif", "webp",
            "xlsx", "xls", "csv", "docx", "doc", "hwp", "hwpx", "pptx", "txt", "zip");
    private static final Set<RequestStatus> REPORT_STATUSES = EnumSet.of(RequestStatus.CONFIRMED, RequestStatus.COMPLETED);
    private static final int MAX_NAME = 300;

    private final AttachmentRepository attachmentRepository;
    private final LocalFileStorage storage;
    private final RequestWorkflow workflow;
    private final RequestViews views;

    public record Download(Resource resource, String fileName, long sizeBytes) {
    }

    /** POST /test-requests/{id}/attachments (multipart: file, category) */
    @Transactional
    public AttachmentView upload(CurrentUser actor, Long requestId, MultipartFile file, AttachmentCategory category) {
        TestRequest request = workflow.lock(requestId);
        AttachmentCategory kind = category == null ? AttachmentCategory.GENERAL : category;
        if (kind == AttachmentCategory.RESULT_REPORT) {
            workflow.authorize(RequestAction.Party.INSTITUTION, actor, request);
            if (!REPORT_STATUSES.contains(request.getStatus())) {
                throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS,
                        "성적서는 시험 일정이 확정된 뒤에 올릴 수 있습니다.", Map.of("currentStatus", request.getStatus()));
            }
        } else {
            workflow.requireParty(actor, request);
        }
        String fileName = cleanName(file);
        String key = "requests/" + requestId + "/" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            storage.save(key, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // DB 저장이 실패(롤백)하면 디스크에 남은 파일도 지움
        afterRollback(() -> storage.delete(key));
        Attachment saved = attachmentRepository.saveAndFlush(new Attachment(requestId, request.getCurrentRevisionNo(),
                fileName, key, file.getContentType(), file.getSize(), actor.userId(), kind));
        if (kind == AttachmentCategory.RESULT_REPORT) {
            workflow.notifyRequester(request, NotificationType.RESULT_REGISTERED,
                    "시험 성적서 파일이 올라왔습니다: " + fileName);
        }
        return view(saved, views.people(List.of(actor.userId())));
    }

    /** GET /test-requests/{id}/attachments — 의뢰 당사자만 */
    public List<AttachmentView> list(CurrentUser actor, Long requestId) {
        TestRequest request = workflow.get(requestId);
        workflow.requireParty(actor, request);
        List<Attachment> all = attachmentRepository.findAllByRequestIdOrderByIdAsc(requestId);
        RequestViews.Names names = views.people(all.stream().map(Attachment::getUploadedBy).distinct().toList());
        return all.stream().map(a -> view(a, names)).toList();
    }

    /** GET /attachments/{id}/download — 의뢰 당사자만 */
    public Download download(CurrentUser actor, Long attachmentId) {
        Attachment a = find(attachmentId);
        workflow.requireParty(actor, workflow.get(a.getRequestId()));
        Resource resource = storage.load(a.getStorageKey());
        if (resource == null) {
            throw notFound();
        }
        return new Download(resource, a.getFileName(), a.getSizeBytes() == null ? -1 : a.getSizeBytes());
    }

    /** DELETE /attachments/{id} — 올린 사람(또는 관리자)만 */
    @Transactional
    public void delete(CurrentUser actor, Long attachmentId) {
        Attachment a = find(attachmentId);
        if (!actor.isAdmin() && !a.getUploadedBy().equals(actor.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "올린 사람만 지울 수 있습니다.", null);
        }
        attachmentRepository.delete(a);
        attachmentRepository.flush();
        String key = a.getStorageKey();
        afterCommit(() -> storage.delete(key));
    }

    private Attachment find(Long id) {
        return attachmentRepository.findById(id).orElseThrow(AttachmentService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다.", null);
    }

    /** 경로 부분을 떼고 허용된 확장자인지 확인 */
    private static String cleanName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalidField("file", "파일을 선택해 주세요.");
        }
        String raw = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw BusinessException.invalidField("file",
                    "올릴 수 없는 파일 형식입니다. (pdf, 이미지, 엑셀·워드·한글·파워포인트, csv, txt, zip)");
        }
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }

    private AttachmentView view(Attachment a, RequestViews.Names names) {
        return new AttachmentView(a.getId(), a.getRequestId(), a.getCategory(), a.getFileName(), a.getMimeType(),
                a.getSizeBytes(), names.person(a.getUploadedBy()), a.getCreatedAt(),
                "/api/v1/attachments/" + a.getId() + "/download");
    }

    private static void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    private static void afterRollback(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    task.run();
                }
            }
        });
    }
}
