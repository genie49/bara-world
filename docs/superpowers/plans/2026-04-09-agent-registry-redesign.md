# Agent Registry 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AgentCard를 최소화하고, Agent name을 글로벌 unique로 변경하고, Redis 기반 registry/heartbeat 관리와 Kafka 기반 A2A 메시지 프록시를 API Service에 추가한다.

**Architecture:** Provider가 수동 등록한 Agent(MongoDB)에 대해, Agent가 기동 시 registry API를 호출하여 Redis에 생존 등록한다. Heartbeat(Kafka)로 TTL을 갱신하고, User는 `/agents/{agentName}/message:send`로 Kafka를 통해 A2A 통신한다.

**Tech Stack:** Kotlin, Spring Boot 3.4.4, MongoDB, Redis, Kafka (spring-kafka), MockK

**중요:** 커밋 메시지에 Co-Authored-By 트레일러를 붙이지 마라. git commit 시 --no-verify 플래그를 사용하지 마라.

---

### Task 1: AgentCard 최소화 및 Agent name 글로벌 unique

**Files:**
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/model/Agent.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentDocument.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoDataRepository.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoRepository.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRepository.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentCommand.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/service/command/RegisterAgentService.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNameAlreadyExistsException.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Modify: 모든 테스트 파일

이 Task는 기존 코드 전반에 걸쳐 수정이 필요하므로 스텝을 크게 묶는다.

- [ ] **Step 1: AgentCard.kt 최소화**

`apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt` 전체 교체:

```kotlin
package com.bara.api.domain.model

data class AgentCard(
    val name: String,
    val description: String,
    val version: String,
)
```

- [ ] **Step 2: AgentDocument.kt에서 AgentCard 매핑 단순화**

`AgentCardDocument`, `AgentCapabilitiesDocument`, `AgentSkillDocument` 클래스를 모두 제거하고 단순 구조로 교체.

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentDocument.kt` 전체 교체:

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "agents")
data class AgentDocument(
    @Id val id: String,
    @Indexed(unique = true) val name: String,
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
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
    )

    companion object {
        fun fromDomain(c: AgentCard): AgentCardDocument = AgentCardDocument(
            name = c.name,
            description = c.description,
            version = c.version,
        )
    }
}
```

- [ ] **Step 3: AgentRepository에 findByName 추가, findByProviderIdAndName을 findByName으로 변경**

`apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRepository.kt` 전체 교체:

```kotlin
package com.bara.api.application.port.out

import com.bara.api.domain.model.Agent

interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findByName(name: String): Agent?
    fun findAll(): List<Agent>
    fun deleteById(id: String)
}
```

- [ ] **Step 4: AgentMongoDataRepository 변경**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoDataRepository.kt` 전체 교체:

```kotlin
package com.bara.api.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface AgentMongoDataRepository : MongoRepository<AgentDocument, String> {
    fun findByName(name: String): AgentDocument?
}
```

- [ ] **Step 5: AgentMongoRepository 변경**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/AgentMongoRepository.kt` 전체 교체:

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

    override fun findByName(name: String): Agent? =
        dataRepository.findByName(name)?.toDomain()

    override fun findAll(): List<Agent> =
        dataRepository.findAll().map { it.toDomain() }

    override fun deleteById(id: String) =
        dataRepository.deleteById(id)
}
```

- [ ] **Step 6: RegisterAgentCommand 변경**

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegisterAgentCommand.kt` 전체 교체:

```kotlin
package com.bara.api.application.port.`in`.command

import com.bara.api.domain.model.AgentCard

data class RegisterAgentCommand(
    val name: String,
    val agentCard: AgentCard,
)
```

(변경 없음 — 그대로 유지)

- [ ] **Step 7: AgentNameAlreadyExistsException 변경**

`apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNameAlreadyExistsException.kt` 전체 교체:

```kotlin
package com.bara.api.domain.exception

class AgentNameAlreadyExistsException : RuntimeException("Agent name already exists")
```

