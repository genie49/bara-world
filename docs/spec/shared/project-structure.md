# 프로젝트 구조

모노레포에서 모든 서비스를 관리하며, Gradle 멀티 프로젝트로 빌드한다. 각 앱은 독립된 Docker 이미지로 빌드되어 K3s Pod으로 배포된다.

## 디렉토리 구조

```
bara-world/
├── apps/                          # 실행 가능한 애플리케이션
│   ├── auth/                      #   Auth Service (Spring Boot)
│   ├── api/                       #   API Service (Spring Boot)
│   ├── scheduler/                 #   Scheduler Service (Spring Boot)
│   ├── fe/                        #   웹 FE
│   └── telegram/                  #   Telegram Service
├── libs/                          # 공유 라이브러리
│   └── common/                    #   서비스 간 공유 코드
├── sdk/                           # 공용 SDK (외부 Agent용)
│   ├── java/
│   ├── python/
│   └── typescript/
├── infra/                         # 인프라 설정
│   ├── nginx/                     #   Nginx 설정 파일
│   ├── k8s/                       #   K3s 매니페스트
│   └── docker-compose.dev.yml     #   로컬 개발용 인프라
├── docs/                          # 문서
│   ├── spec/                      #   시스템 설계 문서
│   └── guides/                    #   개발자 가이드
├── package.json                   # Git hooks 전용 (devDependencies만)
├── build.gradle.kts               # Gradle 루트 — 공통 설정
└── settings.gradle.kts            # Gradle 모듈 등록
```

## 앱 구성

### Spring Boot 서비스 (apps/auth, apps/api, apps/scheduler)

각 서비스는 독립된 Spring Boot 앱이다. 헥사고날 아키텍처 + 경량 CQRS를 적용한다.

> 서비스 내부 패키지 구조는 [서비스 아키텍처](service-architecture.md) 참고.

| 서비스    | 역할                                               |
| --------- | -------------------------------------------------- |
| Auth      | Google OAuth → JWT 발급, Provider 토큰, Kafka 토큰 |
| API       | Agent 등록/조회, 태스크 처리, A2A 게이트웨이       |
| Scheduler | cron 기반 반복 태스크 발행, 결과 전달              |

### 클라이언트 (apps/fe, apps/telegram)

| 앱       | 역할                        |
| -------- | --------------------------- |
| FE       | 웹 대화 인터페이스          |
| Telegram | Webhook 수신, 메시지 송수신 |

## 공유 라이브러리 (libs/common)

서비스 간 공유가 필요한 코드만 포함한다. 도메인 모델은 서비스별 독립이므로 공유하지 않는다.

| 포함 대상               | 이유                                |
| ----------------------- | ----------------------------------- |
| JSON-RPC 에러 응답 포맷 | 모든 서비스가 동일한 에러 포맷 사용 |
| Kafka 메시지 구조 (DTO) | 서비스 간 Kafka 통신에 동일 구조    |
| JWT 검증 로직           | API, Scheduler 모두 JWT 검증 필요   |

| 제외 대상                    | 이유                          |
| ---------------------------- | ----------------------------- |
| 도메인 모델 (Agent, User 등) | 헥사고날 원칙 — 서비스별 독립 |
| 서비스별 설정                | 각 서비스 고유                |

```kotlin
// libs/common/build.gradle.kts
// 순수 라이브러리 — Spring Boot 플러그인 없음, 다른 서비스에서 참조
dependencies {
    implementation(project(":libs:common"))
}
```

## 기술 스택 버전

| 항목        | 버전             |
| ----------- | ---------------- |
| Kotlin      | 2.1.x            |
| Java (JDK)  | 21 (LTS)         |
| Spring Boot | 3.4.x            |
| Gradle      | 8.x (Kotlin DSL) |

## 빌드

Gradle 멀티 프로젝트로 전체 또는 개별 빌드가 가능하다.

```bash
# 전체 빌드
./gradlew build

# 개별 서비스 빌드
./gradlew :apps:auth:bootJar
./gradlew :apps:api:bootJar
./gradlew :apps:scheduler:bootJar
```

각 서비스의 빌드 결과물은 독립된 실행 가능 jar 파일이다.

## 배포

각 서비스는 Docker 이미지로 빌드되어 K3s Pod으로 배포된다.

```
소스 → Gradle 빌드 → jar → Docker 이미지 → K3s Pod
```

### K3s Pod 구성

| Pod               | 이미지              | 비고                    |
| ----------------- | ------------------- | ----------------------- |
| auth-service      | 직접 빌드           | Spring Boot jar         |
| api-service       | 직접 빌드           | Spring Boot jar         |
| scheduler-service | 직접 빌드           | Spring Boot jar         |
| fe                | 직접 빌드           | 웹 FE                   |
| telegram-service  | 직접 빌드           | Telegram Bot            |
| nginx             | `nginx:alpine`      | ConfigMap으로 설정 주입 |
| mongodb           | `mongo:7`           | StatefulSet + PV        |
| redis             | `redis:7-alpine`    |                         |
| kafka             | `bitnami/kafka`     | KRaft 모드              |
| fluent-bit        | `fluent/fluent-bit` | DaemonSet               |
| loki              | `grafana/loki`      |                         |
| grafana           | `grafana/grafana`   |                         |

> 인프라 상세(VM, K3s, Cloudflare 등)는 [인프라 문서](infrastructure.md) 참고.

## 로컬 개발 환경

로컬에서는 인프라만 Docker Compose로 띄우고, 서비스는 IDE에서 직접 실행한다.

```yaml
# infra/docker-compose.dev.yml
services:
  mongodb:
    image: mongo:7
    ports: ['27017:27017']
  redis:
    image: redis:7-alpine
    ports: ['6379:6379']
  kafka:
    image: bitnami/kafka
    ports: ['9092:9092']
```

```bash
# 인프라 시작
docker compose -f infra/docker-compose.dev.yml up -d

# 서비스 실행 (IDE 또는 CLI)
./gradlew :apps:auth:bootRun
```
