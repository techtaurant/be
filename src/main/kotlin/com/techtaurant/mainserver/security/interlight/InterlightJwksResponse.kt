package com.techtaurant.mainserver.security.interlight

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "표준 공개 JWK 집합. 개인키와 인증 토큰은 포함하지 않습니다")
data class InterlightJwksResponse(
    @field:Schema(description = "현재 및 이전 검증용 RSA 공개키", requiredMode = Schema.RequiredMode.REQUIRED)
    val keys: List<InterlightPublicJwk>,
)

data class InterlightPublicJwk(
    @field:Schema(description = "공개키 종류 RSA", requiredMode = Schema.RequiredMode.REQUIRED)
    val kty: String,
    @field:Schema(description = "서명 검증 용도 sig", requiredMode = Schema.RequiredMode.REQUIRED)
    val use: String,
    @field:Schema(description = "고정 서명 알고리즘 RS256", requiredMode = Schema.RequiredMode.REQUIRED)
    val alg: String,
    @field:Schema(description = "중복되지 않는 공개키 식별자", requiredMode = Schema.RequiredMode.REQUIRED)
    val kid: String,
    @field:Schema(description = "Base64url RSA modulus", requiredMode = Schema.RequiredMode.REQUIRED)
    val n: String,
    @field:Schema(description = "Base64url RSA public exponent", requiredMode = Schema.RequiredMode.REQUIRED)
    val e: String,
)
