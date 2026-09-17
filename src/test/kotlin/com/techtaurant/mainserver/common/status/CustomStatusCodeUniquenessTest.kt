package com.techtaurant.mainserver.common.status

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("커스텀 상태 코드 유일성 테스트")
class CustomStatusCodeUniquenessTest {
    @Test
    @DisplayName("도메인 상태 enum은 서로 겹치지 않는 커스텀 상태 코드를 사용한다")
    fun customStatusCodes_shouldNotOverlapAcrossDomains() {
        val statusEnums = scanStatusEnums()

        assertThat(statusEnums)
            .describedAs("StatusIfs 구현 enum을 스캔하지 못하면 중복 검사가 무의미해진다")
            .hasSizeGreaterThanOrEqualTo(KNOWN_STATUS_ENUM_COUNT)

        val ownersByCustomStatusCode =
            statusEnums
                .flatMap { statusEnum -> statusEnum.enumConstants.toList() }
                .groupBy({ it.getCustomStatusCode() }, { describe(it) })
        val duplicatedCodes = ownersByCustomStatusCode.filterValues { owners -> owners.size > 1 }

        assertThat(duplicatedCodes)
            .describedAs("같은 커스텀 상태 코드를 여러 도메인이 사용하면 클라이언트가 에러를 구분할 수 없다")
            .isEmpty()
    }

    private fun describe(status: StatusIfs): String {
        val constant = status as Enum<*>
        return "${constant.declaringJavaClass.simpleName}.${constant.name}"
    }

    companion object {
        private const val KNOWN_STATUS_ENUM_COUNT = 10
    }
}
