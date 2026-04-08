# Agent Registry CRUD 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API Service에 Agent 등록/조회/삭제 CRUD와 Agent Card 제공 REST 엔드포인트를 구현한다.

**Architecture:** Auth Service의 Hexagonal + CQRS 패턴을 따른다. Domain → Application (ports/services) → Adapter (REST/MongoDB) 순서로 inside-out 구현. TDD로 서비스/컨트롤러 레이어 테스트.

**Tech Stack:** Kotlin, Spring Boot 3.4.4, Spring Data MongoDB, MockK, JUnit 5

---

## File Structure

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/
│   ├── model/
│   │   ├── Agent.kt
│   │   └── AgentCard.kt
│   └── exception/
│       ├── AgentNotFoundException.kt
│       └── AgentNameAlreadyExistsException.kt
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── command/
│   │   │   │   ├── RegisterAgentUseCase.kt
│   │   │   │   ├── RegisterAgentCommand.kt
│   │   │   │   └── DeleteAgentUseCase.kt
│   │   │   └── query/
│   │   │       ├── ListAgentsQuery.kt
│   │   │       ├── GetAgentQuery.kt
│   │   │       └── GetAgentCardQuery.kt
│   │   └── out/
│   │       └── AgentRepository.kt
│   └── service/
│       ├── command/
│       │   ├── RegisterAgentService.kt
│       │   └── DeleteAgentService.kt
│       └── query/
│           ├── ListAgentsService.kt
│           ├── GetAgentService.kt
│           └── GetAgentCardService.kt
├── adapter/
│   ├── in/rest/
│   │   ├── AgentController.kt
│   │   ├── AgentDtos.kt
│   │   └── ApiExceptionHandler.kt
│   └── out/persistence/
│       ├── AgentDocument.kt
│       ├── AgentMongoRepository.kt
│       └── AgentMongoDataRepository.kt
└── config/
    (빈 상태 유지)

apps/api/src/test/kotlin/com/bara/api/
├── application/service/
│   ├── command/
│   │   ├── RegisterAgentServiceTest.kt
│   │   └── DeleteAgentServiceTest.kt
│   └── query/
│       ├── ListAgentsServiceTest.kt
│       ├── GetAgentServiceTest.kt
│       └── GetAgentCardServiceTest.kt
└── adapter/in/rest/
    └── AgentControllerTest.kt

infra/k8s/base/gateway/routes.yaml  (수정 — 라우팅 분리)
```

---

### Task 1: 도메인 모델 + 예외

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/Agent.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNotFoundException.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNameAlreadyExistsException.kt`

- [ ] **Step 1: AgentCard.kt 작성**

`apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt`:

```kotlin
package com.bara.api.domain.model

data class AgentCard(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilities,
    val skills: List<AgentSkill>,
    val iconUrl: String? = null,
) {
    data class AgentCapabilities(
        val streaming: Boolean = false,
        val pushNotifications: Boolean = false,
    )

    data class AgentSkill(
        val id: String,
        val name: String,
        val description: String,
        val tags: List<String> = emptyList(),
        val examples: List<String> = emptyList(),
    )
}
```

- [ ] **Step 2: Agent.kt 작성**

`apps/api/src/main/kotlin/com/bara/api/domain/model/Agent.kt`:

```kotlin
package com.bara.api.domain.model

import java.time.Instant
import java.util.UUID

data class Agent(
    val id: String,
    val name: String,
    val providerId: String,
    val agentCard: AgentCard,
    val createdAt: Instant,
) {
    companion object {
        fun create(name: String, providerId: String, agentCard: AgentCard): Agent =
            Agent(
                id = UUID.randomUUID().toString(),
                name = name,
                providerId = providerId,
                agentCard = agentCard,
                createdAt = Instant.now(),
            )
    }
}
```

- [ ] **Step 3: 도메인 예외 작성**

`apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNotFoundException.kt`:

```kotlin
package com.bara.api.domain.exception

class AgentNotFoundException : RuntimeException("Agent not found")
```

`apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNameAlreadyExistsException.kt`:

