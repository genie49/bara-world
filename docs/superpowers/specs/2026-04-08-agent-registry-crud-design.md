# Agent Registry CRUD Design

## 개요

API Service에 Agent 등록/조회/삭제 CRUD와 Agent Card 제공 기능을 구현한다. Auth Service의 Hexagonal + CQRS 패턴을 따른다. Redis(heartbeat), Kafka(계정/ACL)는 이번 범위에서 제외하고, 순수 MongoDB CRUD + REST 엔드포인트에 집중한다.

## 확정 사항

| 항목 | 값 |
|------|-----|
| 범위 | Agent CRUD + Agent Card (MongoDB only) |
| 인증 | forwardAuth가 주입하는 `X-Provider-Id` 헤더로 등록/삭제 인가 |
| Agent Card | 정형 도메인 모델 (A2A v0.3 스펙 기반) |
| 조회 | 전체 public (인증 불필요) |
| 변경 | Provider 인증 필수 (`X-Provider-Id`) |
| 저장 | Agent + AgentCard 단일 Document (embedded) |

## 1. 도메인 모델

### Agent

```kotlin
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

### AgentCard (A2A v0.3 기반)

```kotlin
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

A2A 스펙의 `supported_interfaces`는 Agent 등록 시 받지 않는다. Agent Card 조회(`/.well-known/agent.json`) 응답 시 API Service가 다음 값을 자동 생성하여 포함한다:

```json
{
  "supported_interfaces": [{
    "url": "https://{host}/api/core/agents/{agent-id}",
    "protocol_binding": "JSONRPC",
    "protocol_version": "0.3"
  }]
}
```

`provider`, `security_schemes`, `signatures`는 Kafka 연동 시 추가 예정.

### 도메인 예외

```kotlin
class AgentNotFoundException : RuntimeException("Agent not found")
class AgentNameAlreadyExistsException : RuntimeException("Agent name already exists for this provider")
```

중복 검증은 `providerId + name` 조합.

## 2. 포트 + 서비스

### Input Ports

**Command:**
- `RegisterAgentUseCase` — `fun register(providerId: String, command: RegisterAgentCommand): Agent`
- `DeleteAgentUseCase` — `fun delete(providerId: String, agentId: String)`

`RegisterAgentCommand`는 `name: String` + `agentCard: AgentCard`를 담는 데이터 클래스. Application 레이어에 위치.

**Query:**
- `ListAgentsQuery` — `fun listAll(): List<Agent>`
- `GetAgentQuery` — `fun getById(agentId: String): Agent`
- `GetAgentCardQuery` — `fun getCardById(agentId: String): AgentCard`

### Output Ports

```kotlin
interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findAll(): List<Agent>
    fun findByProviderIdAndName(providerId: String, name: String): Agent?
    fun deleteById(id: String)
}
```

### 서비스 로직

| 서비스 | 핵심 로직 |
|--------|----------|
| `RegisterAgentService` | `findByProviderIdAndName` 중복 검증 → `Agent.create()` → save → WideEvent 로깅 |
| `DeleteAgentService` | findById → providerId 소유권 검증 → deleteById → WideEvent 로깅 |
| `ListAgentsService` | findAll 반환 |
| `GetAgentService` | findById → 없으면 `AgentNotFoundException` |
| `GetAgentCardService` | findById → `agentCard` 반환 → 없으면 `AgentNotFoundException` |

## 3. REST 컨트롤러 + 에러 핸들링

### AgentController

| 메서드 | 엔드포인트 | 인증 | 응답 |
|--------|-----------|------|------|
| `POST` | `/agents` | `X-Provider-Id` 필수 | 201 Created + AgentDetailResponse |
| `GET` | `/agents` | 불필요 (public) | 200 + AgentListResponse |
| `GET` | `/agents/{id}` | 불필요 (public) | 200 + AgentDetailResponse / 404 |
| `GET` | `/agents/{id}/.well-known/agent.json` | 불필요 (public) | 200 + AgentCard JSON / 404 |
| `DELETE` | `/agents/{id}` | `X-Provider-Id` 필수 | 204 No Content / 404 |

