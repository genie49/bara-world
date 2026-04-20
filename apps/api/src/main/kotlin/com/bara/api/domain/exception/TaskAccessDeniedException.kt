package com.bara.api.domain.exception

class TaskAccessDeniedException(taskId: String) : A2AException(
    code = A2AErrorCodes.TASK_ACCESS_DENIED,
    message = "Task access denied: $taskId",
)