```kotlin
package com.bara.api.domain.exception

class AgentNameAlreadyExistsException : RuntimeException("Agent name already exists for this provider")
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: .gitkeep 정리 + 커밋**

domain/model/.gitkeep과 domain/exception/.gitkeep은 이제 실제 파일이 있으므로 삭제한다.

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/domain/model/.gitkeep
rm -f apps/api/src/main/kotlin/com/bara/api/domain/exception/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/domain/
git commit -m "feat(api): add Agent and AgentCard domain models"
```

---

### Task 2: Output Port + Input Ports

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRepository.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentUseCase.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentCommand.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/DeleteAgentUseCase.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/ListAgentsQuery.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetAgentQuery.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetAgentCardQuery.kt`

- [ ] **Step 1: AgentRepository (output port) 작성**

`apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRepository.kt`:

```kotlin
package com.bara.api.application.port.out

import com.bara.api.domain.model.Agent

interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findAll(): List<Agent>
    fun findByProviderIdAndName(providerId: String, name: String): Agent?
    fun deleteById(id: String)
}
```

- [ ] **Step 2: Command use cases 작성**

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentCommand.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

import com.bara.api.domain.model.AgentCard

data class RegisterAgentCommand(
    val name: String,
    val agentCard: AgentCard,
)
```

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentUseCase.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

import com.bara.api.domain.model.Agent

interface RegisterAgentUseCase {
    fun register(providerId: String, command: RegisterAgentCommand): Agent
}
```

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/DeleteAgentUseCase.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

interface DeleteAgentUseCase {
    fun delete(providerId: String, agentId: String)
}
```

- [ ] **Step 3: Query use cases 작성**

`apps/api/src/main/kotlin/com/bara/api/application/port/in/query/ListAgentsQuery.kt`:

```kotlin
package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.Agent

interface ListAgentsQuery {
    fun listAll(): List<Agent>
}
```

`apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetAgentQuery.kt`:

```kotlin
package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.Agent

interface GetAgentQuery {
    fun getById(agentId: String): Agent
}
```

`apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetAgentCardQuery.kt`:

```kotlin
package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.AgentCard

interface GetAgentCardQuery {
    fun getCardById(agentId: String): AgentCard
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: .gitkeep 정리 + 커밋**

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/application/port/in/command/.gitkeep
rm -f apps/api/src/main/kotlin/com/bara/api/application/port/in/query/.gitkeep
rm -f apps/api/src/main/kotlin/com/bara/api/application/port/out/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/application/
git commit -m "feat(api): add Agent ports (use cases and repository)"
```

---

### Task 3: Command Services + 테스트

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/RegisterAgentService.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/DeleteAgentService.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/command/RegisterAgentServiceTest.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/command/DeleteAgentServiceTest.kt`

- [ ] **Step 1: RegisterAgentService 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/application/service/command/RegisterAgentServiceTest.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RegisterAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = RegisterAgentService(agentRepository)

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
        defaultInputModes = listOf("text/plain"),
        defaultOutputModes = listOf("text/plain"),
        capabilities = AgentCard.AgentCapabilities(),
        skills = listOf(
            AgentCard.AgentSkill(id = "s1", name = "Skill 1", description = "A skill")
        ),
    )

    @Test
    fun `신규 Agent를 등록하면 저장된다`() {
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByProviderIdAndName("p-1", "My Agent") } returns null
        every { agentRepository.save(any()) } answers { firstArg() }

        val result = service.register("p-1", command)

        assertEquals("My Agent", result.name)
        assertEquals("p-1", result.providerId)
        assertEquals(agentCard, result.agentCard)
        verify { agentRepository.save(any()) }
    }

    @Test
    fun `동일 Provider에 같은 이름의 Agent가 있으면 예외 발생`() {
        val existing = Agent.create(name = "My Agent", providerId = "p-1", agentCard = agentCard)
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByProviderIdAndName("p-1", "My Agent") } returns existing

        assertThrows<AgentNameAlreadyExistsException> {
            service.register("p-1", command)
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:api:test --no-daemon --tests "*.RegisterAgentServiceTest"`

Expected: FAIL — `RegisterAgentService` 클래스가 없음

