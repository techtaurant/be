package com.techtaurant.mainserver.security.infrastructure.out

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.security.config.RefreshTokenWhitelistPolicy
import com.techtaurant.mainserver.security.service.RefreshTokenWhitelistService
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class DbRefreshTokenWhitelistService(
    private val refreshTokenRepository: RefreshTokenRepository,
    transactionManager: PlatformTransactionManager,
    private val policy: RefreshTokenWhitelistPolicy,
) : RefreshTokenWhitelistService {
    private val transactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun register(
        userId: UUID,
        tokenHash: String,
    ) {
        transactionTemplate.executeWithoutResult {
            requireUserLock(userId)
            deleteExcessTokens(userId, slotsRequired = 1)
            refreshTokenRepository.insert(userId, tokenHash)
        }
    }

    override fun rotate(
        userId: UUID,
        expectedHash: String,
        replacementHash: String,
    ): Boolean =
        transactionTemplate.execute {
            if (!refreshTokenRepository.lockUser(userId)) {
                return@execute false
            }
            deleteExcessTokens(userId, slotsRequired = 0)
            refreshTokenRepository.rotate(userId, expectedHash, replacementHash)
        } ?: false

    override fun revokeAll(userId: UUID): Int =
        transactionTemplate.execute {
            if (!refreshTokenRepository.lockUser(userId)) {
                return@execute 0
            }
            refreshTokenRepository.deleteAllByUserId(userId)
        } ?: 0

    private fun requireUserLock(userId: UUID) {
        if (!refreshTokenRepository.lockUser(userId)) {
            throw ApiException(DefaultStatus.SERVER_ERROR, "Refresh Token을 등록할 사용자를 찾을 수 없습니다")
        }
    }

    private fun deleteExcessTokens(
        userId: UUID,
        slotsRequired: Int,
    ) {
        val excessCount =
            refreshTokenRepository.countByUserId(userId) -
                policy.maxActiveTokensPerUser +
                slotsRequired
        if (excessCount > 0) {
            refreshTokenRepository.deleteOldestByUserId(userId, excessCount)
        }
    }
}
