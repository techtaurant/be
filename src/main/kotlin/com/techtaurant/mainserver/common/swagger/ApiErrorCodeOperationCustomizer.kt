package com.techtaurant.mainserver.common.swagger

import com.techtaurant.mainserver.common.dto.ApiResponse
import com.techtaurant.mainserver.common.dto.ValidationErrorResponse
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.status.StatusIfs
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MapSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.AnnotatedElement
import io.swagger.v3.oas.models.examples.Example as SwaggerExample
import io.swagger.v3.oas.models.responses.ApiResponse as SwaggerApiResponse

@Component
class ApiErrorCodeOperationCustomizer : OperationCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod,
    ): Operation {
        val annotations = collectAnnotations(handlerMethod)
        if (annotations.isEmpty()) return operation

        if (operation.responses == null) {
            operation.responses = ApiResponses()
        }

        val statusEntries = extractStatusEntries(annotations)
        val groupedByHttpStatus = statusEntries.groupBy { it.getHttpStatusCode().toString() }

        groupedByHttpStatus.forEach { (httpStatusCode, statuses) ->
            addErrorResponse(operation, httpStatusCode, statuses)
        }

        return operation
    }

    private fun collectAnnotations(handlerMethod: HandlerMethod): List<ApiErrorCodeResponse> {
        val methodAnnotations = collectAnnotations(handlerMethod.method)
        val classAnnotations = collectAnnotations(handlerMethod.beanType)

        return (methodAnnotations + classAnnotations)
            .distinctBy { annotation ->
                "${annotation.statusType.qualifiedName}:${annotation.values.joinToString(",")}"
            }
    }

    private fun collectAnnotations(element: AnnotatedElement): List<ApiErrorCodeResponse> {
        val singleAnnotations =
            AnnotatedElementUtils
                .findAllMergedAnnotations(element, ApiErrorCodeResponse::class.java)
                .toList()
        val groupedAnnotations =
            AnnotatedElementUtils
                .findAllMergedAnnotations(element, ApiErrorCodeResponses::class.java)
                .flatMap { annotation -> annotation.value.toList() }

        return singleAnnotations + groupedAnnotations
    }

    private fun extractStatusEntries(annotations: List<ApiErrorCodeResponse>): List<StatusIfs> {
        return annotations.flatMap { annotation ->
            val enumClass = annotation.statusType.java
            val allConstants = enumClass.enumConstants?.filterIsInstance<StatusIfs>() ?: emptyList()
            val requestedValues = annotation.values

            if (requestedValues.isEmpty()) {
                allConstants
            } else {
                val valueNames = requestedValues.toSet()
                allConstants.filter { (it as Enum<*>).name in valueNames }
            }
        }
    }

    private fun addErrorResponse(
        operation: Operation,
        httpStatusCode: String,
        statuses: List<StatusIfs>,
    ) {
        val existing = operation.responses?.get(httpStatusCode)
        val response = existing ?: SwaggerApiResponse()

        if (response.description.isNullOrBlank()) {
            response.description = resolveDescription(httpStatusCode)
        }

        val content = response.content ?: Content()
        val mediaType = content["application/json"] ?: MediaType()

        statuses.forEach { status ->
            val example =
                SwaggerExample().apply {
                    value = buildErrorExampleValue(status)
                    description = status.getDescription()
                }
            mediaType.addExamples(resolveExampleName(status), example)
        }

        val hasValidationError = httpStatusCode == "400"
        if (hasValidationError) {
            addValidationErrorExample(mediaType)
        }

        if (mediaType.schema == null) {
            mediaType.schema =
                if (hasValidationError) {
                    ComposedSchema()
                        .addOneOfItem(businessErrorSchema())
                        .addOneOfItem(validationErrorSchema())
                } else {
                    businessErrorSchema()
                }
        }

        content.addMediaType("application/json", mediaType)
        response.content = content
        operation.responses.addApiResponse(httpStatusCode, response)
    }

    private fun businessErrorSchema(): Schema<*> =
        ObjectSchema()
            .addProperty("status", IntegerSchema().description("커스텀 상태 코드").example(2001))
            .addProperty(
                "data",
                Schema<Any>().apply {
                    description = "에러 상세 데이터"
                    nullable = true
                },
            )
            .addProperty("message", StringSchema().description("에러 메시지").example("게시물을 찾을 수 없습니다"))

    private fun validationErrorSchema(): Schema<*> =
        ObjectSchema()
            .addProperty("status", IntegerSchema().description("커스텀 상태 코드").example(400))
            .addProperty(
                "data",
                ObjectSchema()
                    .description("검증 오류 상세 정보")
                    .addProperty(
                        "errors",
                        MapSchema()
                            .description("필드별 검증 오류")
                            .additionalProperties(StringSchema())
                            .example(mapOf("field" to "유효하지 않은 값입니다")),
                    ),
            )
            .addProperty("message", StringSchema().description("에러 메시지").example("Wrong Request"))

    private fun buildErrorExampleValue(status: StatusIfs): Map<String, Any?> {
        val errorResponse = ApiResponse.error<Any>(status)
        return linkedMapOf(
            "status" to errorResponse.status,
            "data" to errorResponse.data,
            "message" to errorResponse.message,
        )
    }

    private fun addValidationErrorExample(mediaType: MediaType) {
        val validationData = ValidationErrorResponse(mapOf("field" to "에러 메시지"))
        val validationResponse = ApiResponse.error(DefaultStatus.BAD_REQUEST, validationData)
        val example =
            SwaggerExample().apply {
                value =
                    linkedMapOf(
                        "status" to validationResponse.status,
                        "data" to validationResponse.data,
                        "message" to validationResponse.message,
                    )
                description = DefaultStatus.BAD_REQUEST.getDescription()
            }
        mediaType.addExamples("Validation 에러", example)
    }

    private fun resolveDescription(httpStatusCode: String): String =
        when (httpStatusCode) {
            "400" -> "입력값 검증 실패 또는 비즈니스 규칙 위반"
            "401" -> "인증 필요"
            "403" -> "권한 없음"
            "404" -> "리소스 미존재"
            "409" -> "동시 요청 충돌"
            "500" -> "서버 오류"
            else -> "에러"
        }

    private fun resolveExampleName(status: StatusIfs): String = status.getDescription()
}