- [ ] **Step 3: RegisterAgentService 구현**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/RegisterAgentService.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.model.Agent
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegisterAgentService(
    private val agentRepository: AgentRepository,
) : RegisterAgentUseCase {

    override fun register(providerId: String, command: RegisterAgentCommand): Agent {
        agentRepository.findByProviderIdAndName(providerId, command.name)?.let {
            throw AgentNameAlreadyExistsException()
        }

        val agent = Agent.create(
            name = command.name,
            providerId = providerId,
            agentCard = command.agentCard,
        )

        val saved = agentRepository.save(agent)

        WideEvent.put("agent_id", saved.id)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_registered")
        WideEvent.message("Agent 등록 완료")

        return saved
    }
}
```

- [ ] **Step 4: RegisterAgentService 테스트 통과 확인**

Run: `./gradlew :apps:api:test --no-daemon --tests "*.RegisterAgentServiceTest"`

Expected: `BUILD SUCCESSFUL` — 2 tests passed

- [ ] **Step 5: DeleteAgentService 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/application/service/command/DeleteAgentServiceTest.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeleteAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = DeleteAgentService(agentRepository)

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
        defaultInputModes = listOf("text/plain"),
        defaultOutputModes = listOf("text/plain"),
        capabilities = AgentCard.AgentCapabilities(),
        skills = listOf(
            AgentCard.AgentSkill(id = "s1", name = "Skill 1", description = "A skill")
        ),
    )

    @Test
    fun `본인 소유 Agent를 삭제하면 성공한다`() {
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        justRun { agentRepository.deleteById(agent.id) }

        service.delete("p-1", agent.id)

        verify { agentRepository.deleteById(agent.id) }
    }

    @Test
    fun `존재하지 않는 Agent 삭제 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.delete("p-1", "not-exist")
        }
    }

    @Test
    fun `다른 Provider의 Agent 삭제 시 예외 발생`() {
        val agent = Agent.create(name = "My Agent", providerId = "p-other", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent

        assertThrows<AgentNotFoundException> {
            service.delete("p-1", agent.id)
        }
    }
}
```

- [ ] **Step 6: DeleteAgentService 구현**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/DeleteAgentService.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class DeleteAgentService(
    private val agentRepository: AgentRepository,
) : DeleteAgentUseCase {

    override fun delete(providerId: String, agentId: String) {
        val agent = agentRepository.findById(agentId)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentNotFoundException()
        }

        agentRepository.deleteById(agentId)

        WideEvent.put("agent_id", agentId)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_deleted")
        WideEvent.message("Agent 삭제 완료")
    }
}
```

- [ ] **Step 7: 전체 command 테스트 통과 확인**

Run: `./gradlew :apps:api:test --no-daemon --tests "*.command.*"`

Expected: `BUILD SUCCESSFUL` — 5 tests passed

- [ ] **Step 8: .gitkeep 정리 + 커밋**

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/application/service/command/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/application/service/command/ apps/api/src/test/
git commit -m "feat(api): add RegisterAgent and DeleteAgent services with tests"
```

---

### Task 4: Query Services + 테스트

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/query/ListAgentsService.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/query/GetAgentService.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/query/GetAgentCardService.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/query/ListAgentsServiceTest.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/query/GetAgentServiceTest.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/query/GetAgentCardServiceTest.kt`

- [ ] **Step 1: Query 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/application/service/query/ListAgentsServiceTest.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListAgentsServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = ListAgentsService(agentRepository)

    @Test
    fun `전체 Agent 목록을 반환한다`() {
        val card = AgentCard(
            name = "A", description = "d", version = "1.0.0",
            defaultInputModes = listOf("text/plain"), defaultOutputModes = listOf("text/plain"),
            capabilities = AgentCard.AgentCapabilities(), skills = emptyList(),
        )
        val agents = listOf(
            Agent.create(name = "Agent 1", providerId = "p-1", agentCard = card),
            Agent.create(name = "Agent 2", providerId = "p-2", agentCard = card),
        )
        every { agentRepository.findAll() } returns agents

        val result = service.listAll()

        assertEquals(2, result.size)
    }

    @Test
    fun `Agent가 없으면 빈 목록 반환`() {
        every { agentRepository.findAll() } returns emptyList()

        val result = service.listAll()

        assertEquals(0, result.size)
    }
}
```

`apps/api/src/test/kotlin/com/bara/api/application/service/query/GetAgentServiceTest.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class GetAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = GetAgentService(agentRepository)

    @Test
    fun `존재하는 Agent를 조회하면 반환한다`() {
        val card = AgentCard(
            name = "A", description = "d", version = "1.0.0",
            defaultInputModes = listOf("text/plain"), defaultOutputModes = listOf("text/plain"),
            capabilities = AgentCard.AgentCapabilities(), skills = emptyList(),
        )
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = card)
        every { agentRepository.findById(agent.id) } returns agent

        val result = service.getById(agent.id)

        assertEquals("My Agent", result.name)
    }

    @Test
    fun `존재하지 않는 Agent 조회 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.getById("not-exist")
        }
    }
}
```