- [ ] **Step 8: RegisterAgentService 변경 — 글로벌 unique 검증**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/RegisterAgentService.kt` 전체 교체:

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
        agentRepository.findByName(command.name)?.let {
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

- [ ] **Step 9: AgentDtos.kt 변경 — AgentCard 요청/응답 단순화**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt` 전체 교체:

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
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
    )
}

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

- [ ] **Step 10: 테스트 전체 수정**

모든 테스트에서 `AgentCard` 생성 부분을 최소화된 스키마로 변경한다.

`AgentCard(...)` 호출을 다음으로 통일:

```kotlin
private val agentCard = AgentCard(
    name = "Test Agent",
    description = "A test agent",
    version = "1.0.0",
)
```

테스트 파일별로:
- `AgentControllerTest.kt`: `agentCard` 필드 변경, POST 요청 JSON 변경, `findByProviderIdAndName` → `findByName` 관련 mock 없음 (Controller 테스트에서는 UseCase mock)
- `RegisterAgentServiceTest.kt`: `agentCard` 필드 변경, `findByProviderIdAndName` → `findByName`
- `DeleteAgentServiceTest.kt`: `agentCard` 필드 변경

각 테스트 파일의 `agentCard` 생성 부분과 JSON 요청 body를 수정한다. 구체적인 코드는 아래에.

`RegisterAgentServiceTest.kt` 전체 교체:

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
    )

    @Test
    fun `신규 Agent를 등록하면 저장된다`() {
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByName("My Agent") } returns null
        every { agentRepository.save(any()) } answers { firstArg() }

        val result = service.register("p-1", command)

        assertEquals("My Agent", result.name)
        assertEquals("p-1", result.providerId)
        assertEquals(agentCard, result.agentCard)
        verify { agentRepository.save(any()) }
    }

    @Test
    fun `같은 이름의 Agent가 있으면 예외 발생`() {
        val existing = Agent.create(name = "My Agent", providerId = "p-other", agentCard = agentCard)
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByName("My Agent") } returns existing

        assertThrows<AgentNameAlreadyExistsException> {
            service.register("p-1", command)
        }
    }
}
```

`DeleteAgentServiceTest.kt` 전체 교체:

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

`AgentControllerTest.kt` 전체 교체:

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
                        "version": "1.0.0"
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
            content = """{"name":"dup","agentCard":{"name":"A","description":"d","version":"1"}}"""
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

- [ ] **Step 11: 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과

- [ ] **Step 12: 커밋**

```bash
git add apps/api/
git commit -m "refactor(api): simplify AgentCard and make agent name globally unique"
```

---

### Task 2: Redis 의존성 추가 및 AgentRegistryPort/Adapter

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRegistryPort.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/AgentRegistryRedisAdapter.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/AgentRegistryRedisAdapterTest.kt`

- [ ] **Step 1: build.gradle.kts에 Redis 의존성 추가**

`apps/api/build.gradle.kts`의 dependencies 블록에 추가:

```kotlin
implementation(libs.spring.boot.starter.data.redis)
```

- [ ] **Step 2: application.yml에 Redis 설정 추가**

`apps/api/src/main/resources/application.yml`에 추가:

```yaml
spring:
  data:
    redis:
      host: redis.data.svc.cluster.local
      port: 6379
```

- [ ] **Step 3: AgentRegistryPort 생성**

`apps/api/src/main/kotlin/com/bara/api/application/port/out/AgentRegistryPort.kt`:

```kotlin
package com.bara.api.application.port.out

interface AgentRegistryPort {
    fun register(agentName: String, agentId: String)
    fun isRegistered(agentName: String): Boolean
    fun getAgentId(agentName: String): String?
    fun refreshTtl(agentName: String)
}
```

