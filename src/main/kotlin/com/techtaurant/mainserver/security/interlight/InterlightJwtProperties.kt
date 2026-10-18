package com.techtaurant.mainserver.security.interlight

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("interlight.jwt")
class InterlightJwtProperties(
    val enabled: Boolean = false,
    val kid: String = "",
    val privateKeyPem: String = "",
    val previousPublicKeys: List<PreviousPublicKey> = emptyList(),
) {
    class PreviousPublicKey(val kid: String, val publicKeyPem: String)
}
