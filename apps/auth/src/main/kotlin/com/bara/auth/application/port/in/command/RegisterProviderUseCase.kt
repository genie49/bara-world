package com.bara.auth.application.port.`in`.command
import com.bara.auth.domain.model.Provider
interface RegisterProviderUseCase {
    fun register(userId: String, name: String): Provider
}
