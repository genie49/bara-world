package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.Agent

interface ListAgentsQuery {
    fun listAll(): List<Agent>
}
