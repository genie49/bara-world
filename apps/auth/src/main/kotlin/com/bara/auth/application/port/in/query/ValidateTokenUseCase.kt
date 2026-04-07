package com.bara.auth.application.port.`in`.query

import com.bara.auth.domain.model.ValidateResult

interface ValidateTokenUseCase {
    fun validate(token: String): ValidateResult
}
