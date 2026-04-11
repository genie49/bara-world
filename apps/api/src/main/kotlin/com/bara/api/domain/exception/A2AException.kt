package com.bara.api.domain.exception

/**
 * A2A 프로토콜의 JSON-RPC 에러로 변환되는 도메인 예외의 베이스.
 * 각 서브클래스는 [A2AErrorCodes] 의 상수를 그대로 `code` 로 전달한다.
 * HTTP 매핑은 [com.bara.api.adapter.`in`.rest.A2AExceptionHandler] 가 담당한다.
 */
open class A2AException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * JSON-RPC Server Error 범위(-32000~-32099)에서 내부적으로 정의한 A2A 에러 코드.
 * spec §2.7 참조.
 */
object A2AErrorCodes {
    const val KAFKA_PUBLISH_FAILED = -32001
    const val AGENT_UNAVAILABLE = -32062
    const val AGENT_TIMEOUT = -32063
    const val TASK_NOT_FOUND = -32064
    const val TASK_ACCESS_DENIED = -32065
}
