# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Google A2A(Agent-to-Agent) 프로토콜 기반 멀티 에이전트 플랫폼. Agent를 제외한 모든 서비스를 이 모노레포에서 관리한다.

**Tech Stack:** Kotlin + Spring Boot (API/Auth), Apache Kafka (KRaft), MongoDB, Redis, K3s, Cloudflare, Traefik + K8s Gateway API

## Development Setup

```bash
npm install    # Husky Git hooks 자동 설정 (prepare 스크립트)

# 로컬 인프라 (MongoDB, Redis, Kafka)
./scripts/infra.sh up dev

# 전체 빌드
./gradlew build

# Auth Service 실행 (8081, context-path: /api/auth, .env 자동 로드)
./gradlew :apps:auth:bootRun

# Frontend 실행 (5173)
cd apps/fe && pnpm install && pnpm dev
```

- 루트 `package.json`은 **Git 도구 전용** (Husky/lint-staged). 프로젝트 의존성 아님.
- `apps/fe/package.json`은 **FE 독립 프로젝트**. `pnpm` 사용. Gradle 대상 아님.
- 서비스 빌드는 Gradle 멀티 프로젝트 (`settings.gradle.kts`에 모듈 등록).
- **JDK 21 필수**. Gradle daemon은 `gradle/gradle-daemon-jvm.properties`로 자동 선택.

### Docker 빌드 & K8s 로컬 테스트

```bash
./scripts/docker.sh build           # 전체 Docker 이미지 빌드 (auth, fe)
./scripts/docker.sh build auth      # 개별 빌드
./scripts/docker.sh clean           # 이미지 삭제

./scripts/k8s.sh create             # k3d 클러스터 생성 + 이미지 로드 + 배포 (localhost:80)
./scripts/k8s.sh apply              # K8s manifest만 재적용 (yaml 수정 시)
./scripts/k8s.sh load               # 이미지 재로드 + Pod 자동 재시작
./scripts/k8s.sh stop               # 클러스터 중지
./scripts/k8s.sh start              # 클러스터 재시작
./scripts/k8s.sh delete             # 클러스터 삭제
```

- 코드 수정 후 반영: `./scripts/docker.sh build auth && ./scripts/k8s.sh load`
- manifest 수정 후 반영: `./scripts/k8s.sh apply`
- 게이트웨이: Traefik (K3s 내장) + Gateway API (`infra/k8s/base/gateway/`)
- 로컬 인프라(MongoDB, Redis, Kafka)만 필요 시: `./scripts/infra.sh up`
- k3d 클러스터에서 MongoDB(`localhost:27017`), Redis(`localhost:6379`) 직접 접속 가능

### CI/CD

- **CI**: PR → `develop`/`main` 시 자동 빌드/테스트 (path filter로 변경 서비스만)
  - `ci-auth.yml`: Gradle test + bootJar
  - `ci-fe.yml`: pnpm test + build
- **CD**: `main` 머지 시 자동 배포
  - 변경 감지 → Docker build → GCP Artifact Registry push → SSH → OCI VM(K3s) 배포
  - Secret 관리: GCP Parameter Manager → `.env.prod` → `auth-secrets`(core ns) + `data-secrets`(data ns)
  - 상세 설계: `docs/spec/shared/ci-cd-design.md`
- **K8s 환경 분리**: Kustomize (`infra/k8s/overlays/dev/`, `infra/k8s/overlays/prod/`)

### DB 인증

- **prod만 인증 적용** — dev(k3d)는 인증 없이 동작
- **MongoDB**: `MONGO_INITDB_ROOT_USERNAME/PASSWORD`로 root 계정 생성. prod overlay에서 StatefulSet에 주입
- **Redis**: `--requirepass`로 비밀번호 설정. prod overlay에서 Deployment args에 주입
- **Auth Service**: prod overlay에서 `SPRING_DATA_MONGODB_URI`(인증 URI)와 `SPRING_DATA_REDIS_PASSWORD`를 Secret에서 주입
- **credential 관리**: `.env.prod` → GCP Parameter Manager → deploy 시 `data-secrets`(data ns) + `auth-secrets`(core ns) 자동 생성
- **외부 접속**: prod에서 MongoDB(30017), Redis(30379) NodePort로 외부 접속 가능 (인증 필수)