- [ ] **Step 4: 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/AgentRegistryRedisAdapterTest.kt`:

```kotlin
package com.bara.api.adapter.out.redis

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRegistryRedisAdapterTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val adapter = AgentRegistryRedisAdapter(redisTemplate)

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `register는 60초 TTL로 Redis에 저장한다`() {
        justRun { valueOps.set("agent:registry:my-agent", "agent-001", Duration.ofSeconds(60)) }

        adapter.register("my-agent", "agent-001")

        verify { valueOps.set("agent:registry:my-agent", "agent-001", Duration.ofSeconds(60)) }
    }

    @Test
    fun `isRegistered는 키가 있으면 true를 반환한다`() {
        every { redisTemplate.hasKey("agent:registry:my-agent") } returns true

        assertTrue(adapter.isRegistered("my-agent"))
    }

    @Test
    fun `isRegistered는 키가 없으면 false를 반환한다`() {
        every { redisTemplate.hasKey("agent:registry:unknown") } returns false

        assertFalse(adapter.isRegistered("unknown"))
    }

    @Test
    fun `getAgentId는 저장된 값을 반환한다`() {
        every { valueOps.get("agent:registry:my-agent") } returns "agent-001"

        assertEquals("agent-001", adapter.getAgentId("my-agent"))
    }

    @Test
    fun `getAgentId는 키가 없으면 null을 반환한다`() {
        every { valueOps.get("agent:registry:unknown") } returns null

        assertNull(adapter.getAgentId("unknown"))
    }

    @Test
    fun `refreshTtl은 TTL을 60초로 갱신한다`() {
        every { redisTemplate.expire("agent:registry:my-agent", Duration.ofSeconds(60)) } returns true

        adapter.refreshTtl("my-agent")

        verify { redisTemplate.expire("agent:registry:my-agent", Duration.ofSeconds(60)) }
    }
}
```

- [ ] **Step 5: AgentRegistryRedisAdapter 구현**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/AgentRegistryRedisAdapter.kt`:

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.AgentRegistryPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

private const val KEY_PREFIX = "agent:registry:"
private val TTL = Duration.ofSeconds(60)

@Component
class AgentRegistryRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
) : AgentRegistryPort {

    override fun register(agentName: String, agentId: String) {
        redisTemplate.opsForValue().set("$KEY_PREFIX$agentName", agentId, TTL)
    }

    override fun isRegistered(agentName: String): Boolean =
        redisTemplate.hasKey("$KEY_PREFIX$agentName")

    override fun getAgentId(agentName: String): String? =
        redisTemplate.opsForValue().get("$KEY_PREFIX$agentName")

    override fun refreshTtl(agentName: String) {
        redisTemplate.expire("$KEY_PREFIX$agentName", TTL)
    }
}
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과 (Redis autoconfiguration은 테스트에서 exclude 필요할 수 있음)

- [ ] **Step 7: 커밋**

```bash
git add apps/api/
git commit -m "feat(api): add Redis AgentRegistry port and adapter"
```

---

### Task 3: Registry API 엔드포인트

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegistryAgentUseCase.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/RegistryAgentService.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/command/RegistryAgentServiceTest.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentOwnershipException.kt`

- [ ] **Step 1: AgentOwnershipException 생성**

`apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentOwnershipException.kt`:

```kotlin
package com.bara.api.domain.exception

class AgentOwnershipException : RuntimeException("Agent does not belong to this provider")
```

- [ ] **Step 2: RegistryAgentUseCase 포트 생성**

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/RegistryAgentUseCase.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

interface RegistryAgentUseCase {
    fun registry(providerId: String, agentName: String)
}
```

