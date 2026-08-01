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
     * 문자열 접미사나 특정 표기와의 일치가 아니라, 파싱한 host에 도메인 리터럴이 있는지로 판별한다.
     * Spring은 패턴의 모든 와일드카드를 임의 문자열로 확장하므로 포트 표기가 붙은 형태나
     * 와일드카드를 반복한 형태도 모든 host와 일치하는데, 접미사·동등 비교로는 이런 변형을 걸러내지 못한다.
     *
     * @param pattern 설정에서 파싱된 origin 패턴 하나
     * @return 특정 도메인으로 범위가 좁혀지지 않은 패턴이면 true
     */
    private fun isHostWideWildcardPattern(pattern: String): Boolean {
        val hostAndPort = pattern.substringAfter("://", missingDelimiterValue = pattern)
        val host = hostAndPort.substringBefore(":")

        return host.isNotEmpty() && host.none { it != WILDCARD_CHAR && it != DOMAIN_SEPARATOR_CHAR }
    }

    fun createCorsConfiguration(): CorsConfiguration =
        CorsConfiguration().apply {
            allowedOriginPatterns = parsedAllowedOriginPatterns
        }

    companion object {
        private const val WILDCARD_CHAR = '*'
        private const val DOMAIN_SEPARATOR_CHAR = '.'
    }
}