`apps/api/src/test/kotlin/com/bara/api/application/service/query/GetAgentCardServiceTest.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class GetAgentCardServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = GetAgentCardService(agentRepository)

    @Test
    fun `존재하는 Agent의 AgentCard를 반환한다`() {
        val card = AgentCard(
            name = "Test Agent", description = "d", version = "1.0.0",
            defaultInputModes = listOf("text/plain"), defaultOutputModes = listOf("text/plain"),
            capabilities = AgentCard.AgentCapabilities(streaming = true),
            skills = listOf(AgentCard.AgentSkill(id = "s1", name = "Skill", description = "desc")),
        )
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = card)
        every { agentRepository.findById(agent.id) } returns agent

        val result = service.getCardById(agent.id)

        assertEquals("Test Agent", result.name)
        assertEquals(true, result.capabilities.streaming)
        assertEquals(1, result.skills.size)
    }

    @Test
    fun `존재하지 않는 Agent의 Card 조회 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.getCardById("not-exist")
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:api:test --no-daemon --tests "*.query.*"`

Expected: FAIL — Service 클래스들이 없음

- [ ] **Step 3: Query Services 구현**

`apps/api/src/main/kotlin/com/bara/api/application/service/query/ListAgentsService.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Service

@Service
class ListAgentsService(
    private val agentRepository: AgentRepository,
) : ListAgentsQuery {

    override fun listAll(): List<Agent> = agentRepository.findAll()
}
```

`apps/api/src/main/kotlin/com/bara/api/application/service/query/GetAgentService.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Service

@Service
class GetAgentService(
    private val agentRepository: AgentRepository,
) : GetAgentQuery {

    override fun getById(agentId: String): Agent =
        agentRepository.findById(agentId) ?: throw AgentNotFoundException()
}
```

`apps/api/src/main/kotlin/com/bara/api/application/service/query/GetAgentCardService.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.AgentCard
import org.springframework.stereotype.Service

@Service
class GetAgentCardService(
    private val agentRepository: AgentRepository,
) : GetAgentCardQuery {

    override fun getCardById(agentId: String): AgentCard {
        val agent = agentRepository.findById(agentId)
            ?: throw AgentNotFoundException()
        return agent.agentCard
    }
}
```

- [ ] **Step 4: Query 테스트 통과 확인**

Run: `./gradlew :apps:api:test --no-daemon --tests "*.query.*"`

Expected: `BUILD SUCCESSFUL` — 6 tests passed

- [ ] **Step 5: .gitkeep 정리 + 커밋**

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/application/service/query/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/application/service/query/ apps/api/src/test/
git commit -m "feat(api): add Agent query services with tests"
```

---

### Task 5: MongoDB Persistence Adapter

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentDocument.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoDataRepository.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoRepository.kt`

- [ ] **Step 1: AgentDocument + 내장 Document 클래스 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentDocument.kt`:

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "agents")
@CompoundIndex(name = "provider_name_idx", def = "{'providerId': 1, 'name': 1}", unique = true)
data class AgentDocument(
    @Id val id: String,
    val name: String,
    @Indexed val providerId: String,
    val agentCard: AgentCardDocument,
    val createdAt: java.time.Instant,
) {
    fun toDomain(): Agent = Agent(
        id = id,
        name = name,
        providerId = providerId,
        agentCard = agentCard.toDomain(),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(a: Agent): AgentDocument = AgentDocument(
            id = a.id,
            name = a.name,
            providerId = a.providerId,
            agentCard = AgentCardDocument.fromDomain(a.agentCard),
            createdAt = a.createdAt,
        )
    }
}

