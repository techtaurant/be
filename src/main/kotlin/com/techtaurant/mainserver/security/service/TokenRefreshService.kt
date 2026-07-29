package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.helper.JwtExceptionMapper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtStatus
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import io.jsonwebtoken.ExpiredJwtException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TokenRefreshService(
    private val cookieHelper: CookieHelper,
    private val jwtTokenProvider: JwtTokenProvider,
    private val jwtProperties: JwtProperties,
    private val tokenCacheManager: TokenCachePort,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun execute(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        // 1. 쿠키에서 refresh token 읽기
        val clientRefreshToken =
            cookieHelper.getCookie(request, JwtConstants.REFRESH_TOKEN_COOKIE)
                ?: throw ApiException(JwtStatus.MISSING_REFRESH_TOKEN)

        // 2. JWT 검증 및 userId 추출 (먼저!)
        val userId =
            try {
                jwtTokenProvider.validateAndGetUserId(clientRefreshToken)
            } catch (e: ExpiredJwtException) {
                throw ApiException(JwtStatus.REFRESH_TOKEN_EXPIRED)
            } catch (e: Exception) {
                throw ApiException(JwtExceptionMapper.mapToJwtStatus(e = e))
            }

        // 3. 캐시에서 userId로 저장된 refresh token 조회
        val cachedRefreshToken =
            tokenCacheManager.getRefreshToken(userId.toString())
                ?: throw ApiException(JwtStatus.INVALID_REFRESH_TOKEN)

        // 4. 클라이언트 토큰과 캐시 토큰 비교 (토큰 재사용 공격 방어)
        if (clientRefreshToken != cachedRefreshToken) {
            throw ApiException(JwtStatus.INVALID_REFRESH_TOKEN)
        }

        // 5. DB에서 최신 User 조회 (권한 변경 반영)
        val user =
            userRepository.findById(userId).orElseThrow {
                ApiException(JwtStatus.INVALID_REFRESH_TOKEN)
            }

        // 6. 새 토큰 발급 (최신 권한 포함)
        val newAccessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(userId)

        // 7. 새 refresh token 저장 (기존 토큰은 자동으로 덮어씌워짐)
        tokenCacheManager.saveRefreshToken(userId.toString(), newRefreshToken)

        // 8. 쿠키에 새 토큰 설정
        cookieHelper.addCookie(
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            newAccessToken,
            (jwtProperties.accessTokenExpireMs / 1000).toInt(),
        )
        cookieHelper.addCookie(
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            newRefreshToken,
            (jwtProperties.refreshTokenExpireMs / 1000).toInt(),
        )
    }
}
