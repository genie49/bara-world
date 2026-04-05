package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class UserMongoRepositoryTest {

    private val dataRepo = mockk<UserMongoDataRepository>()
    private val repo = UserMongoRepository(dataRepo)

    private val user = User(
        id = "user-1",
        googleId = "g-1",
        email = "a@b.com",
        name = "Alice",
        role = User.Role.USER,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `findByGoogleId는 존재하면 도메인 User를 반환한다`() {
        every { dataRepo.findByGoogleId("g-1") } returns UserDocument.fromDomain(user)
        val result = repo.findByGoogleId("g-1")
        assertEquals(user, result)
    }

    @Test
    fun `findByGoogleId는 없으면 null을 반환한다`() {
        every { dataRepo.findByGoogleId("missing") } returns null
        assertNull(repo.findByGoogleId("missing"))
    }

    @Test
    fun `save는 Document로 변환 후 저장하고 도메인으로 되돌려준다`() {
        val captured = slot<UserDocument>()
        every { dataRepo.save(capture(captured)) } answers { captured.captured }
        val result = repo.save(user)
        assertEquals(user, result)
        assertEquals("user-1", captured.captured.id)
        assertEquals("USER", captured.captured.role)
        verify(exactly = 1) { dataRepo.save(any()) }
    }
}
