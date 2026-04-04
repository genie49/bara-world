# 서비스 아키텍처 (헥사고날)

모든 Spring Boot 서비스(Auth, API, Scheduler)에 동일한 헥사고날 아키텍처를 적용한다. 단순 CRUD를 포함한 모든 기능에 예외 없이 동일한 패턴을 사용한다.

> 결정 배경은 [ADR-012](../decisions/adr-012-hexagonal-architecture.md) 참고.

## 핵심 원칙

1. **의존성은 항상 안쪽(Domain)을 향한다**
2. **Domain은 프레임워크를 모른다** — Spring, MongoDB, Kafka 의존성 없음
3. **외부와의 통신은 반드시 Port를 거친다**
4. **모든 기능에 동일한 패턴** — 단순/복잡 구분 없이 통일

## 패키지 구조

```
com.bara.{service}
├── domain/                    # 핵심 비즈니스 로직
│   ├── model/                 #   도메인 모델 (엔티티, VO)
│   └── exception/             #   도메인 예외
├── application/               # 유스케이스 (비즈니스 흐름 조율)
│   ├── port/
│   │   ├── in/                #   인바운드 포트 (UseCase 인터페이스)
│   │   └── out/               #   아웃바운드 포트 (Repository, 외부 시스템)
│   └── service/               #   유스케이스 구현체
├── adapter/                   # 외부 기술 연결
│   ├── in/                    #   인바운드 어댑터
│   │   ├── rest/              #     REST Controller
│   │   └── kafka/             #     Kafka Consumer
│   └── out/                   #   아웃바운드 어댑터
│       ├── persistence/       #     MongoDB Repository
│       ├── cache/             #     Redis
│       ├── kafka/             #     Kafka Producer (SDK 감싸기)
│       └── external/          #     외부 API (Google OAuth 등)
└── config/                    # Spring 설정, Bean 등록
```

## 각 레이어 규칙

### Domain

- **순수 Kotlin 코드**. Spring 어노테이션(`@Entity`, `@Document`, `@Component`) 사용 금지
- 비즈니스 규칙과 검증을 포함
- 다른 레이어에 의존하지 않음

```kotlin
// domain/model/Agent.kt
data class Agent(
    val id: AgentId,
    val name: String,
    val providerId: ProviderId,
    val endpoint: Url,
    val skills: List<Skill>,
    val createdAt: Instant,
) {
    init {
        require(endpoint.scheme == "https") { "Agent endpoint는 HTTPS만 허용" }
        require(name.isNotBlank()) { "Agent 이름은 필수" }
    }
}
```

### Application (Port + Service)

- **인바운드 포트**: 외부가 이 서비스에 요청할 수 있는 기능을 정의
- **아웃바운드 포트**: 이 서비스가 외부에 요청해야 하는 기능을 정의
- **Service**: 인바운드 포트를 구현하며, 아웃바운드 포트를 호출하여 비즈니스 흐름을 조율
- 포트 이름은 **기술이 아니라 의도**로 작성: `SaveAgentPort`(O), `MongoAgentRepository`(X)

```kotlin
// application/port/in/RegisterAgentUseCase.kt
interface RegisterAgentUseCase {
    fun register(command: RegisterAgentCommand): Agent
}

// application/port/out/SaveAgentPort.kt
interface SaveAgentPort {
    fun save(agent: Agent): Agent
}

// application/port/out/CreateKafkaAccountPort.kt
interface CreateKafkaAccountPort {
    fun create(agentId: AgentId): KafkaCredentials
}

// application/service/RegisterAgentService.kt
@Service
class RegisterAgentService(
    private val saveAgent: SaveAgentPort,
    private val createKafkaAccount: CreateKafkaAccountPort,
    private val registerHeartbeat: RegisterHeartbeatPort,
    private val verifyAgentCard: VerifyAgentCardPort,
) : RegisterAgentUseCase {

    override fun register(command: RegisterAgentCommand): Agent {
        verifyAgentCard.verify(command.endpoint)
        val agent = Agent.create(command)
        saveAgent.save(agent)
        registerHeartbeat.register(agent.id)
        val credentials = createKafkaAccount.create(agent.id)
        return agent.withCredentials(credentials)
    }
}
```

