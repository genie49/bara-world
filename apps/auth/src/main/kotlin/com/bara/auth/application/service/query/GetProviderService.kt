package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.GetProviderQuery
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class GetProviderService(
    private val providerRepository: ProviderRepository,
) : GetProviderQuery {
    override fun getByUserId(userId: String): Provider? {
        WideEvent.put("user_id", userId)
        val provider = providerRepository.findByUserId(userId)
        if (provider != null) {
            WideEvent.put("provider_id", provider.id)
            WideEvent.put("provider_status", provider.status.name)
            WideEvent.put("outcome", "provider_found")
            WideEvent.message("Provider 조회 성공")
        } else {
            WideEvent.put("outcome", "provider_not_found")
            WideEvent.message("Provider 미등록 사용자 조회")
        }
        return provider
    }
}
