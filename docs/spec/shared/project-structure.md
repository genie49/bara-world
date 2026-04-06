# 프로젝트 구조

모노레포에서 모든 서비스를 관리하며, Gradle 멀티 프로젝트로 빌드한다. 각 앱은 독립된 Docker 이미지로 빌드되어 K3s Pod으로 배포된다.

## 디렉토리 구조

> ✓ = 구현 완료, ○ = 계획됨 (미구현)

```
bara-world/
├── apps/                          # 실행 가능한 애플리케이션
│   ├── auth/                      # ✓ Auth Service (Spring Boot)
│   ├── fe/                        # ✓ 웹 FE (Vite + React, pnpm)
│   ├── api/                       # ○ API Service (Spring Boot)
│   ├── scheduler/                 # ○ Scheduler Service (Spring Boot)
│   └── telegram/                  # ○ Telegram Service
├── libs/                          # 공유 라이브러리
│   └── common/                    # ✓ 서비스 간 공유 코드
├── sdk/                           # ○ 공용 SDK (외부 Agent용)
│   ├── java/
│   ├── python/
│   └── typescript/
├── infra/                         # 인프라 설정
│   ├── k8s/                       # ✓ K3s 매니페스트 (Kustomize)
│   └── docker-compose.dev.yml     # ✓ 로컬 개발용 인프라
├── scripts/                       # 운영 스크립트
│   ├── infra.sh                   # ✓ Docker Compose 관리 (up/down, dev)
│   ├── k8s.sh                     # ✓ k3d 클러스터 생성/삭제
│   └── docker.sh                  # ✓ 서비스별 Docker 이미지 빌드/클린
├── docs/                          # 문서
│   ├── spec/                      #   시스템 설계 문서
│   └── guides/                    #   개발자 가이드
├── package.json                   # Git hooks 전용 (devDependencies만)
├── build.gradle.kts               # Gradle 루트 — 공통 설정
└── settings.gradle.kts            # Gradle 모듈 등록
```

> `apps/fe`는 Node.js 프로젝트로 Gradle 빌드 대상이 아니다. `settings.gradle.kts`에는 Spring Boot 서비스(`apps/auth` 등)와 `libs/common`만 등록한다.

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
Spring Boot: 소스 → Gradle bootJar → Docker multi-stage → K3s Pod
FE:          소스 → pnpm build → Docker multi-stage (Nginx) → K3s Pod
```

### Docker 빌드

`scripts/docker.sh`로 서비스별 또는 전체 Docker 이미지를 빌드/클린한다.

```bash
./scripts/docker.sh build           # 전체 빌드
./scripts/docker.sh build auth      # auth만 빌드
./scripts/docker.sh build fe        # fe만 빌드
./scripts/docker.sh clean           # 전체 이미지 삭제
```

빌드된 이미지는 `bara/<service>:latest` 태그가 붙는다. `scripts/k8s.sh create` 시 k3d 클러스터에 로드되어 사용된다.

### K3s Pod 구성

| Pod               | 이미지              | 비고                                     |
| ----------------- | ------------------- | ---------------------------------------- |
| auth-service      | 직접 빌드           | Spring Boot jar                          |
| api-service       | 직접 빌드           | Spring Boot jar                          |
| scheduler-service | 직접 빌드           | Spring Boot jar                          |
| fe                | 직접 빌드           | 웹 FE                                    |
| telegram-service  | 직접 빌드           | Telegram Bot                             |
| mongodb           | `mongo:7`           | StatefulSet + PV                         |
| redis             | `redis:7-alpine`    |                                          |
| kafka             | `bitnami/kafka`     | KRaft 모드                               |
| fluent-bit        | `fluent/fluent-bit` | DaemonSet                                |
| loki              | `grafana/loki`      |                                          |
| grafana           | `grafana/grafana`   |                                          |

> 게이트웨이는 K3s에 내장된 Traefik이 담당한다. 별도 Nginx Pod 없이 K8s Gateway API(`gateway.yaml`, `routes.yaml`)로 라우팅 규칙을 선언한다.

> 인프라 상세(VM, K3s, Cloudflare 등)는 [인프라 문서](infrastructure.md) 참고.

## 로컬 개발 환경

두 가지 실행 방식을 지원한다.

### dev 모드 — 인프라만 Docker, 서비스는 IDE

```bash
./scripts/infra.sh up dev           # MongoDB, Redis, Kafka
./gradlew :apps:auth:bootRun        # Auth Service (8081)
cd apps/fe && pnpm dev              # Vite dev server (5173)
```

### k3d 모드 — 전체 K8s (Traefik 게이트웨이 포함)

```bash
./scripts/docker.sh build           # Docker 이미지 빌드
./scripts/k8s.sh create             # k3d 클러스터 생성 + 매니페스트 적용 (80)
```

Traefik(K3s 내장)이 ServiceLB(Klipper)를 통해 `localhost:80`에 바인딩된다.
