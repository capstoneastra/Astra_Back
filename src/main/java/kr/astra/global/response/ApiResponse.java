package kr.astra.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.astra.global.exception.ErrorCode;

/**
 * 모든 API 의 공통 응답 형식 (api-spec.md §0-1).
 * <pre>
 * 성공: { "success": true,  "data": { ... } }
 * 실패: { "success": false, "error": { "code": "...", "message": "...", "details": ... } }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode code, String message, Object details) {
        return new ApiResponse<>(false, null, new ErrorBody(code.name(), message, details));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorBody(String code, String message, Object details) {
    }
}
