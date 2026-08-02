package com.techtaurant.mainserver.common.swagger

import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.status.StatusIfs
import com.techtaurant.mainserver.security.jwt.JwtStatus
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MapSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.method.HandlerMethod

@Configuration
class SwaggerErrorResponseConfig {
    @Bean
    fun apiErrorResponseCustomizer(): OperationCustomizer {
        return OperationCustomizer { operation, handlerMethod ->
            val apiErrorResponses = findApiErrorResponses(handlerMethod) ?: return@OperationCustomizer operation

            val groupedExamples = groupExamples(apiErrorResponses)
            val responses = operation.responses ?: ApiResponses().also { operation.responses = it }

            groupedExamples.forEach { (httpStatusCode, examples) ->
                responses.addApiResponse(httpStatusCode.toString(), buildApiResponse(httpStatusCode, examples))
            }

            operation
        }
    }

    private fun findApiErrorResponses(handlerMethod: HandlerMethod): ApiErrorResponses? {
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.method, ApiErrorResponses::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(handlerMethod.beanType, ApiErrorResponses::class.java)
    }

    private fun groupExamples(apiErrorResponses: ApiErrorResponses): Map<Int, List<SwaggerErrorExample>> {
        val groupedExamples = linkedMapOf<Int, MutableList<SwaggerErrorExample>>()

        buildStatuses(apiErrorResponses)
            .forEach { status ->
                groupedExamples
                    .getOrPut(status.getHttpStatusCode()) { mutableListOf() }
                    .add(
                        SwaggerErrorExample(
                            key = status.javaClass.simpleName + "_" + status.toString(),
                            status = status.getCustomStatusCode(),
                            message = status.getDescription(),
                        ),
                    )
            }

        if (apiErrorResponses.includeValidationError) {
            groupedExamples
                .getOrPut(400) { mutableListOf() }
                .add(
                    SwaggerErrorExample(
                        key = "validation_error",
                        status = DefaultStatus.BAD_REQUEST.getCustomStatusCode(),
                        message = DefaultStatus.BAD_REQUEST.getDescription(),
                        data = mapOf("errors" to mapOf("field" to "유효하지 않은 값입니다")),
                        isValidationError = true,
                    ),
                )
        }

        groupedExamples
            .getOrPut(500) { mutableListOf() }
            .add(
                SwaggerErrorExample(
                    key = "default_unknown_exception",
                    status = DefaultStatus.UNKNOWN_EXCEPTION.getCustomStatusCode(),
                    message = DefaultStatus.UNKNOWN_EXCEPTION.getDescription(),
                ),
            )

        return groupedExamples.mapValues { (_, examples) ->
            examples.distinctBy { it.key }
        }
    }

    private fun buildStatuses(apiErrorResponses: ApiErrorResponses): List<StatusIfs> {
        val statuses = mutableListOf<StatusIfs>()

        statuses += apiErrorResponses.comments
        statuses += apiErrorResponses.defaults
        statuses += apiErrorResponses.jwts
        statuses += apiErrorResponses.oauths
        statuses += apiErrorResponses.posts
        statuses += apiErrorResponses.users

        if (apiErrorResponses.includeAuthenticationErrors) {
            statuses += COMMON_AUTHENTICATION_ERRORS
        }

        return statuses.distinctBy { status ->
            "${status.javaClass.name}:${status.getCustomStatusCode()}"
        }
    }

    private fun buildApiResponse(
        httpStatusCode: Int,
        examples: List<SwaggerErrorExample>,
    ): ApiResponse {
        val mediaType =
            MediaType()
                .schema(buildSchema(examples))
                .examples(buildExamples(examples))

        return ApiResponse()
            .description(resolveDescription(httpStatusCode))
            .content(Content().addMediaType("application/json", mediaType))
    }

    private fun buildSchema(examples: List<SwaggerErrorExample>): Schema<*> {
        val hasValidationExample = examples.any { it.isValidationError }
        val hasBusinessExample = examples.any { !it.isValidationError }

        return when {
            hasValidationExample && hasBusinessExample ->
                ComposedSchema()
                    .addOneOfItem(validationErrorSchema())
                    .addOneOfItem(businessErrorSchema())

            hasValidationExample -> validationErrorSchema()

            else -> businessErrorSchema()
        }
    }

    private fun buildExamples(examples: List<SwaggerErrorExample>): MutableMap<String, Example> {
        val exampleMap = linkedMapOf<String, Example>()

        examples.forEach { example ->
            exampleMap[example.key] =
                Example()
                    .summary(example.message)
                    .value(
                        linkedMapOf(
                            "status" to example.status,
                            "data" to example.data,
                            "message" to example.message,
                        ),
                    )
        }

        return exampleMap
    }

    private fun businessErrorSchema(): Schema<*> {
        return ObjectSchema()
            .addProperty("status", IntegerSchema().description("커스텀 상태 코드").example(2001))
            .addProperty(
                "data",
                Schema<Any>().apply {
                    description = "에러 상세 데이터"
                    nullable = true
                },
            )
            .addProperty("message", StringSchema().description("에러 메시지").example("게시물을 찾을 수 없습니다"))
    }

    private fun validationErrorSchema(): Schema<*> {
        return ObjectSchema()
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
    }

    private fun resolveDescription(httpStatusCode: Int): String {
        return when (httpStatusCode) {
            400 -> "잘못된 요청"
            401 -> "인증 실패"
            403 -> "권한 없음"
            404 -> "리소스를 찾을 수 없음"
            500 -> "서버 오류"
            else -> "에러 응답"
        }
    }

    private data class SwaggerErrorExample(
        val key: String,
        val status: Int,
        val message: String,
        val data: Any? = null,
        val isValidationError: Boolean = false,
    )

    companion object {
        private val COMMON_AUTHENTICATION_ERRORS =
            listOf(
                JwtStatus.AUTHENTICATION_REQUIRED,
                JwtStatus.ACCESS_TOKEN_EXPIRED,
                JwtStatus.ACCESS_DENIED,
                JwtStatus.INVALID_TOKEN,
                JwtStatus.MALFORMED_TOKEN,
                JwtStatus.UNSUPPORTED_TOKEN,
                JwtStatus.UNKNOWN_ERROR,
            )
    }
}
