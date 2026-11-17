package kr.astra.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.astra.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 지원하지_않는_Content_Type_은_500이_아니라_400대() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(res.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 받을_수_없는_Accept_도_400대() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(res.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 프레임워크_ErrorResponse_는_상태코드별로_기존_코드에_맞춘다() {
        assertThat(codeOf(new ErrorResponseException(HttpStatus.BAD_REQUEST))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(codeOf(new ErrorResponseException(HttpStatus.NOT_FOUND))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(new ErrorResponseException(HttpStatus.METHOD_NOT_ALLOWED)))
                .isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(codeOf(new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE)))
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 클라이언트가_끊은_연결에는_본문을_쓰지_않는다() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(
                new AsyncRequestNotUsableException("Broken pipe"));
        assertThat(res).isNull();
    }

    @Test
    void 서비스에서_변환하지_않은_DB_제약_위반은_409() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(
                new DataIntegrityViolationException("duplicate key"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().error().code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void invalidField_는_Valid_실패와_같은_details_형식() {
        BusinessException e = BusinessException.invalidField("role", "사유");
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.getMessage()).isEqualTo("사유");
        assertThat(e.getDetails()).isEqualTo(Map.of("fields", List.of(Map.of("field", "role", "reason", "사유"))));

        BusinessException custom = BusinessException.invalidField(ErrorCode.EMAIL_NOT_VERIFIED, "verificationToken", "x");
        assertThat(custom.getErrorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
        assertThat(custom.getMessage()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED.defaultMessage());
        assertThat(custom.getDetails())
                .isEqualTo(Map.of("fields", List.of(Map.of("field", "verificationToken", "reason", "x"))));
    }

    private ErrorCode codeOf(Exception e) {
        ResponseEntity<ApiResponse<Void>> res = handler.handleUnexpected(e);
        ErrorCode code = ErrorCode.valueOf(res.getBody().error().code());
        assertThat(res.getStatusCode().value()).isEqualTo(code.status().value());
        return code;
    }
}
