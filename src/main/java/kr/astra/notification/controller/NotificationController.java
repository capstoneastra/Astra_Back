package kr.astra.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.astra.global.response.ApiResponse;
import kr.astra.global.response.PageResponse;
import kr.astra.global.security.CurrentUser;
import kr.astra.notification.dto.NotificationResponse;
import kr.astra.notification.service.NotificationServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "내 알림 목록·안 읽은 수·읽음 처리 (api-spec §10, 트리거 §13). 본인 알림만")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationServiceImpl notificationService;

    @Operation(summary = "알림 목록", description = "최신순. ?unreadOnly=true 면 안 읽은 것만")
    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.ok(notificationService.list(user, unreadOnly, pageable));
    }

    @Operation(summary = "안 읽은 알림 수", description = "헤더 배지용 { count }")
    @GetMapping("/unread-count")
    public ApiResponse<NotificationResponse.UnreadCount> unreadCount(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(notificationService.unreadCount(user));
    }

    @Operation(summary = "읽음 처리", description = "이미 읽었으면 그대로. 남의 알림이면 404")
    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationResponse> read(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(notificationService.markRead(user, id));
    }

    @Operation(summary = "모두 읽음", description = "{ updated: 바뀐 개수 }")
    @PostMapping("/read-all")
    public ApiResponse<NotificationResponse.ReadAll> readAll(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(notificationService.markAllRead(user));
    }
}
