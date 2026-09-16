package com.techtaurant.mainserver.common.exception

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.ValidationErrorResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.status.StatusMessageResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 애플리케이션 전역 예외를 중앙에서 처리하는 핸들러
 *
 * - Validation 에러: 검증 실패한 모든 필드 정보를 반환
 * - ApiException: 커스텀 상태 코드 기반 에러 응답 반환
 * - 일반 Exception: UNKNOWN_EXCEPTION 상태로 반환하고 상세 로깅
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val statusMessageResolver: StatusMessageResolver,
) {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * @RequestBody @Valid 검증 실패 시 발생하는 예외를 처리한다.
     * 검증 실패한 모든 필드와 에러 메시지를 반환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<ValidationErrorResponse>> {
        val errors =
            exception.bindingResult.fieldErrors.associate { fieldError ->
                fieldError.field to (fieldError.defaultMessage ?: "Invalid value")
            }

        log.warn("Validation failed: {}", errors)

        val validationErrorResponse = ValidationErrorResponse(errors)
        val errorMessage = statusMessageResolver.resolve(DefaultStatus.BAD_REQUEST, request)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(DefaultStatus.BAD_REQUEST, errorMessage, validationErrorResponse))
    }

    /**
     * @Validated @PathVariable/@RequestParam 검증 실패 시 발생하는 예외를 처리한다.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolationException(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<ValidationErrorResponse>> {
        val errors =
            exception.constraintViolations.associate { violation ->
                violation.propertyPath.toString() to (violation.message ?: "Invalid value")
            }

        log.warn("Constraint violation: {}", errors)

        val validationErrorResponse = ValidationErrorResponse(errors)
        val errorMessage = statusMessageResolver.resolve(DefaultStatus.BAD_REQUEST, request)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(DefaultStatus.BAD_REQUEST, errorMessage, validationErrorResponse))
    }

    /**
     * 비즈니스 로직에서 발생하는 ApiException을 처리한다.
     */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(
        exception: ApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<Any?>> {
        log.info("ApiException: status={}, detail={}", exception.status, exception.detail)

        val errorMessage = statusMessageResolver.resolve(exception.status, request)
        return ResponseEntity
            .status(exception.status.getHttpStatusCode())
            .body(ApiResponse.error(exception.status, errorMessage))
    }

    /**
     * 예상치 못한 예외를 처리한다.
     * UNKNOWN_EXCEPTION 상태로 반환하고, 디버깅을 위해 요청 정보와 스택 트레이스를 로깅한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiResponse<Any?>> {
        log.error(
            "Unhandled exception occurred: method={}, uri={}, queryString={}",
            request.method,
            request.requestURI,
            request.queryString,
            exception,
        )

        val errorMessage = statusMessageResolver.resolve(DefaultStatus.UNKNOWN_EXCEPTION, request)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(DefaultStatus.UNKNOWN_EXCEPTION, errorMessage))
    }
}
