package kr.astra.global.exception;

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

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
