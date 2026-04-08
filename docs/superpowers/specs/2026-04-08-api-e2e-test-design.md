# API Service E2E Test Design

## 개요

API Service에 E2E 테스트를 추가한다. Auth Service의 Testcontainers 패턴을 따르되, 컨테이너 관리를 공통 모듈(`libs/common-test`)로 추출하여 양쪽 서비스에서 재사용한다.

## 확정 사항

| 항목 | 값 |
|------|-----|
| 범위 | API Service Agent CRUD E2E + Auth E2eTestBase 리팩토링 |
| 공통 모듈 | `libs/common-test` — Testcontainers 유틸 |
| 인증 방식 | `X-Provider-Id` 헤더 직접 주입 (Auth Service 의존 없음) |
| 컨테이너 | MongoDB만 (Redis는 API Service에서 미사용) |
| 테스트 구조 | 시나리오 테스트 + 에러 테스트 (별도 클래스) |

## 1. 공통 E2E 모듈 (`libs/common-test`)

### 모듈 역할

Testcontainers 컨테이너 관리와 DB 클리너를 제공하는 테스트 전용 라이브러리.

### 제공 컴포넌트

#### MongoContainerSupport

- MongoDB 7 컨테이너를 싱글턴으로 시작
- `register(registry: DynamicPropertyRegistry, dbName: String)` — `spring.data.mongodb.uri` 프로퍼티 등록
- 컨테이너는 JVM 프로세스 수명 동안 1회 시작, 재사용

#### RedisContainerSupport

- Redis 7-alpine 컨테이너를 싱글턴으로 시작
- `register(registry: DynamicPropertyRegistry)` — `spring.data.redis.host`, `spring.data.redis.port` 프로퍼티 등록

#### DatabaseCleaner

- `clean(mongoTemplate: MongoTemplate)` — 모든 컬렉션의 문서 삭제
- `@BeforeEach`에서 호출하여 테스트 격리 보장

### build.gradle.kts

```kotlin
plugins { id("bara-kotlin-library") }

dependencies {
    api(libs.testcontainers.core)
    api(libs.testcontainers.junit.jupiter)
    api(libs.spring.boot.starter.test)
    implementation("org.springframework.data:spring-data-mongodb")
}
```

`settings.gradle.kts`에 `:libs:common-test` 추가.

## 2. API Service E2E 테스트

### E2eTestBase

```kotlin
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("e2e")
abstract class E2eTestBase {
    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun cleanDatabase() = DatabaseCleaner.clean(mongoTemplate)

    companion object {
        @JvmStatic @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            MongoContainerSupport.register(registry, dbName = "bara-api-e2e")
        }
    }
}
```

### 시나리오 테스트 (AgentFlowScenarioTest)

`@TestMethodOrder(OrderAnnotation)` + `@TestInstance(PER_CLASS)`로 순차 실행. `@BeforeEach` 오버라이드하여 DB 클리어 안 함 (상태 공유).

| 순서 | 테스트 | HTTP | 기대 |
|------|--------|------|------|
| 1 | Agent 등록 | `POST /api/core/agents` + `X-Provider-Id` | 201, id/name/providerId/agentCard 확인 |
| 2 | Agent 목록 조회 | `GET /api/core/agents` | 200, 1건, agentCard 미포함 |
| 3 | Agent 상세 조회 | `GET /api/core/agents/{id}` | 200, agentCard 포함 |
| 4 | Agent Card 조회 | `GET /api/core/agents/{id}/.well-known/agent.json` | 200, AgentCard JSON |
| 5 | Agent 삭제 | `DELETE /api/core/agents/{id}` + `X-Provider-Id` | 204 |
| 6 | 삭제 확인 | `GET /api/core/agents/{id}` | 404 |

### 에러 테스트 (AgentErrorTest)

일반 `E2eTestBase` 상속 (매 테스트 DB 클리어).

| 테스트 | HTTP | 기대 |
|--------|------|------|
| 존재하지 않는 Agent 조회 | `GET /api/core/agents/{random-id}` | 404, `agent_not_found` |
| 존재하지 않는 Agent 삭제 | `DELETE /api/core/agents/{random-id}` + `X-Provider-Id` | 404, `agent_not_found` |
| 동일 provider + 동일 이름 중복 등록 | `POST /api/core/agents` 2회 | 409, `agent_name_already_exists` |
| 다른 provider가 삭제 시도 | Provider-A가 등록 → Provider-B가 삭제 | 404, `agent_not_found` |

### 디렉토리 구조

```
apps/api/src/e2eTest/
├── kotlin/com/bara/api/e2e/
│   ├── support/
│   │   └── E2eTestBase.kt
│   ├── scenario/
│   │   └── AgentFlowScenarioTest.kt
│   └── error/
│       └── AgentErrorTest.kt
└── resources/
    └── application-e2e.yml
```

### application-e2e.yml

최소 설정. 프로퍼티 오버라이드 필요 시 여기에 추가.

```yaml
# E2E test profile - container properties are injected dynamically
```

### 빌드 구성

`apps/api/build.gradle.kts`에 Auth 패턴과 동일한 `e2eTest` 소스셋 + Gradle task 추가:

- `e2eTestImplementation(project(":libs:common-test"))`
- `e2eTestImplementation(project(":libs:common"))`
- `tasks.register<Test>("e2eTest")` with `useJUnitPlatform()`

## 3. Auth E2eTestBase 리팩토링

### 변경 내용

`E2eTestBase.kt`에서 컨테이너 직접 관리를 `common-test` 유틸 호출로 교체:

- `GenericContainer("mongo:7")` → `MongoContainerSupport.register(registry, "bara-auth-e2e")`
- `GenericContainer("redis:7-alpine")` → `RedisContainerSupport.register(registry)`
- `mongoTemplate.db.listCollectionNames()...` → `DatabaseCleaner.clean(mongoTemplate)`
- RSA 키 생성 로직은 Auth 전용이므로 Auth E2eTestBase에 유지

### build.gradle.kts 변경

기존 `e2eTestImplementation`의 testcontainers 직접 의존(`libs.testcontainers.core`, `libs.testcontainers.junit.jupiter`)을 `project(":libs:common-test")`로 교체. common-test가 전이적으로 제공.

## 4. 검증 기준

1. `./gradlew :libs:common-test:build` — 컴파일 성공
2. `./gradlew :apps:auth:e2eTest` — 기존 Auth E2E 테스트 전부 통과 (리팩토링 회귀 없음)
3. `./gradlew :apps:api:e2eTest` — 시나리오 6건 + 에러 4건 전부 통과
4. `./gradlew :apps:api:test` — 기존 단위/슬라이스 테스트 영향 없음
