package com.bara.auth.application.port.`in`.query

import com.bara.auth.domain.model.Provider

interface GetProviderQuery {
    fun getByUserId(userId: String): Provider?
}
