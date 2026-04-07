package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.GetProviderQuery
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import org.springframework.stereotype.Service

@Service
class GetProviderService(
    private val providerRepository: ProviderRepository,
) : GetProviderQuery {
    override fun getByUserId(userId: String): Provider? =
        providerRepository.findByUserId(userId)
}
