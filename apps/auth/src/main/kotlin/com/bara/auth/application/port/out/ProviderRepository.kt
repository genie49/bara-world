package com.bara.auth.application.port.out
import com.bara.auth.domain.model.Provider
interface ProviderRepository {
    fun save(provider: Provider): Provider
    fun findByUserId(userId: String): Provider?
    fun findById(id: String): Provider?
}
