package kr.astra.request.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.request.dto.RequestResponses.AttachmentView;
import kr.astra.request.entity.AttachmentCategory;
import kr.astra.request.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 의뢰 첨부파일·성적서 파일 (api-spec §5). 파일당 최대 20MB */
@Tag(name = "시험 의뢰 첨부파일")
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @Operation(summary = "첨부 올리기", description = "multipart: file, category(GENERAL 기본 / RESULT_REPORT). "
            + "성적서는 장비 보유 조직이 일정 확정 뒤에만 올리고 의뢰자에게 알림")
    @PostMapping(value = "/test-requests/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AttachmentView>> upload(@AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) AttachmentCategory category) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(attachmentService.upload(user, id, file, category)));
    }

    @Operation(summary = "첨부 목록", description = "의뢰 당사자(의뢰자·장비 보유 조직)만")
    @GetMapping("/test-requests/{id}/attachments")
    public ApiResponse<List<AttachmentView>> list(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(attachmentService.list(user, id));
    }

    @Operation(summary = "첨부 내려받기", description = "항상 다운로드로 내려줌 (브라우저에서 바로 열리지 않게)")
    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        AttachmentService.Download d = attachmentService.download(user, id);
        ResponseEntity.BodyBuilder res = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff");
        if (d.sizeBytes() >= 0) {
            res.contentLength(d.sizeBytes());
        }
        return res.body(d.resource());
    }

    @Operation(summary = "첨부 지우기", description = "올린 사람(또는 관리자)만")
    @DeleteMapping("/attachments/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        attachmentService.delete(user, id);
        return ApiResponse.ok();
    }
}
