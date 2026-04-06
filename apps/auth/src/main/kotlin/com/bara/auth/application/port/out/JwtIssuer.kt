package com.bara.auth.application.port.out

import com.bara.auth.domain.model.User

interface JwtIssuer {
    fun issue(user: User): String
}