- [ ] **Step 3: 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/application/service/command/RegistryAgentServiceTest.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RegistryAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val service = RegistryAgentService(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")

    @Test
    fun `소유한 Agent를 registry하면 Redis에 등록된다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.register("my-agent", agent.id) }

        service.registry("p-1", "my-agent")

        verify { agentRegistryPort.register("my-agent", agent.id) }
    }

    @Test
    fun `존재하지 않는 Agent registry 시 예외`() {
        every { agentRepository.findByName("unknown") } returns null

        assertThrows<AgentNotFoundException> {
            service.registry("p-1", "unknown")
        }
    }

    @Test
    fun `소유하지 않은 Agent registry 시 예외`() {
        val agent = Agent.create(name = "other-agent", providerId = "p-other", agentCard = agentCard)
        every { agentRepository.findByName("other-agent") } returns agent

        assertThrows<AgentOwnershipException> {
            service.registry("p-1", "other-agent")
        }
    }

    @Test
    fun `이미 registry된 Agent도 멱등하게 성공한다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.register("my-agent", agent.id) }

        service.registry("p-1", "my-agent")
        service.registry("p-1", "my-agent")

        verify(exactly = 2) { agentRegistryPort.register("my-agent", agent.id) }
    }
}
```

- [ ] **Step 4: RegistryAgentService 구현**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/RegistryAgentService.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegistryAgentService(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) : RegistryAgentUseCase {

    override fun registry(providerId: String, agentName: String) {
        val agent = agentRepository.findByName(agentName)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentOwnershipException()
        }

        agentRegistryPort.register(agentName, agent.id)

        WideEvent.put("agent_id", agent.id)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_registry")
        WideEvent.message("Agent registry 완료")
    }
}
```

- [ ] **Step 5: Controller에 registry 엔드포인트 추가**

`AgentController.kt`에 추가:

```kotlin
@PostMapping("/{agentName}/registry")
fun registry(
    @RequestHeader("X-Provider-Id") providerId: String,
    @PathVariable agentName: String,
): ResponseEntity<Void> {
    registryAgentUseCase.registry(providerId, agentName)
    return ResponseEntity.ok().build()
}
```

그리고 생성자에 `private val registryAgentUseCase: RegistryAgentUseCase` 추가.

- [ ] **Step 6: ApiExceptionHandler에 AgentOwnershipException 처리 추가**

```kotlin
@ExceptionHandler(AgentOwnershipException::class)
fun handleAgentOwnership(ex: AgentOwnershipException): ResponseEntity<ErrorResponse> {
    WideEvent.put("error_type", "AgentOwnershipException")
    WideEvent.put("outcome", "agent_ownership_denied")
    WideEvent.message("Agent 소유권 불일치")
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse("agent_ownership_denied", ex.message ?: "Agent does not belong to this provider"))
}
```

- [ ] **Step 7: 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과

- [ ] **Step 8: 커밋**

```bash
git add apps/api/
git commit -m "feat(api): add Agent registry endpoint with ownership verification"
```

---

### Task 4: Kafka 의존성 추가 및 Heartbeat Consumer

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumer.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumerTest.kt`

- [ ] **Step 1: build.gradle.kts에 Kafka 의존성 추가**

`gradle/libs.versions.toml`의 `[libraries]`에 추가:

```toml
spring-kafka = { module = "org.springframework.kafka:spring-kafka" }
spring-kafka-test = { module = "org.springframework.kafka:spring-kafka-test" }
```

`apps/api/build.gradle.kts`의 dependencies에 추가:

```kotlin
implementation(libs.spring.kafka)
testImplementation(libs.spring.kafka.test)
```

- [ ] **Step 2: application.yml에 Kafka 설정 추가**

```yaml
spring:
  kafka:
    bootstrap-servers: kafka-0.kafka.data.svc.cluster.local:9092
    consumer:
      group-id: api-service
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
```

- [ ] **Step 3: 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumerTest.kt`:

```kotlin
package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class HeartbeatConsumerTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val consumer = HeartbeatConsumer(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")

    @Test
    fun `registry된 Agent의 heartbeat는 TTL을 갱신한다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        justRun { agentRegistryPort.refreshTtl("my-agent") }

        consumer.handleHeartbeat("""{"agent_id":"${agent.id}","timestamp":"2026-04-09T10:00:00Z"}""")

        verify { agentRegistryPort.refreshTtl("my-agent") }
    }

    @Test
    fun `registry되지 않은 Agent의 heartbeat는 무시한다`() {
        val agent = Agent.create(name = "unregistered", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        every { agentRegistryPort.isRegistered("unregistered") } returns false

        consumer.handleHeartbeat("""{"agent_id":"${agent.id}","timestamp":"2026-04-09T10:00:00Z"}""")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }

    @Test
    fun `존재하지 않는 Agent의 heartbeat는 무시한다`() {
        every { agentRepository.findById("unknown-id") } returns null

        consumer.handleHeartbeat("""{"agent_id":"unknown-id","timestamp":"2026-04-09T10:00:00Z"}""")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }

    @Test
    fun `잘못된 JSON은 무시한다`() {
        consumer.handleHeartbeat("not json")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }
}
```

- [ ] **Step 4: HeartbeatConsumer 구현**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumer.kt`:

