package com.techtaurant.mainserver.security.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.cors.CorsConfiguration

@ConfigurationProperties(prefix = "cors")
data class CorsProperties(
    val allowedOriginPatterns: String = "",
) {
    init {
        val hostWideWildcardPatterns = parsedAllowedOriginPatterns.filter(::isHostWideWildcardPattern)
        require(hostWideWildcardPatterns.isEmpty()) {
            "쿠키 인증(allowCredentials=true)에서 호스트 전체를 여는 origin 패턴은 사용할 수 없습니다: $hostWideWildcardPatterns"
        }
    }

    val parsedAllowedOriginPatterns: List<String>
        get() =
            allowedOriginPatterns
                .split(",")
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }

    /**
     * 호스트 전체를 여는 origin 패턴인지 판별한다.
     *
     * allowedOrigins와 달리 allowedOriginPatterns에는 Spring의 와일드카드 검증이 없고 매칭된 origin을 그대로 반사한다.
     * 따라서 이런 패턴이 들어오면 임의 origin에 쿠키 포함 요청과 OAuth2 리다이렉트가 허용되므로 기동 시점에 차단한다.
     *
     * @param pattern 설정에서 파싱된 origin 패턴 하나
     * @return 특정 도메인으로 범위가 좁혀지지 않은 패턴이면 true
     */
    private fun isHostWideWildcardPattern(pattern: String): Boolean = pattern == "*" || pattern.endsWith("://*")

    fun createCorsConfiguration(): CorsConfiguration =
        CorsConfiguration().apply {
            allowedOriginPatterns = parsedAllowedOriginPatterns
        }
}
