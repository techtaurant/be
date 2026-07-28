package com.techtaurant.mainserver.security.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("RefreshTokenWhitelistPolicy")
class RefreshTokenWhitelistPolicyTest {
    @ParameterizedTest
    @ValueSource(ints = [-1, 0])
    @DisplayName("사용자당 활성 토큰 상한은 양수여야 한다")
    fun maxActiveTokensPerUser_mustBePositive(invalidLimit: Int) {
        assertThatIllegalArgumentException()
            .isThrownBy { RefreshTokenWhitelistPolicy(invalidLimit) }
            .withMessageContaining("maxActiveTokensPerUser")
    }

    @Test
    @DisplayName("양수인 활성 토큰 상한을 보존한다")
    fun maxActiveTokensPerUser_acceptsPositiveValue() {
        val policy = RefreshTokenWhitelistPolicy(5)

        assertThat(policy.maxActiveTokensPerUser).isEqualTo(5)
    }
}