```kotlin
package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private data class HeartbeatMessage(
    val agent_id: String,
    val timestamp: String,
)

@Component
class HeartbeatConsumer(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    @KafkaListener(topics = ["heartbeat"], groupId = "api-service")
    fun handleHeartbeat(message: String) {
        val heartbeat = try {
            mapper.readValue<HeartbeatMessage>(message)
        } catch (e: Exception) {
            logger.warn("Failed to parse heartbeat message", e)
            return
        }

        val agent = agentRepository.findById(heartbeat.agent_id)
        if (agent == null) {
            logger.warn("Heartbeat from unknown agent: {}", heartbeat.agent_id)
            return
        }

        if (!agentRegistryPort.isRegistered(agent.name)) {
            logger.debug("Heartbeat from unregistered agent: {}", agent.name)
            return
        }

        agentRegistryPort.refreshTtl(agent.name)
    }
}
```

- [ ] **Step 5: 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과 (Kafka autoconfiguration 테스트 exclude 필요할 수 있음 — `AgentControllerTest`의 `TestPropertySource`에 Kafka 관련 exclude 추가)

- [ ] **Step 6: 커밋**

```bash
git add apps/api/ gradle/libs.versions.toml
git commit -m "feat(api): add Kafka heartbeat consumer with Redis TTL refresh"
```

---

### Task 5: A2A 메시지 프록시 엔드포인트

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskPublisherPort.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisher.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentUnavailableException.kt`

- [ ] **Step 1: AgentUnavailableException 생성**

`apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentUnavailableException.kt`:

```kotlin
package com.bara.api.domain.exception

class AgentUnavailableException : RuntimeException("Agent is not available")
```

- [ ] **Step 2: TaskPublisherPort 생성**

`apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskPublisherPort.kt`:

```kotlin
package com.bara.api.application.port.out

interface TaskPublisherPort {
    fun publish(agentId: String, taskMessage: Map<String, Any?>)
}
```

- [ ] **Step 3: TaskKafkaPublisher 구현**

`apps/api/src/main/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisher.kt`:

```kotlin
package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskPublisherPort
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class TaskKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : TaskPublisherPort {
    private val mapper = jacksonObjectMapper()

    override fun publish(agentId: String, taskMessage: Map<String, Any?>) {
        val topic = "tasks.$agentId"
        val value = mapper.writeValueAsString(taskMessage)
        kafkaTemplate.send(topic, value)
    }
}
```

- [ ] **Step 4: SendMessageUseCase 포트 생성**

`apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

interface SendMessageUseCase {
    fun sendMessage(userId: String, agentName: String, request: SendMessageRequest): String

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}
```

- [ ] **Step 5: 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.AgentUnavailableException
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

class SendMessageServiceTest {

    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val taskPublisherPort = mockk<TaskPublisherPort>()
    private val service = SendMessageService(agentRegistryPort, taskPublisherPort)

    @Test
    fun `살아있는 Agent에 메시지를 보내면 Kafka에 발행된다`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        justRun { taskPublisherPort.publish(eq("agent-001"), any()) }

        val request = SendMessageUseCase.SendMessageRequest(
            messageId = "msg-1",
            text = "안녕하세요",
            contextId = "ctx-1",
        )
        val taskId = service.sendMessage("user-1", "my-agent", request)

        assertNotNull(taskId)
        verify { taskPublisherPort.publish(eq("agent-001"), any()) }
    }

    @Test
    fun `registry되지 않은 Agent에 메시지를 보내면 예외`() {
        every { agentRegistryPort.getAgentId("dead-agent") } returns null

        val request = SendMessageUseCase.SendMessageRequest(
            messageId = "msg-1",
            text = "hello",
            contextId = null,
        )

        assertThrows<AgentUnavailableException> {
            service.sendMessage("user-1", "dead-agent", request)
        }
    }
}
```

