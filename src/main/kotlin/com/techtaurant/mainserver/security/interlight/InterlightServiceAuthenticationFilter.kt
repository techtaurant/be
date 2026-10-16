package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
import com.techtaurant.mainserver.security.jwt.JwtStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

internal class InterlightServiceAuthenticationFilter(
    private val enabled: Boolean,
    secret: String,
    private val entryPoint: CustomAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    private val configured = secret.isNotBlank()
    private val expectedDigest = digest(secret)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeaders("Authorization").toList().singleOrNull()
        val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')
        if (!enabled || !configured || token.isNullOrBlank() || !MessageDigest.isEqual(expectedDigest, digest(token))) {
            SecurityContextHolder.clearContext()
            entryPoint.writeError(request, response, JwtStatus.AUTHENTICATION_REQUIRED)
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken(
                InterlightServicePrincipal,
                null,
                listOf(SimpleGrantedAuthority(InterlightServicePrincipal.AUTHORITY)),
            )
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

    private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