data class AgentCardDocument(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesDocument,
    val skills: List<AgentSkillDocument>,
    val iconUrl: String?,
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
        defaultInputModes = defaultInputModes,
        defaultOutputModes = defaultOutputModes,
        capabilities = AgentCard.AgentCapabilities(
            streaming = capabilities.streaming,
            pushNotifications = capabilities.pushNotifications,
        ),
        skills = skills.map { it.toDomain() },
        iconUrl = iconUrl,
    )

    companion object {
        fun fromDomain(c: AgentCard): AgentCardDocument = AgentCardDocument(
            name = c.name,
            description = c.description,
            version = c.version,
            defaultInputModes = c.defaultInputModes,
            defaultOutputModes = c.defaultOutputModes,
            capabilities = AgentCapabilitiesDocument(
                streaming = c.capabilities.streaming,
                pushNotifications = c.capabilities.pushNotifications,
            ),
            skills = c.skills.map { AgentSkillDocument.fromDomain(it) },
            iconUrl = c.iconUrl,
        )
    }
}

data class AgentCapabilitiesDocument(
    val streaming: Boolean,
    val pushNotifications: Boolean,
)

data class AgentSkillDocument(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String>,
) {
    fun toDomain(): AgentCard.AgentSkill = AgentCard.AgentSkill(
        id = id, name = name, description = description,
        tags = tags, examples = examples,
    )

    companion object {
        fun fromDomain(s: AgentCard.AgentSkill): AgentSkillDocument = AgentSkillDocument(
            id = s.id, name = s.name, description = s.description,
            tags = s.tags, examples = s.examples,
        )
    }
}
```

- [ ] **Step 2: Spring Data interface 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoDataRepository.kt`:

```kotlin
package com.bara.api.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface AgentMongoDataRepository : MongoRepository<AgentDocument, String> {
    fun findByProviderIdAndName(providerId: String, name: String): AgentDocument?
}
```

- [ ] **Step 3: AgentMongoRepository (port 구현) 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoRepository.kt`:

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Repository

@Repository
class AgentMongoRepository(
    private val dataRepository: AgentMongoDataRepository,
) : AgentRepository {

    override fun save(agent: Agent): Agent =
        dataRepository.save(AgentDocument.fromDomain(agent)).toDomain()

    override fun findById(id: String): Agent? =
        dataRepository.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<Agent> =
        dataRepository.findAll().map { it.toDomain() }

    override fun findByProviderIdAndName(providerId: String, name: String): Agent? =
        dataRepository.findByProviderIdAndName(providerId, name)?.toDomain()

    override fun deleteById(id: String) =
        dataRepository.deleteById(id)
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: .gitkeep 정리 + 커밋**

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/
git commit -m "feat(api): add Agent MongoDB persistence adapter"
```

---

### Task 6: REST Controller + Exception Handler + 테스트

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt`

- [ ] **Step 1: DTO 클래스 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard

// ── Request ──

data class RegisterAgentRequest(
    val name: String,
    val agentCard: AgentCardRequest,
) {
    fun toCommand(): RegisterAgentCommand = RegisterAgentCommand(
        name = name,
        agentCard = agentCard.toDomain(),
    )
}

data class AgentCardRequest(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesRequest = AgentCapabilitiesRequest(),
    val skills: List<AgentSkillRequest>,
    val iconUrl: String? = null,
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
        defaultInputModes = defaultInputModes,
        defaultOutputModes = defaultOutputModes,
        capabilities = AgentCard.AgentCapabilities(
            streaming = capabilities.streaming,
            pushNotifications = capabilities.pushNotifications,
        ),
        skills = skills.map {
            AgentCard.AgentSkill(
                id = it.id, name = it.name, description = it.description,
                tags = it.tags, examples = it.examples,
            )
        },
        iconUrl = iconUrl,
    )
}

data class AgentCapabilitiesRequest(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
)

data class AgentSkillRequest(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

// ── Response ──

data class AgentResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent): AgentResponse = AgentResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            createdAt = agent.createdAt.toString(),
        )
    }
}

data class AgentDetailResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val agentCard: AgentCard,
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent): AgentDetailResponse = AgentDetailResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            agentCard = agent.agentCard,
            createdAt = agent.createdAt.toString(),
        )
    }
}

data class AgentListResponse(val agents: List<AgentResponse>)

data class ErrorResponse(val error: String, val message: String)
```

