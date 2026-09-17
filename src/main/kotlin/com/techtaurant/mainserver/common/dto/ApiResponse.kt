package com.techtaurant.mainserver.common.dto

import com.techtaurant.mainserver.common.status.DefaultStatus
import com.techtaurant.mainserver.common.status.StatusIfs
import org.springframework.http.HttpStatus

class ApiResponse<T>(val status: Int, val data: T?, val message: String) {
    companion object {
        fun <T> ok(): ApiResponse<T> {
            return ApiResponse(HttpStatus.OK.value(), null, DefaultStatus.OK.getDescription())
        }

        fun <T> ok(data: T): ApiResponse<T> {
            return ApiResponse(HttpStatus.OK.value(), data, DefaultStatus.OK.getDescription())
        }

        fun <T> created(data: T): ApiResponse<T> {
            return ApiResponse(HttpStatus.CREATED.value(), data, DefaultStatus.CREATED.getDescription())
        }

        fun <T> error(
            status: StatusIfs,
            message: String,
        ): ApiResponse<T?> {
            return ApiResponse(status.getCustomStatusCode(), null, message)
        }

        fun <T> error(
            status: StatusIfs,
            message: String,
            data: T?,
        ): ApiResponse<T> {
            return ApiResponse(status.getCustomStatusCode(), data, message)
        }
    }
}