- [ ] **Step 6: SendMessageService 구현**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SendMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
) : SendMessageUseCase {

    override fun sendMessage(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): String {
        val agentId = agentRegistryPort.getAgentId(agentName)
            ?: throw AgentUnavailableException()

        val taskId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()

        val taskMessage = mapOf(
            "task_id" to taskId,
            "context_id" to request.contextId,
            "user_id" to userId,
            "request_id" to requestId,
            "result_topic" to "results.api",
            "message" to mapOf(
                "message_id" to request.messageId,
                "role" to "user",
                "parts" to listOf(mapOf("text" to request.text)),
            ),
        )

        taskPublisherPort.publish(agentId, taskMessage)

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("outcome", "task_published")
        WideEvent.message("태스크 발행 완료")

        return taskId
    }
}
```

- [ ] **Step 7: AgentDtos.kt에 메시지 DTO 추가**

`AgentDtos.kt`에 추가:

```kotlin
// ── A2A Message ──

data class SendMessageApiRequest(
    val message: MessageRequest,
    val contextId: String? = null,
)

data class MessageRequest(
    val messageId: String,
    val parts: List<PartRequest>,
)

data class PartRequest(
    val text: String,
)

data class SendMessageApiResponse(
    val taskId: String,
)
```

- [ ] **Step 8: Controller에 message:send 엔드포인트 추가**

`AgentController.kt`에 추가:

```kotlin
@PostMapping("/{agentName}/message:send")
fun sendMessage(
    @RequestHeader("X-User-Id") userId: String,
    @PathVariable agentName: String,
    @RequestBody request: SendMessageApiRequest,
): ResponseEntity<SendMessageApiResponse> {
    val text = request.message.parts.firstOrNull()?.text ?: ""
    val sendRequest = SendMessageUseCase.SendMessageRequest(
        messageId = request.message.messageId,
        text = text,
        contextId = request.contextId,
    )
    val taskId = sendMessageUseCase.sendMessage(userId, agentName, sendRequest)
    return ResponseEntity.ok(SendMessageApiResponse(taskId = taskId))
}
```

그리고 생성자에 `private val sendMessageUseCase: SendMessageUseCase` 추가.

- [ ] **Step 9: ApiExceptionHandler에 AgentUnavailableException 처리 추가**

```kotlin
@ExceptionHandler(AgentUnavailableException::class)
fun handleAgentUnavailable(ex: AgentUnavailableException): ResponseEntity<ErrorResponse> {
    WideEvent.put("error_type", "AgentUnavailableException")
    WideEvent.put("outcome", "agent_unavailable")
    WideEvent.message("Agent 비활성")
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ErrorResponse("agent_unavailable", ex.message ?: "Agent is not available"))
}
```

- [ ] **Step 10: 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과

- [ ] **Step 11: 커밋**

```bash
git add apps/api/
git commit -m "feat(api): add A2A message:send proxy endpoint via Kafka"
```

---

### Task 6: Traefik 라우트 추가 및 통합 테스트

**Files:**
- Modify: `infra/k8s/base/gateway/routes.yaml`

- [ ] **Step 1: routes.yaml에 message:send 라우트 추가**

`infra/k8s/base/gateway/routes.yaml`에 추가:

```yaml
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-agent-message
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core/agents`) && PathRegexp(`/api/core/agents/[^/]+/message:send`)
      kind: Rule
      middlewares:
        - name: auth-forward
          namespace: core
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-agent-registry
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core/agents`) && PathRegexp(`/api/core/agents/[^/]+/registry`)
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

- [ ] **Step 2: 전체 빌드 확인**

Run: `./gradlew :apps:api:test`
Expected: 전체 통과

- [ ] **Step 3: 커밋**

```bash
git add infra/k8s/base/gateway/routes.yaml
git commit -m "feat(infra): add Traefik routes for agent registry and message:send"
```