### Auth Service 첫 실행 (1회 세팅)

Auth Service는 RSA 키쌍과 Google OAuth Client가 필요하다. 상세: [`docs/guides/auth-local-setup.md`](docs/guides/auth-local-setup.md).

요약:

1. `cp .env.example .env`
2. `openssl`로 RSA 키쌍 생성 → base64 인코딩 → `.env`에 기입
3. Google Cloud Console에서 OAuth 2.0 Client ID 생성, redirect URI에 `http://localhost/auth/google/callback` 추가 → `.env`에 기입
4. `./scripts/docker.sh build` + `./scripts/k8s.sh create`
5. `http://localhost/` 접속하여 Google 로그인 동작 확인

`.env` 파일은 `apps/auth/build.gradle.kts`의 `bootRun` task가 읽어 환경변수로 주입한다 (별도 라이브러리 사용 안 함).

## Testing

```bash
./gradlew :apps:auth:test         # 백엔드 단위/슬라이스 테스트
cd apps/fe && pnpm test            # FE Vitest
```

Auth 백엔드 테스트는 MongoDB/Redis 없이 동작 (자동 구성 exclude + `@MockkBean` 교체).

## Git Convention

커밋/브랜치 규칙은 Husky hooks로 자동 검증된다. 커밋 전에 반드시 `docs/guides/git-convention.md`를 확인할 것.

### 커밋 시 필수 규칙

- **`Co-Authored-By` 트레일러 금지** — `.husky/commit-msg` 훅이 차단함. 커밋 메시지에 절대 포함하지 말 것.
- **`--no-verify` 사용 금지** — Git hooks를 우회하면 안 됨.
- **Subagent/Agent 호출 시에도 반드시 전달할 것:**
  - "커밋 메시지에 Co-Authored-By 트레일러를 붙이지 마라."
  - "git commit 시 --no-verify 플래그를 사용하지 마라."

## Logging

- Wide Event 패턴 사용 — 요청당 서비스당 단일 구조화 로그 출력 (dev: 텍스트, prod: JSON)
- 로깅 공통 모듈: `libs/common/src/main/kotlin/com/bara/common/logging/`
- `WideEvent.put()` — 구조화 필드, `WideEvent.message()` — 사람이 읽을 수 있는 로그 메시지
- 새 port/adapter/엔드포인트 추가 시 반드시 `docs/guides/logging/README.md` 참조
- 해당 흐름의 비즈니스 컨텍스트 로깅 필드를 정의하고 `docs/guides/logging/flows/`에 문서 추가
- **PR 전 로깅 체크리스트:**
  - 새 엔드포인트/흐름에 `WideEvent.put()` + `WideEvent.message()` 추가했는가?
  - `outcome` 필드가 성공/실패 경로 모두에 설정되었는가?
  - `user_id` 등 비즈니스 컨텍스트 필드가 포함되었는가?
  - `docs/guides/logging/flows/`에 해당 흐름 문서를 추가/업데이트했는가?

## Architecture

상세 설계: `docs/spec/overview.md` (서비스별 폴더 구조, ADR 11개)

핵심 구성:

- **Auth Service** — Google OAuth → Access/Refresh Token 발급, Provider API Key 관리, Kafka OAUTHBEARER 토큰, Traefik forwardAuth (`GET /api/auth/validate`)
- **API Service** — Agent 등록/조회/삭제 (MongoDB CRUD ✓), Agent Card ✓, 태스크 처리(동기+SSE) ○, Kafka 연동 ○
- **Scheduler Service** — cron 기반 반복 태스크 발행, 결과 전달
- **SDK** — Python, TypeScript, Java 공용 SDK (Kafka 통신, 인증, 메시지 포맷팅)
- **Clients** — Web FE, Telegram

모든 Agent 통신은 Kafka를 통해 비동기로 처리. Redis로 Agent 상태 관리(heartbeat) 및 SSE 버퍼링.

## Documentation

- `docs/spec/` — 시스템 설계 문서 (서비스별: auth/, api/, scheduler/, clients/ | 공통: shared/ | ADR: decisions/)
- `docs/guides/` — 개발자 가이드 (git-convention.md 등)
