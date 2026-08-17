package com.techtaurant.mainserver.security.oauth.repository

import com.techtaurant.mainserver.security.helper.CookieHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        repository.removeAuthorizationRequestCookies(response)

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

    private fun createAuthorizationRequest(): OAuth2AuthorizationRequest {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("client-id")
            .redirectUri("https://api.techtaurant.com/login/oauth2/code/google")
            .state("state")
            .build()
    }
}
