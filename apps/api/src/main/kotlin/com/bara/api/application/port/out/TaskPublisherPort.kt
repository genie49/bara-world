package com.bara.api.application.port.out

interface TaskPublisherPort {
    fun publish(agentId: String, taskMessage: Map<String, Any?>)
}