- 등록/상세 응답에는 `agentCard` 포함 (`AgentDetailResponse`)
- 목록 응답에는 `agentCard` 제외 (`AgentResponse` — id, name, providerId, createdAt)
- Agent Card 조회(`/.well-known/agent.json`) 시 `supported_interfaces`를 API Service가 자동 생성하여 추가

### Request/Response DTO

컨트롤러에 co-located:

```kotlin
// Request
data class RegisterAgentRequest(
    val name: String,
    val agentCard: AgentCardRequest,
)
data class AgentCardRequest(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesRequest,
    val skills: List<AgentSkillRequest>,
    val iconUrl: String? = null,
)
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

// Response
data class AgentResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val createdAt: String,
)
data class AgentDetailResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val agentCard: AgentCard,
    val createdAt: String,
)
data class AgentListResponse(val agents: List<AgentResponse>)
```

### ApiExceptionHandler

| 예외 | HTTP 상태 | error 코드 |
|------|----------|-----------|
| `AgentNotFoundException` | 404 | `agent_not_found` |
| `AgentNameAlreadyExistsException` | 409 | `agent_name_already_exists` |

`ErrorResponse(error, message)` 형식은 Auth의 `AuthExceptionHandler`와 동일.

### Traefik 라우팅 변경

현재 라우팅을 read/write 분리:

- `api-public`: Swagger UI + API docs + actuator (기존)
- `api-read`: `PathPrefix(/api/core/agents) && Method(GET)` → public (인증 없이)
- `api-write`: `PathPrefix(/api/core/agents) && Method(POST,DELETE)` → forwardAuth 보호

기존 `api-protected` (전체 `/api/core`) 라우트를 위 구조로 교체.

## 4. 영속화

### MongoDB Document

단일 `agents` 컬렉션. AgentCard는 embedded:

```kotlin
@Document(collection = "agents")
data class AgentDocument(
    @Id val id: String,
    val name: String,
    @Indexed val providerId: String,
    val agentCard: AgentCardDocument,
    val createdAt: Instant,
)

data class AgentCardDocument(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesDocument,
    val skills: List<AgentSkillDocument>,
    val iconUrl: String?,
)

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
)
```

각 Document 클래스에 `toDomain()` / `fromDomain()` 변환 메서드 포함.

인덱스: `providerId` (단독), `providerId + name` (유니크 복합 인덱스)

### 2-layer Repository 패턴

- `AgentMongoDataRepository` — Spring Data interface (`MongoRepository<AgentDocument, String>`)
  - `findByProviderIdAndName(providerId: String, name: String): AgentDocument?`
- `AgentMongoRepository` — output port(`AgentRepository`) 구현. Document ↔ Domain 변환.

## 5. 테스트 전략

| 레이어 | 테스트 방식 | Mock 대상 |
|--------|-----------|----------|
| Service (command) | 단위 테스트 (MockK) | `AgentRepository` |
| Service (query) | 단위 테스트 (MockK) | `AgentRepository` |
| Controller | `@WebMvcTest` 슬라이스 | Use Case / Query |
| ExceptionHandler | Controller 테스트에 포함 | - |

Persistence 레이어 테스트는 E2E 구현 시 추가.

## 6. 검증 기준

1. `./gradlew :apps:api:test` — 모든 단위/슬라이스 테스트 통과
2. `bootRun` 후 `POST /agents` → 201 + Agent 생성 확인
3. `GET /agents` → 200 + 목록 (agentCard 미포함)
4. `GET /agents/{id}` → 200 + 상세 (agentCard 포함)
5. `GET /agents/{id}/.well-known/agent.json` → 200 + AgentCard JSON
6. `DELETE /agents/{id}` → 204 + 삭제 확인
7. 중복 이름 등록 → 409 Conflict
8. 존재하지 않는 Agent 조회/삭제 → 404
9. k3d 환경에서 Traefik 라우팅 동작 확인 (GET public, POST/DELETE protected)
