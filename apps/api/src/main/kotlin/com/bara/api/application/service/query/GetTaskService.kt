package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class GetTaskService(
    private val taskRepositoryPort: TaskRepositoryPort,
) : GetTaskQuery {

    override fun getTask(userId: String, taskId: String): A2ATaskDto {
        val task = taskRepositoryPort.findById(taskId)
        if (task == null) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_not_found")
            WideEvent.message("Task 조회 실패 - 존재하지 않음")
            throw TaskNotFoundException(taskId)
        }

        if (task.userId != userId) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_access_denied")
            WideEvent.message("Task 조회 실패 - 권한 없음")
            throw TaskAccessDeniedException(taskId)
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("user_id", userId)
        WideEvent.put("current_state", task.state.name.lowercase())
        WideEvent.put("outcome", "task_retrieved")
        WideEvent.message("Task 조회 성공")

        return A2ATaskMapper.toDto(task)
    }
}
