package com.techtaurant.mainserver.link.application

import com.techtaurant.mainserver.common.exception.ApiException
import com.techtaurant.mainserver.common.status.DefaultStatus

internal fun Throwable.toLinkCrawlErrorStatusCode(): Int {
    return if (this is ApiException) {
        status.getCustomStatusCode()
    } else {
        DefaultStatus.UNKNOWN_EXCEPTION.getCustomStatusCode()
    }
}

internal fun Throwable.toLinkCrawlErrorMessage(): String {
    return if (this is ApiException) {
        detail
    } else {
        message ?: javaClass.simpleName
    }
}

internal fun Exception.toApiException(): ApiException {
    return if (this is ApiException) {
        this
    } else {
        ApiException(
            DefaultStatus.UNKNOWN_EXCEPTION,
            this,
            message ?: DefaultStatus.UNKNOWN_EXCEPTION.getDescription(),
        )
    }
}
