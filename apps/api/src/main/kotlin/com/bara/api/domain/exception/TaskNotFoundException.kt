package com.bara.api.domain.exception

class TaskNotFoundException(taskId: String) : A2AException(
    code = A2AErrorCodes.TASK_NOT_FOUND,
    message = "Task not found: $taskId",
)
