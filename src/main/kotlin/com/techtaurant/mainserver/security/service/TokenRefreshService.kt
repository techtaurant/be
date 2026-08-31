package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.helper.JwtExceptionMapper
import com.techtaurant.mainserver.security.infrastructure.out.RefreshTokenStore
import com.techtaurant.mainserver.security.jwt.JwtConstants
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
    private val refreshTokenStore: RefreshTokenStore,
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
                jwtTokenProvider.validateAndGetRefreshTokenUserId(clientRefreshToken)
            } catch (e: ExpiredJwtException) {
                throw ApiException(JwtStatus.REFRESH_TOKEN_EXPIRED)
            } catch (e: Exception) {
                throw ApiException(JwtExceptionMapper.mapToJwtStatus(e = e))
            }

        // 3. 저장된 refresh token과 대조 (토큰 재사용 공격 방어)
        if (!refreshTokenStore.exists(userId, clientRefreshToken)) {
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

        // 7. 사용한 refresh token을 폐기하고 새 토큰을 저장 (다른 기기 세션은 유지)
        refreshTokenStore.delete(userId, clientRefreshToken)
        refreshTokenStore.save(userId, newRefreshToken)

        // 8. 쿠키에 새 토큰 설정
        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            newAccessToken,
        )
        cookieHelper.addAuthCookie(
            request,
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            newRefreshToken,
        )
    }
}
