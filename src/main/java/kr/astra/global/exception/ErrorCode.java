package kr.astra.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 (api-spec.md §0-3 과 1:1). 코드를 추가·변경하면 api-spec.md 도 같이 수정하고 FE 에 공지하세요.
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    // 401
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "승인 대기 중이거나 이용이 제한된 계정입니다."),

    // 404 / 405
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
    EQUIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "장비를 찾을 수 없습니다."),
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "시험 의뢰를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    // 409
    REQUEST_INVALID_STATUS(HttpStatus.CONFLICT, "현재 의뢰 상태에서는 할 수 없는 작업입니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도해 주세요."),
    RESERVATION_OVERLAP(HttpStatus.CONFLICT, "선택한 시간에 이미 확정된 예약이 있습니다."),
    RESERVATION_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 예약이 확정된 의뢰입니다."),
    SUPPLEMENT_ALREADY_OPEN(HttpStatus.CONFLICT, "답변을 기다리는 보완 요청이 이미 있습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    ORGANIZATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "같은 이름의 조직이 이미 등록되어 있습니다."),

    // 500 / 502
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    AI_UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