- [ ] **Step 2: ApiExceptionHandler 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AgentNotFoundException::class)
    fun handleAgentNotFound(ex: AgentNotFoundException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNotFoundException")
        WideEvent.put("outcome", "agent_not_found")
        WideEvent.message("Agent 조회 실패")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("agent_not_found", ex.message ?: "Agent not found"))
    }

    @ExceptionHandler(AgentNameAlreadyExistsException::class)
    fun handleAgentNameAlreadyExists(ex: AgentNameAlreadyExistsException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNameAlreadyExistsException")
        WideEvent.put("outcome", "agent_name_already_exists")
        WideEvent.message("Agent 이름 중복")
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("agent_name_already_exists", ex.message ?: "Agent name already exists"))
    }
}
```

- [ ] **Step 3: AgentController 작성**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
) {

    @PostMapping
    fun register(
        @RequestHeader("X-Provider-Id") providerId: String,
        @RequestBody request: RegisterAgentRequest,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = registerAgentUseCase.register(providerId, request.toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentDetailResponse.from(agent))
    }

    @GetMapping
    fun list(): ResponseEntity<AgentListResponse> {
        val agents = listAgentsQuery.listAll()
        return ResponseEntity.ok(AgentListResponse(agents.map { AgentResponse.from(it) }))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<AgentDetailResponse> {
        val agent = getAgentQuery.getById(id)
        return ResponseEntity.ok(AgentDetailResponse.from(agent))
    }

    @GetMapping("/{id}/.well-known/agent.json")
    fun getAgentCard(@PathVariable id: String): ResponseEntity<Any> {
        val card = getAgentCardQuery.getCardById(id)
        return ResponseEntity.ok(card)
    }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestHeader("X-Provider-Id") providerId: String,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        deleteAgentUseCase.delete(providerId, id)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 4: Controller 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(controllers = [AgentController::class])
@Import(ApiExceptionHandler::class)
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
    ]
)
class AgentControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var registerAgentUseCase: RegisterAgentUseCase

    @MockkBean
    lateinit var deleteAgentUseCase: DeleteAgentUseCase

    @MockkBean
    lateinit var listAgentsQuery: ListAgentsQuery

    @MockkBean
    lateinit var getAgentQuery: GetAgentQuery

    @MockkBean
    lateinit var getAgentCardQuery: GetAgentCardQuery

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
        defaultInputModes = listOf("text/plain"),
        defaultOutputModes = listOf("text/plain"),
        capabilities = AgentCard.AgentCapabilities(),
        skills = listOf(AgentCard.AgentSkill(id = "s1", name = "Skill 1", description = "A skill")),
    )

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private val agent = Agent(
        id = "a-1", name = "My Agent", providerId = "p-1",
        agentCard = agentCard, createdAt = now,
    )

    @Test
    fun `POST agents 성공 시 201과 Agent 정보 반환`() {
        every { registerAgentUseCase.register("p-1", any<RegisterAgentCommand>()) } returns agent

        mockMvc.post("/agents") {
            header("X-Provider-Id", "p-1")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "name": "My Agent",
                    "agentCard": {
                        "name": "Test Agent",
                        "description": "A test agent",
                        "version": "1.0.0",
                        "defaultInputModes": ["text/plain"],
                        "defaultOutputModes": ["text/plain"],
                        "capabilities": {"streaming": false, "pushNotifications": false},
                        "skills": [{"id": "s1", "name": "Skill 1", "description": "A skill"}]
                    }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("a-1") }
            jsonPath("$.name") { value("My Agent") }
            jsonPath("$.providerId") { value("p-1") }
            jsonPath("$.agentCard.name") { value("Test Agent") }
        }
    }

    @Test
    fun `POST agents 이름 중복 시 409 반환`() {
        every { registerAgentUseCase.register("p-1", any()) } throws AgentNameAlreadyExistsException()

        mockMvc.post("/agents") {
            header("X-Provider-Id", "p-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"dup","agentCard":{"name":"A","description":"d","version":"1","defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"capabilities":{},"skills":[]}}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("agent_name_already_exists") }
        }
    }

    @Test
    fun `GET agents 목록 반환`() {
        every { listAgentsQuery.listAll() } returns listOf(agent)

        mockMvc.get("/agents").andExpect {
            status { isOk() }
            jsonPath("$.agents.length()") { value(1) }
            jsonPath("$.agents[0].id") { value("a-1") }
            jsonPath("$.agents[0].name") { value("My Agent") }
        }
    }

    @Test
    fun `GET agents by id 성공`() {
        every { getAgentQuery.getById("a-1") } returns agent

        mockMvc.get("/agents/a-1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value("a-1") }
            jsonPath("$.agentCard.name") { value("Test Agent") }
        }
    }

    @Test
    fun `GET agents by id 미존재 시 404`() {
        every { getAgentQuery.getById("not-exist") } throws AgentNotFoundException()

        mockMvc.get("/agents/not-exist").andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_found") }
        }
    }

    @Test
    fun `GET agent card 성공`() {
        every { getAgentCardQuery.getCardById("a-1") } returns agentCard

        mockMvc.get("/agents/a-1/.well-known/agent.json").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Test Agent") }
            jsonPath("$.skills.length()") { value(1) }
        }
    }

    @Test
    fun `DELETE agents 성공 시 204`() {
        justRun { deleteAgentUseCase.delete("p-1", "a-1") }

        mockMvc.delete("/agents/a-1") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `DELETE 미존재 Agent 시 404`() {
        every { deleteAgentUseCase.delete("p-1", "not-exist") } throws AgentNotFoundException()

        mockMvc.delete("/agents/not-exist") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_found") }
        }
    }
}
```

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew :apps:api:test --no-daemon`

Expected: `BUILD SUCCESSFUL` — 모든 테스트 통과

- [ ] **Step 6: .gitkeep 정리 + 커밋**

```bash
rm -f apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/.gitkeep
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ apps/api/src/test/
git commit -m "feat(api): add Agent REST controller and exception handler with tests"
```

---

### Task 7: Traefik 라우팅 변경

**Files:**
- Modify: `infra/k8s/base/gateway/routes.yaml`

- [ ] **Step 1: routes.yaml에서 api-protected를 read/write로 분리**

`infra/k8s/base/gateway/routes.yaml`에서 기존 `api-protected` IngressRoute를 삭제하고 `api-read` + `api-write`로 교체한다.

기존:
```yaml
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-protected
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core`)
      kind: Rule
      middlewares:
        - name: auth-forward
          namespace: core
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
```

