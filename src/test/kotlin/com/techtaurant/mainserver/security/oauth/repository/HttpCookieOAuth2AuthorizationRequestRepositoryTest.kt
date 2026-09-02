package com.techtaurant.mainserver.security.oauth.repository

import com.techtaurant.mainserver.security.helper.CookieHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

class HttpCookieOAuth2AuthorizationRequestRepositoryTest {
    private val cookieHelper: CookieHelper = mockk(relaxed = true)
    private val repository = HttpCookieOAuth2AuthorizationRequestRepository(cookieHelper)

    @Test
    @DisplayName("OAuth 시작 요청의 성공/실패 redirect-uri를 쿠키에 저장한다")
    fun `save success and failure redirect uri cookies`() {
        // given
        val request =
            MockHttpServletRequest().apply {
                addParameter("origin", "https://techtaurant.com")
                addParameter("redirect-uri", "/ko/oauth/callback?redirect=%2Fko%2Fpost%2Fwrite")
                addParameter("failure-redirect-uri", "/ko/oauth/error")
            }
        val response = MockHttpServletResponse()
        val authorizationRequest = createAuthorizationRequest()

        every { cookieHelper.addCookie(any(), any(), any(), any(), any()) } returns Unit

        // when
        repository.saveAuthorizationRequest(authorizationRequest, request, response)

        // then
        verify {
            cookieHelper.addCookie(
                request,
                response,
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_SUCCESS_REDIRECT_URI_COOKIE,
                "/ko/oauth/callback?redirect=%2Fko%2Fpost%2Fwrite",
                any(),
            )
        }
        verify {
            cookieHelper.addCookie(
                request,
                response,
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_FAILURE_REDIRECT_URI_COOKIE,
                "/ko/oauth/error",
                any(),
            )
        }
    }

    @Test
    @DisplayName("OAuth authorization request 쿠키를 지울 때 redirect-uri 쿠키도 함께 지운다")
    fun `remove redirect uri cookies`() {
        // given
        val response = MockHttpServletResponse()

        every { cookieHelper.deleteCookie(any(), any()) } returns Unit

        // when
        repository.removeOAuthAuthorizationRequestCookies(response)

        // then
        verify {
            cookieHelper.deleteCookie(
                response,
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_SUCCESS_REDIRECT_URI_COOKIE,
            )
        }
        verify {
            cookieHelper.deleteCookie(
                response,
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_FAILURE_REDIRECT_URI_COOKIE,
            )
        }
    }

    @Test
    @DisplayName("콜백에서 authorization request를 꺼낼 때 OAuth 쿠키 네 개를 함께 지운다")
    fun `remove authorization request clears cookies`() {
        // given
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val authorizationRequest = createAuthorizationRequest()
        every {
            cookieHelper.getCookie(
                request,
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE,
            )
        } returns serializeToCookieValue(authorizationRequest)

        // when
        val loaded = repository.removeAuthorizationRequest(request, response)

        // then
        assertThat(loaded?.state).isEqualTo(authorizationRequest.state)
        listOf(
            HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE,
            HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_ORIGIN_COOKIE,
            HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_SUCCESS_REDIRECT_URI_COOKIE,
            HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_FAILURE_REDIRECT_URI_COOKIE,
        ).forEach { cookieName ->
            verify { cookieHelper.deleteCookie(response, cookieName) }
        }
    }

    /**
     * 저장소가 쿠키에 실제로 싣는 형식으로 만들어야 읽기 경로가 그대로 검증된다.
     */
    private fun serializeToCookieValue(authorizationRequest: OAuth2AuthorizationRequest): String {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val savedValues = mutableListOf<String>()
        every {
            cookieHelper.addCookie(
                any(),
                any(),
                HttpCookieOAuth2AuthorizationRequestRepository.OAUTH2_AUTHORIZATION_REQUEST_COOKIE,
                capture(savedValues),
                any(),
            )
        } returns Unit
        repository.saveAuthorizationRequest(authorizationRequest, request, response)

        return savedValues.single()
    }

    private fun createAuthorizationRequest(): OAuth2AuthorizationRequest {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("client-id")
            .redirectUri("https://api.techtaurant.com/login/oauth2/code/google")
            .state("state")
            .build()
    }
}
