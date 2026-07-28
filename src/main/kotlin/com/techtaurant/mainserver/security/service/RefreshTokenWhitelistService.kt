package com.techtaurant.mainserver.security.service

import java.util.UUID

/**
 * Refresh Token whitelist의 저장과 동시성 제어 계약입니다.
 *
 * 같은 사용자의 등록, 회전, 전체 폐기는 구현체 내부에서 직렬화되고 원자적으로 확정된 뒤 반환되어야 합니다.
 * 호출자는 별도 잠금이나 트랜잭션을 획득하지 않습니다.
 */
interface RefreshTokenWhitelistService {
    fun register(
        userId: UUID,
        tokenHash: String,
    )

    fun rotate(
        userId: UUID,
        expectedHash: String,
        replacementHash: String,
    ): Boolean

    fun revokeAll(userId: UUID): Int
}
