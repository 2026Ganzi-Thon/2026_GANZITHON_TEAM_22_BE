package kr.ganzithon.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex, HttpServletRequest request) {
        return error(ex.status(), ex.code(), ex.getMessage(), request, ex.retryable());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
        ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> input(Exception ex, HttpServletRequest request) {
        return error(400, "VALIDATION_FAILED", "요청 형식이나 값이 올바르지 않습니다.", request);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(Exception ex, HttpServletRequest request) {
        return error(404, "RESOURCE_NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", request);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        var result = error(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", request);
        var headers = new HttpHeaders();
        headers.putAll(result.getHeaders());
        if (ex.getSupportedHttpMethods() != null) headers.setAllow(ex.getSupportedHttpMethods());
        return new ResponseEntity<>(result.getBody(), headers, result.getStatusCode());
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMedia(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "요청 본문은 application/json 형식으로 보내 주세요.", request);
    }
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> unacceptableMedia(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return error(406, "NOT_ACCEPTABLE", "응답 형식은 application/json을 허용해 주세요.", request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
        return error(409, "DATA_CONFLICT", "현재 데이터와 요청이 충돌합니다.", request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        return error(500, "INTERNAL_ERROR", "처리 중 오류가 발생했습니다.", request);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request) {
        return error(status, code, message, request, status == 429 || status >= 500);
    }
    private ResponseEntity<ApiError> error(int status, String code, String message, HttpServletRequest request, boolean retryable) {
        var response = ResponseEntity.status(status);
        if ("CHAT_RATE_LIMITED".equals(code)) {
            response.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(new ApiError(code, message,
            String.valueOf(request.getAttribute("requestId")), retryable));
    }
}
