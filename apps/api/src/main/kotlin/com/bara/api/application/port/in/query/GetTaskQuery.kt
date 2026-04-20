package com.bara.api.application.port.`in`.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto

interface GetTaskQuery {
    /**
     * [userId] 소유의 [taskId] Task 를 조회해 A2A wire DTO 로 변환한다.
     * @throws com.bara.api.domain.exception.TaskNotFoundException taskId 가 Mongo 에 없을 때
     * @throws com.bara.api.domain.exception.TaskAccessDeniedException 소유자가 다를 때
     */
    fun getTask(userId: String, taskId: String): A2ATaskDto
}
