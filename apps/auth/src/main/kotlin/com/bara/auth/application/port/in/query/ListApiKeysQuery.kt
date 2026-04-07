package com.bara.auth.application.port.`in`.query
import com.bara.auth.domain.model.ApiKey
interface ListApiKeysQuery {
    fun listByUserId(userId: String): List<ApiKey>
}
