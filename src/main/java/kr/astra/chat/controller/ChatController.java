package kr.astra.chat.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.*;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.*;
import kr.astra.chat.dto.ChatResponse;
import kr.astra.chat.service.*;
import kr.astra.global.exception.*;
import kr.astra.global.response.*;
import kr.astra.global.security.CurrentUser;
import kr.astra.llm.client.AiTaskExecutor;
import kr.astra.requirement.dto.RequirementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "AI 상담") @RestController @RequestMapping("/chat/sessions") @RequiredArgsConstructor
public class ChatController {
    private final ChatPersistence persistence;
    private final ChatService service;
    private final AiTaskExecutor executor;
    public record CreateRequest(@Size(max = 200) String title) {}
    public record MessageRequest(@NotBlank @Size(max = 10000) String content) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChatResponse.Session> create(@AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody(required = false) CreateRequest request) {
        return ApiResponse.ok(persistence.create(user,request == null ? null : request.title()));
    }
    @GetMapping
    public ApiResponse<PageResponse<ChatResponse.Session>> list(@AuthenticationPrincipal CurrentUser user, Pageable page) {
        return ApiResponse.ok(persistence.list(user,page));
    }
    @GetMapping("/{id}/messages")
    public ApiResponse<PageResponse<ChatResponse.Message>> messages(@AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id, Pageable page) { return ApiResponse.ok(persistence.messages(user,id,page)); }
    @PostMapping(value = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatResponse.Exchange> send(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody MessageRequest request) { return ApiResponse.ok(service.send(user,id,request.content(),null)); }
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id,
            @Valid @RequestBody MessageRequest request) {
        persistence.ownerId(user,id); // failures before SSE starts retain normal HTTP 401/403/404
        SseEmitter emitter = new SseEmitter(150000L);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        Runnable cancel = () -> {
            closed.set(true);
            Future<?> future = task.get();
            if (future != null) future.cancel(true);
        };
        emitter.onTimeout(() -> {
            if (!closed.get()) {
                try { sendEvent(emitter,"error",ApiResponse.fail(ErrorCode.AI_UPSTREAM_ERROR,"AI 응답 시간이 초과되었습니다.",null)); }
                catch (RuntimeException ignored) { /* disconnected client */ }
            }
            cancel.run();
            emitter.complete();
        });
        emitter.onError(e -> cancel.run());
        emitter.onCompletion(cancel);
        Future<?> future = executor.submit(() -> {
            if (closed.get() || Thread.currentThread().isInterrupted()) return;
            try {
                var result = service.send(user,id,request.content(),chunk -> {
                    if (closed.get()) throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR);
                    sendEvent(emitter,"delta",ApiResponse.ok(Map.of("text",chunk)));
                });
                if (!closed.get()) {
                    sendEvent(emitter,"done",ApiResponse.ok(result));
                    emitter.complete();
                }
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    ErrorCode code = e instanceof BusinessException b ? b.getErrorCode() : ErrorCode.INTERNAL_ERROR;
                    try { sendEvent(emitter,"error",ApiResponse.fail(code,code.defaultMessage(),null)); }
                    catch (RuntimeException ignored) { /* disconnected client */ }
                    emitter.complete();
                }
            }
        });
        task.set(future);
        if (closed.get()) future.cancel(true);
        return emitter;
    }
    private static void sendEvent(SseEmitter emitter,String name,Object data) {
        try { emitter.send(SseEmitter.event().name(name).data(data,MediaType.APPLICATION_JSON)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // Before an emitter starts, errors must remain JSON even when Accept only allows SSE.
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> businessError(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(e.getErrorCode(),e.getMessage(),e.getDetails()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validationError(org.springframework.web.bind.MethodArgumentNotValidException e) {
        var result = new GlobalExceptionHandler().handleValidation(e);
        return ResponseEntity.status(result.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(result.getBody());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformedJson(org.springframework.http.converter.HttpMessageNotReadableException e) {
        var result = new GlobalExceptionHandler().handleBadRequest(e);
        return ResponseEntity.status(result.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(result.getBody());
    }
    @PostMapping("/{id}/requirement")
    public ApiResponse<RequirementResponse> requirement(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return ApiResponse.ok(service.requirement(user,id));
    }
}
