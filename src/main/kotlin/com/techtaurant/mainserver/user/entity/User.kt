package com.techtaurant.mainserver.user.entity

import com.techtaurant.mainserver.common.base.EntityBase
import com.techtaurant.mainserver.security.enums.OAuthProvider
import com.techtaurant.mainserver.user.enums.UserRole
import java.util.UUID

class User(
    var name: String,
    var email: String,
    var provider: OAuthProvider,
    var identifier: String,
    var role: UserRole,
    var profileImageUrl: String,
    var serviceProfileImageAttachmentId: UUID? = null,
) : EntityBase() {
    fun getProfileImageSource(): UserProfileImageSource =
        serviceProfileImageAttachmentId?.let(UserProfileImageSource::ServiceAttachment)
            ?: UserProfileImageSource.Url(profileImageUrl)

    fun getFallbackProfileImageUrl(): String = profileImageUrl
}
