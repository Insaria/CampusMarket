package edu.bistu.campusmarket.exception;

import edu.bistu.campusmarket.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.failure(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation() {
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_FAILED", "参数校验失败"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("DATA_CONFLICT", "数据不符合约束"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled API exception", exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("INTERNAL_ERROR", "服务器内部错误"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ApiResponse<Void> response;
        if (status.is5xxServerError()) {
            response = ApiResponse.failure("INTERNAL_ERROR", "服务器内部错误");
        } else if (exception instanceof MethodArgumentNotValidException
                || exception instanceof HandlerMethodValidationException) {
            response = ApiResponse.failure("VALIDATION_FAILED", "参数校验失败");
        } else if (exception instanceof HttpMessageNotReadableException) {
            response = ApiResponse.failure("INVALID_REQUEST_BODY", "请求体缺失或格式错误");
        } else if (exception instanceof ServletRequestBindingException || exception instanceof TypeMismatchException) {
            response = ApiResponse.failure("INVALID_REQUEST_PARAMETER", "请求参数缺失或类型错误");
        } else {
            response = ApiResponse.failure("HTTP_" + status.value(), "请求无法处理");
        }
        return super.handleExceptionInternal(exception, response, headers, status, request);
    }
}
