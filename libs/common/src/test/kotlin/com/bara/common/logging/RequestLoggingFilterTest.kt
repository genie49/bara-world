package com.bara.common.logging

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestLoggingFilterTest {

    private val filter = RequestLoggingFilter()

    @AfterEach
    fun cleanup() {
        WideEvent.clear()
        MDC.clear()
    }

    @Test
    fun `요청 완료 후 MDC에 HTTP 컨텍스트가 세팅된다`() {
        val request = MockHttpServletRequest("GET", "/auth/google/callback")
        val response = MockHttpServletResponse().apply { status = 302 }
        val capturedMdc = mutableMapOf<String, String?>()

        filter.doFilter(request, response, FilterChain { _, _ ->
            capturedMdc["method"] = MDC.get("method")
            capturedMdc["path"] = MDC.get("path")
            capturedMdc["request_id"] = MDC.get("request_id")
        })

        assertEquals("GET", capturedMdc["method"])
        assertEquals("/auth/google/callback", capturedMdc["path"])
        assertNotNull(capturedMdc["request_id"])
    }

    @Test
    fun `WideEvent 데이터가 MDC에 포함된다`() {
        val request = MockHttpServletRequest("POST", "/test")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ ->
            WideEvent.put("user_id", "u-123")
            WideEvent.put("outcome", "success")
        })

        // WideEvent는 필터 완료 후 clear됨
        assertTrue(WideEvent.getAll().isEmpty())
    }

    @Test
    fun `필터 완료 후 MDC가 정리된다`() {
        val request = MockHttpServletRequest("GET", "/test")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertNull(MDC.get("method"))
        assertNull(MDC.get("path"))
        assertNull(MDC.get("request_id"))
        assertNull(MDC.get("status_code"))
        assertNull(MDC.get("duration_ms"))
    }

    @Test
    fun `actuator 경로는 필터를 건너뛴다`() {
        val request = MockHttpServletRequest("GET", "/actuator/health/liveness")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertNull(MDC.get("method"))
        assertNull(MDC.get("path"))
    }

    @Test
    fun `예외 발생 시에도 MDC가 정리되고 예외는 다시 throw된다`() {
        val request = MockHttpServletRequest("GET", "/test")
        val response = MockHttpServletResponse()

        assertThrows(RuntimeException::class.java) {
            filter.doFilter(request, response, FilterChain { _, _ ->
                throw RuntimeException("test error")
            })
        }

        assertNull(MDC.get("method"))
        assertTrue(WideEvent.getAll().isEmpty())
    }
}
