package com.techtaurant.mainserver.security.oauth.repository

import com.techtaurant.mainserver.security.helper.CookieHelper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.URI
import java.util.Base64

/**
 * 쿠키 기반 OAuth2 Authorization Request 저장소.
 * STATELESS 세션 정책에서 OAuth2 플로우가 정상 동작하도록 authorization request를 쿠키에 저장한다.
 * 요청의 Origin/Referer 헤더를 쿠키에 저장하여 인증 완료 후 원래 출처로 리다이렉트할 수 있게 한다.
 */
@Component
class HttpCookieOAuth2AuthorizationRequestRepository(
    private val cookieHelper: CookieHelper,
) : AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private val logger =
        LoggerFactory.getLogger(HttpCookieOAuth2AuthorizationRequestRepository::class.java)

    companion object {
        const val OAUTH2_AUTHORIZATION_REQUEST_COOKIE = "oauth2_auth_request"
        const val OAUTH2_ORIGIN_COOKIE = "oauth2_origin"
        const val OAUTH2_SUCCESS_REDIRECT_URI_COOKIE = "oauth2_success_redirect_uri"
        const val OAUTH2_FAILURE_REDIRECT_URI_COOKIE = "oauth2_failure_redirect_uri"
        const val SUCCESS_REDIRECT_URI_PARAMETER = "redirect-uri"
        const val FAILURE_REDIRECT_URI_PARAMETER = "failure-redirect-uri"
        private const val COOKIE_EXPIRE_SECONDS = 180
    }

    override fun loadAuthorizationRequest(request: HttpServletRequest): OAuth2AuthorizationRequest? {
        val cookieValue = cookieHelper.getCookie(request, OAUTH2_AUTHORIZATION_REQUEST_COOKIE)
        val originCookie = cookieHelper.getCookie(request, OAUTH2_ORIGIN_COOKIE)
        logger.info(
            "loadAuthorizationRequest: authRequestCookiePresent={}, originCookie={}, requestURI={}",
            cookieValue != null,
            originCookie,
            request.requestURI,
        )
        val allCookieNames = request.cookies?.map { it.name } ?: emptyList()
        logger.info("loadAuthorizationRequest: allCookies={}", allCookieNames)
        return cookieValue?.let { deserialize(it) }
    }

    override fun saveAuthorizationRequest(
        authorizationRequest: OAuth2AuthorizationRequest?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (authorizationRequest == null) {
            removeAuthorizationRequestCookies(response)
            return
        }

        cookieHelper.addCookie(
            request,
            response,
            OAUTH2_AUTHORIZATION_REQUEST_COOKIE,
            serialize(authorizationRequest),
            COOKIE_EXPIRE_SECONDS,
        )

        // 요청의 Origin을 쿠키에 저장하여 인증 완료 후 원래 출처로 리다이렉트
        val origin = resolveOrigin(request)
        logger.info(
            "saveAuthorizationRequest: resolvedOrigin={}, requestURL={}, queryString={}",
            origin,
            request.requestURL,
            request.queryString,
        )
        if (origin != null) {
            cookieHelper.addCookie(
                request,
                response,
                OAUTH2_ORIGIN_COOKIE,
                origin,
                COOKIE_EXPIRE_SECONDS,
            )
            logger.info("saveAuthorizationRequest: oauth2_origin cookie set to {}", origin)
        } else {
            logger.warn("saveAuthorizationRequest: origin is null, oauth2_origin cookie NOT set")
        }

        saveRedirectUriCookie(
            request = request,
            response = response,
            parameterName = SUCCESS_REDIRECT_URI_PARAMETER,
            cookieName = OAUTH2_SUCCESS_REDIRECT_URI_COOKIE,
        )
        saveRedirectUriCookie(
            request = request,
            response = response,
            parameterName = FAILURE_REDIRECT_URI_PARAMETER,
            cookieName = OAUTH2_FAILURE_REDIRECT_URI_COOKIE,
        )
    }

    override fun removeAuthorizationRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): OAuth2AuthorizationRequest? {
        return loadAuthorizationRequest(request)
    }

    fun removeAuthorizationRequestCookies(response: HttpServletResponse) {
        cookieHelper.deleteCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE)
        cookieHelper.deleteCookie(response, OAUTH2_ORIGIN_COOKIE)
        cookieHelper.deleteCookie(response, OAUTH2_SUCCESS_REDIRECT_URI_COOKIE)
        cookieHelper.deleteCookie(response, OAUTH2_FAILURE_REDIRECT_URI_COOKIE)
    }

    private fun saveRedirectUriCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
        parameterName: String,
        cookieName: String,
    ) {
        val redirectUri = request.getParameter(parameterName)?.trim()
        if (redirectUri.isNullOrBlank()) {
            return
        }

        cookieHelper.addCookie(
            request,
            response,
            cookieName,
            redirectUri,
            COOKIE_EXPIRE_SECONDS,
        )
    }

    /**
     * 요청에서 클라이언트 origin을 추출한다.
     * 쿼리 파라미터를 우선 사용하고, 없으면 Origin 헤더, Referer 헤더 순서로 추출한다.
     */
    private fun resolveOrigin(request: HttpServletRequest): String? {
        val queryOrigin = request.getParameter("origin")
        if (!queryOrigin.isNullOrBlank()) {
            return queryOrigin
        }

        val origin = request.getHeader("Origin")
        if (!origin.isNullOrBlank() && origin != "null") {
            return origin
        }

        val referer = request.getHeader("Referer")
        if (!referer.isNullOrBlank()) {
            return try {
                val uri = URI(referer)
                val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$port"
            } catch (e: Exception) {
                logger.warn("Failed to parse Referer header: {}", referer)
                null
            }
        }

        return null
    }

    private fun serialize(authorizationRequest: OAuth2AuthorizationRequest): String {
        ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(authorizationRequest)
            }
            return Base64.getUrlEncoder().encodeToString(baos.toByteArray())
        }
    }

    private fun deserialize(cookie: String): OAuth2AuthorizationRequest? {
        return try {
            val bytes = Base64.getUrlDecoder().decode(cookie)
            ByteArrayInputStream(bytes).use { bais ->
                ObjectInputStream(bais).use { ois ->
                    ois.readObject() as OAuth2AuthorizationRequest
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to deserialize OAuth2 authorization request: {}", e.message)
            null
        }
    }
}
