package com.bara.common.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WideEventTest {

    @AfterEach
    fun cleanup() {
        WideEvent.clear()
    }

    @Test
    fun `put과 getAll로 데이터를 저장하고 조회한다`() {
        WideEvent.put("user_id", "u-123")
        WideEvent.put("outcome", "success")

        val data = WideEvent.getAll()
        assertEquals("u-123", data["user_id"])
        assertEquals("success", data["outcome"])
    }

    @Test
    fun `clear하면 모든 데이터가 제거된다`() {
        WideEvent.put("user_id", "u-123")
        WideEvent.clear()

        assertTrue(WideEvent.getAll().isEmpty())
    }

    @Test
    fun `같은 키에 put하면 덮어쓴다`() {
        WideEvent.put("outcome", "pending")
        WideEvent.put("outcome", "success")

        assertEquals("success", WideEvent.getAll()["outcome"])
    }
}
