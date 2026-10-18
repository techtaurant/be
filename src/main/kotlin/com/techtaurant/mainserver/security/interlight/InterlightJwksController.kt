package com.techtaurant.mainserver.security.interlight

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class InterlightJwksController(private val issuer: InterlightJwtIssuer) : InterlightJwksControllerDocs {
    @GetMapping("/open-api/interlight/jwks")
    override fun jwks(): InterlightJwksResponse = issuer.jwks()
}
