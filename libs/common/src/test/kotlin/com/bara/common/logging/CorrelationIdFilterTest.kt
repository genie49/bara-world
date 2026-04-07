package com.bara.common.logging

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorrelationIdFilterTest {

    private val filter = CorrelationIdFilter()

    @AfterEach
    fun cleanup() {
        MDC.clear()
    }

    @Test
    fun `헤더에 X-Correlation-Id가 있으면 그 값을 MDC에 세팅한다`() {
        val request = MockHttpServletRequest().apply {
            addHeader("X-Correlation-Id", "existing-corr-id")
        }
        val response = MockHttpServletResponse()
        var mdcValue: String? = null

        filter.doFilter(request, response, FilterChain { _, _ ->
            mdcValue = MDC.get("correlation_id")
        })

        assertEquals("existing-corr-id", mdcValue)
        assertEquals("existing-corr-id", response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `헤더가 없으면 UUID를 생성하여 MDC에 세팅한다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var mdcValue: String? = null

        filter.doFilter(request, response, FilterChain { _, _ ->
            mdcValue = MDC.get("correlation_id")
        })

        assertNotNull(mdcValue)
        assertTrue(mdcValue!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(mdcValue, response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `필터 완료 후 MDC에서 correlation_id가 제거된다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertNull(MDC.get("correlation_id"))
    }
}