교체:
```yaml
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-read
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core/agents`) && Method(`GET`)
      kind: Rule
      middlewares:
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-write
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core/agents`) && (Method(`POST`) || Method(`DELETE`))
      kind: Rule
      middlewares:
        - name: auth-forward
          namespace: core
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
```

- [ ] **Step 2: 커밋**

```bash
git add infra/k8s/base/gateway/routes.yaml
git commit -m "feat(infra): split API routes into public read and protected write"
```

---

### Task 8: 스모크 테스트 업데이트 + 전체 검증

**Files:**
- Modify: `apps/api/src/test/kotlin/com/bara/api/BaraApiApplicationTest.kt`

- [ ] **Step 1: 스모크 테스트에 MockkBean 추가**

기존 스모크 테스트는 MongoDB auto-config을 exclude하지만, 이제 `AgentMongoDataRepository` Bean이 필요하다. MockkBean으로 교체한다.

`apps/api/src/test/kotlin/com/bara/api/BaraApiApplicationTest.kt`:

```kotlin
package com.bara.api

import com.bara.api.adapter.out.persistence.AgentMongoDataRepository
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
    ]
)
class BaraApiApplicationTest {

    @MockkBean
    lateinit var agentMongoDataRepository: AgentMongoDataRepository

    @Test
    fun contextLoads() {
    }
}
```

- [ ] **Step 2: 전체 빌드 확인**

Run: `./gradlew build --no-daemon`

Expected: `BUILD SUCCESSFUL` — auth + api 모두 통과

- [ ] **Step 3: 커밋**

```bash
git add apps/api/src/test/kotlin/com/bara/api/BaraApiApplicationTest.kt
git commit -m "fix(api): update smoke test with MockkBean for AgentMongoDataRepository"
```
