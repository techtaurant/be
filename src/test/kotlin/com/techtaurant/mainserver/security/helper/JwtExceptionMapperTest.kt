package com.techtaurant.mainserver.security.helper

import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.enums.UserRole
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.SignatureException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class JwtExceptionMapperTest {
    @Test
    @DisplayName("MalformedJwtException은 MALFORMED_TOKEN으로 매핑")
    fun `map MalformedJwtException to MALFORMED_TOKEN`() {
        val status = JwtExceptionMapper.mapToJwtStatus(MalformedJwtException("malformed"))
        assertEquals(JwtStatus.MALFORMED_TOKEN, status)
    }

    @Test
    @DisplayName("UnsupportedJwtException은 UNSUPPORTED_TOKEN으로 매핑")
    fun `map UnsupportedJwtException to UNSUPPORTED_TOKEN`() {
        val status = JwtExceptionMapper.mapToJwtStatus(UnsupportedJwtException("unsupported"))
        assertEquals(JwtStatus.UNSUPPORTED_TOKEN, status)
    }

    @Test
    @DisplayName("IllegalArgumentException은 INVALID_TOKEN으로 매핑")
    fun `map IllegalArgumentException to INVALID_TOKEN`() {
        val status = JwtExceptionMapper.mapToJwtStatus(IllegalArgumentException("invalid"))
        assertEquals(JwtStatus.INVALID_TOKEN, status)
    }

    @Test
    @DisplayName("서명 검증 실패는 INVALID_TOKEN으로 매핑")
    fun `map SignatureException to INVALID_TOKEN`() {
        val status = JwtExceptionMapper.mapToJwtStatus(SignatureException("signature mismatch"))
        assertEquals(JwtStatus.INVALID_TOKEN, status)
    }

    /**
     * 예외 타입을 손으로 넣는 검증만으로는 실제 토큰이 어떤 예외를 던지는지 알 수 없어,
     * 다른 시크릿으로 서명된 진짜 토큰을 넣어 매핑 결과까지 한 번에 확인한다.
     */
    @Test
    @DisplayName("다른 시크릿으로 서명된 진짜 토큰은 INVALID_TOKEN으로 매핑")
    fun `map token signed with another secret to INVALID_TOKEN`() {
        val tokenFromAnotherServer =
            providerWithSecret("another-server-jwt-secret-key-minimum-256-bits-for-hs256")
                .createAccessToken(UUID.randomUUID(), UserRole.USER)
        val thisServer = providerWithSecret("this-server-jwt-secret-key-minimum-256-bits-for-hs256")

        val thrown =
            assertThrows<Exception> {
                thisServer.validateAndGetClaims(tokenFromAnotherServer)
            }

        assertEquals(JwtStatus.INVALID_TOKEN, JwtExceptionMapper.mapToJwtStatus(thrown))
    }

    @Test
    @DisplayName("알 수 없는 예외는 UNKNOWN_ERROR로 매핑")
    fun `map unknown exception to UNKNOWN_ERROR`() {
        val status = JwtExceptionMapper.mapToJwtStatus(RuntimeException("unknown"))
        assertEquals(JwtStatus.UNKNOWN_ERROR, status)
    }

    private fun providerWithSecret(secret: String): JwtTokenProvider {
        return JwtTokenProvider(JwtProperties(secret = secret))
    }
}
