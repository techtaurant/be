package com.techtaurant.mainserver.security.service

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.security.cache.TokenCachePort
import com.techtaurant.mainserver.security.dto.DevTestLoginRequest
import com.techtaurant.mainserver.security.dto.DevTestLoginResponse
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.security.helper.CookieHelper
import com.techtaurant.mainserver.security.jwt.JwtConstants
import com.techtaurant.mainserver.security.jwt.JwtProperties
import com.techtaurant.mainserver.security.jwt.JwtTokenProvider
import com.techtaurant.mainserver.user.application.UserUniqueNameService
import com.techtaurant.mainserver.user.entity.User
import com.techtaurant.mainserver.user.infrastructure.out.UserRepository
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 개발 환경 전용 테스트 인증 서비스
 *
 * 테스트 사용자를 조회하거나 생성하고 JWT 토큰을 쿠키로 발급한다.
 * prod 프로파일과 app.environment=prod가 아닌 환경에서만 빈이 등록된다.
 */
@Service
@Profile("!prod")
@ConditionalOnExpression("!'\${app.environment:dev}'.trim().equalsIgnoreCase('prod')")
class DevTestAuthService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val jwtProperties: JwtProperties,
    private val cookieHelper: CookieHelper,
    private val tokenCacheManager: TokenCachePort,
    private val userUniqueNameService: UserUniqueNameService,
) {
    companion object {
        private const val DEV_PASSWORD_HASH =
            "\$2b\$10\$HEy96.lKMAZS.qUQRbkcMOSeqqvR3pcZyIgPNzIT0kZuX/T7BgqLi"
        private val PASSWORD_ENCODER = BCryptPasswordEncoder()
    }

    /**
     * 테스트 사용자 인증 및 JWT 토큰 발급
     *
     * @param request 테스트 로그인 요청 (identifier + password)
     * @param response JWT 토큰 쿠키를 설정할 HTTP 응답
     * @return 발급된 JWT 토큰 정보
     * @throws ApiException password가 일치하지 않는 경우
     */
    @Transactional
    fun execute(
        request: DevTestLoginRequest,
        response: HttpServletResponse,
    ): DevTestLoginResponse {
        validatePassword(request.password)

        val user = findOrCreateTestUser(request)
        val userId = user.id ?: throw ApiException(DefaultStatus.SERVER_ERROR, "테스트 사용자 ID가 없습니다")

        val accessToken = jwtTokenProvider.createAccessToken(userId, user.role)
        val refreshToken = jwtTokenProvider.createRefreshToken(userId)

        tokenCacheManager.saveRefreshToken(userId.toString(), refreshToken)

        cookieHelper.addCookie(
            response,
            JwtConstants.ACCESS_TOKEN_COOKIE,
            accessToken,
            (jwtProperties.accessTokenExpireMs / 1000).toInt(),
        )
        cookieHelper.addCookie(
            response,
            JwtConstants.REFRESH_TOKEN_COOKIE,
            refreshToken,
            (jwtProperties.refreshTokenExpireMs / 1000).toInt(),
        )

        return DevTestLoginResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }

    private fun validatePassword(password: String) {
        if (!PASSWORD_ENCODER.matches(password, DEV_PASSWORD_HASH)) {
            throw ApiException(DefaultStatus.BAD_REQUEST, "개발 환경 비밀번호가 일치하지 않습니다")
        }
    }

    private fun findOrCreateTestUser(request: DevTestLoginRequest): User {
        val existingUser = userRepository.findByIdentifierAndProvider(request.identifier, OAuthProvider.DEV_LOCAL)
        if (existingUser != null) {
            existingUser.role = request.role
            return existingUser
        }

        return userUniqueNameService.saveNewUser(
            User(
                name = request.identifier,
                email = "${request.identifier}@dev.local",
                provider = OAuthProvider.DEV_LOCAL,
                identifier = request.identifier,
                role = request.role,
                profileImageUrl = "",
            ),
        )
    }
}
