package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
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

@Service
class TokenRefreshService(
    private val cookieHelper: CookieHelper,
    private val jwtTokenProvider: JwtTokenProvider,
    private val jwtProperties: JwtProperties,
    private val refreshTokenWhitelistService: RefreshTokenWhitelistService,
    private val userRepository: UserRepository,
) {
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

        // 3. DB에서 최신 User 조회 (권한 변경 반영)
        val user =
            userRepository.findById(userId).orElseThrow {
                ApiException(JwtStatus.INVALID_REFRESH_TOKEN)
            }

        // 4. 새 토큰 발급 (최신 권한 포함)
        val newAccessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        val newRefreshToken = jwtTokenProvider.createRefreshToken(userId)

        // 5. 기존 토큰이 whitelist에 있을 때만 원자적으로 새 토큰으로 교체
        val rotated =
            refreshTokenWhitelistService.rotate(
                userId = userId,
                expectedHash = jwtTokenProvider.hashToken(clientRefreshToken),
                replacementHash = jwtTokenProvider.hashToken(newRefreshToken),
            )
        if (!rotated) {
            throw ApiException(JwtStatus.INVALID_REFRESH_TOKEN)
        }

        // 6. whitelist 교체가 확정된 뒤 쿠키에 새 토큰 설정
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
