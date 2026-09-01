package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.jwt.JwtStatus
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.SecurityException
import org.slf4j.LoggerFactory

object JwtExceptionMapper {
    private val log = LoggerFactory.getLogger(this.javaClass)

    fun mapToJwtStatus(e: Exception): JwtStatus {
        return when (e) {
            is MalformedJwtException -> JwtStatus.MALFORMED_TOKEN
            is UnsupportedJwtException -> JwtStatus.UNSUPPORTED_TOKEN
            // 다른 시크릿으로 서명된 토큰은 형식이 멀쩡해 서명 검증에서만 걸린다.
            // 재발급으로 되살릴 수 없는 실패이므로 401로 알리고 쿠키까지 걷어내야
            // 남아 있는 옛 토큰이 인증을 계속 가로채는 상태에서 빠져나올 수 있다.
            is SecurityException -> JwtStatus.INVALID_TOKEN
            is IllegalArgumentException -> JwtStatus.INVALID_TOKEN
            else -> {
                log.error("Unknown JWT error: {}", e.message, e)
                JwtStatus.UNKNOWN_ERROR
            }
        }
    }
}
