package com.bara.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("wide-event")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.requestURI.startsWith("/actuator")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startTime = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        MDC.put("request_id", requestId)
        MDC.put("method", request.method)
        MDC.put("path", request.requestURI)

        var thrown: Exception? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            thrown = e
            throw e
        } finally {
            val durationMs = System.currentTimeMillis() - startTime
            val statusCode = if (thrown != null) 500 else response.status

            // WideEvent에 쌓인 비즈니스 컨텍스트를 MDC로 복사
            WideEvent.getAll().forEach { (key, value) ->
                if (value != null) MDC.put(key, value.toString())
            }

            MDC.put("status_code", statusCode.toString())
            MDC.put("duration_ms", durationMs.toString())

            if (thrown != null) {
                MDC.put("error_type", thrown.javaClass.simpleName)
                MDC.put("error_message", thrown.message ?: "")
                log.error("request completed with error")
            } else if (statusCode in 400..499) {
                log.warn("request completed with client error")
            } else {
                log.info("request completed")
            }

            // 정리
            MDC.remove("request_id")
            MDC.remove("method")
            MDC.remove("path")
            MDC.remove("status_code")
            MDC.remove("duration_ms")
            MDC.remove("error_type")
            MDC.remove("error_message")
            WideEvent.getAll().keys.forEach { MDC.remove(it) }
            WideEvent.clear()
        }
    }
}
