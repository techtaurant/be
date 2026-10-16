package com.techtaurant.mainserver.security.interlight

import com.techtaurant.mainserver.security.handler.CustomAccessDeniedHandler
import com.techtaurant.mainserver.security.handler.CustomAuthenticationEntryPoint
import com.techtaurant.mainserver.security.jwt.JwtAuthenticationFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class InterlightServiceSecurityConfig(
    @Value("\${interlight.service-auth.enabled:false}")
    private val enabled: Boolean,
    @Value("\${interlight.service-auth.secret:}")
    private val secret: String,
    private val entryPoint: CustomAuthenticationEntryPoint,
    private val accessDeniedHandler: CustomAccessDeniedHandler,
) {
    @Bean
    @Order(1)
    fun interlightServiceSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("/internal/interlight/**")
            .csrf { it.disable() }
            .cors { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler) }
            .authorizeHttpRequests { it.anyRequest().hasAuthority(InterlightServicePrincipal.AUTHORITY) }
            .addFilterBefore(
                InterlightServiceAuthenticationFilter(enabled, secret, entryPoint),
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .build()

    @Bean
    fun jwtServletRegistration(filter: JwtAuthenticationFilter): FilterRegistrationBean<JwtAuthenticationFilter> =
        FilterRegistrationBean(filter).apply {
            // 사용자 JWT 필터는 기존 SecurityFilterChain에서만 실행해 서비스 principal 덮어쓰기를 막는다.
            isEnabled = false
        }
}
