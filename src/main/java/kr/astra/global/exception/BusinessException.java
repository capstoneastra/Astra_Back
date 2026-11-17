package kr.astra.global.exception;

import java.util.List;
import java.util.Map;

/**
 * 비즈니스 규칙 위반. 서비스에서 throw 하면 GlobalExceptionHandler 가 ApiResponse 실패 응답으로 바꿉니다.
 * <pre>
 * throw new BusinessException(ErrorCode.REQUEST_INVALID_STATUS);
 * throw new BusinessException(ErrorCode.RESERVATION_OVERLAP, Map.of("conflicts", conflicts));
 * </pre>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, Object details) {
        this(errorCode, errorCode.defaultMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    /** 400 VALIDATION_FAILED, @Valid 실패와 같은 형식: message=reason, details.fields[{field, reason}] */
    public static BusinessException invalidField(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, reason, fieldDetails(field, reason));
    }

    /** 다른 코드로 필드 하나를 지목할 때 (message 는 코드 기본 문구) */
    public static BusinessException invalidField(ErrorCode code, String field, String reason) {
        return new BusinessException(code, code.defaultMessage(), fieldDetails(field, reason));
    }

    private static Map<String, Object> fieldDetails(String field, String reason) {
        return Map.of("fields", List.of(Map.of("field", field, "reason", reason)));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