### Adapter

- **인바운드 어댑터**: 외부 요청을 받아 인바운드 포트(UseCase)를 호출
- **아웃바운드 어댑터**: 아웃바운드 포트를 구현하여 실제 인프라에 접근
- 프레임워크 어노테이션은 어댑터에서만 사용
- DTO 변환(도메인 모델 ↔ 외부 표현)은 어댑터에서 처리

```kotlin
// adapter/in/rest/AgentController.kt
@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgent: RegisterAgentUseCase,
    private val getAgent: GetAgentUseCase,
) {
    @PostMapping
    fun register(@RequestBody req: RegisterAgentRequest): AgentResponse {
        val agent = registerAgent.register(req.toCommand())
        return AgentResponse.from(agent)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): AgentResponse {
        val agent = getAgent.getById(AgentId(id))
        return AgentResponse.from(agent)
    }
}

// adapter/out/persistence/AgentMongoAdapter.kt
@Component
class AgentMongoAdapter(
    private val mongoTemplate: MongoTemplate,
) : SaveAgentPort, LoadAgentPort {

    override fun save(agent: Agent): Agent {
        mongoTemplate.save(AgentDocument.from(agent))
        return agent
    }

    override fun loadById(id: AgentId): Agent? {
        return mongoTemplate.findById(id.value, AgentDocument::class.java)
            ?.toDomain()
    }
}

// adapter/out/kafka/TaskKafkaAdapter.kt
@Component
class TaskKafkaAdapter(
    private val sdk: BaraSdk,
) : SendTaskPort {

    override fun send(agentId: AgentId, task: Task) {
        sdk.publish("tasks.${agentId.value}", task.toSdkMessage())
    }
}
```

## 의존성 방향

```
Adapter(IN) → Port(IN) ← Service → Port(OUT) ← Adapter(OUT)
                             ↓
                          Domain
```

- Adapter는 Port를 알지만, Port는 Adapter를 모름
- Service는 Domain과 Port를 알지만, Adapter를 모름
- Domain은 아무것도 모름

## SDK와 어댑터

공용 SDK(Kafka 통신 라이브러리)는 아웃바운드 어댑터 내부에서만 사용한다.

```
UseCase → SendTaskPort (interface) → TaskKafkaAdapter → SDK → Kafka
```

- 도메인/유스케이스 코드에 SDK import가 없어야 함
- SDK 버전 변경 시 어댑터만 수정
- 테스트 시 SendTaskPort만 mock하면 SDK/Kafka 불필요

## 서비스별 어댑터 구성

| 서비스    | 인바운드 어댑터            | 아웃바운드 어댑터                                    |
| --------- | -------------------------- | ---------------------------------------------------- |
| Auth      | REST                       | MongoDB, Google OAuth API                            |
| API       | REST, Kafka Consumer       | MongoDB, Redis, Kafka Producer (SDK)                 |
| Scheduler | @Scheduled, Kafka Consumer | MongoDB, Kafka Producer (SDK), HTTP (사용자 webhook) |

## DTO 변환 위치

| 변환                 | 위치              | 예시                               |
| -------------------- | ----------------- | ---------------------------------- |
| Request → Command    | 인바운드 어댑터   | `RegisterAgentRequest.toCommand()` |
| Domain → Response    | 인바운드 어댑터   | `AgentResponse.from(agent)`        |
| Domain → Document    | 아웃바운드 어댑터 | `AgentDocument.from(agent)`        |
| Document → Domain    | 아웃바운드 어댑터 | `AgentDocument.toDomain()`         |
| Domain → SDK Message | 아웃바운드 어댑터 | `Task.toSdkMessage()`              |

Domain 모델이 외부 표현 형식을 알아서는 안 된다. 변환 로직은 항상 어댑터 쪽에 둔다.
