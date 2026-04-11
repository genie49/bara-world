package com.bara.api.adapter.`in`.rest.a2a

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/**
 * JSON-RPC 2.0 request envelope.
 * A2A `message/send` 같은 POST 엔드포인트는 본 DTO 로 body 를 수신한다.
 *
 * `id` 는 string/number/null 을 모두 수용해야 하므로 raw [JsonNode] 로 보관한다.
 * 응답에서 동일한 id 를 echo back 할 때도 JsonNode 를 그대로 써서 타입 정보를 보존한다.
 */
data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val method: String? = null,
    val params: T? = null,
)

/**
 * JSON-RPC 2.0 response envelope. `result` 와 `error` 는 상호 배타적이다.
 * null 필드는 wire 에서 생략되도록 `@JsonInclude(NON_NULL)` 적용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val result: T? = null,
    val error: JsonRpcError? = null,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Map<String, Any?>? = null,
)
