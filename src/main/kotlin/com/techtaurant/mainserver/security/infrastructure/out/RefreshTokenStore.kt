package com.techtaurant.mainserver.security.infrastructure.out

import java.util.UUID

/**
 * 재발급에 쓰이는 refresh token 저장소
 *
 * 서버 재시작이 로그인 상태를 지우지 않도록 토큰을 프로세스 밖에 보관합니다.
 * 원문은 남기지 않고 해시로만 대조하므로, 해싱 방식은 구현이 감춥니다.
 */
interface RefreshTokenStore {
    /**
     * 한 사용자가 여러 기기에서 로그인할 수 있으므로 기존 토큰을 지우지 않고 새 토큰을 추가합니다.
     */
    fun save(
        userId: UUID,
        refreshToken: String,
    )

    /**
     * 저장된 토큰과 일치하고 아직 만료되지 않았을 때만 참을 돌려줍니다.
     */
    fun exists(
        userId: UUID,
        refreshToken: String,
    ): Boolean

    /**
     * 재발급으로 소진했거나 로그아웃한 토큰 하나만 폐기해 다른 기기의 세션을 남깁니다.
     */
    fun delete(
        userId: UUID,
        refreshToken: String,
    )
}
