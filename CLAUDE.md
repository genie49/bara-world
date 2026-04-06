# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Google A2A(Agent-to-Agent) 프로토콜 기반 멀티 에이전트 플랫폼. Agent를 제외한 모든 서비스를 이 모노레포에서 관리한다.

**Tech Stack:** Kotlin + Spring Boot (API/Auth), Apache Kafka (KRaft), MongoDB, Redis, K3s, Cloudflare, Nginx

## Development Setup

```bash
npm install    # Husky Git hooks 자동 설정 (prepare 스크립트)

# 로컬 인프라 (MongoDB, Redis, Kafka)
./scripts/infra.sh up dev

# 전체 빌드
./gradlew build

# Auth Service 실행 (8081, .env 자동 로드)
./gradlew :apps:auth:bootRun

# Frontend 실행 (5173)
cd apps/fe && pnpm install && pnpm dev
```

- 루트 `package.json`은 **Git 도구 전용** (Husky/lint-staged). 프로젝트 의존성 아님.
- `apps/fe/package.json`은 **FE 독립 프로젝트**. `pnpm` 사용. Gradle 대상 아님.
- 서비스 빌드는 Gradle 멀티 프로젝트 (`settings.gradle.kts`에 모듈 등록).
- **JDK 21 필수**. Gradle daemon은 `gradle/gradle-daemon-jvm.properties`로 자동 선택.

### Docker 빌드 & 통합 테스트

```bash
./scripts/docker.sh build           # 전체 Docker 이미지 빌드 (auth, fe)
./scripts/docker.sh build auth      # 개별 빌드
./scripts/docker.sh clean           # 이미지 삭제

./scripts/infra.sh up test          # 인프라 + 서비스 + Nginx (localhost:80)
./scripts/infra.sh down test        # 전체 중지
```

- `docker.sh`로 빌드된 `bara/<service>:latest` 이미지를 `docker-compose.test.yml`이 참조.
- `infra.sh`는 루트 `.env` 파일을 자동으로 `--env-file`로 주입.
- Nginx 게이트웨이 설정: `infra/nginx/nginx.conf` (`/` → fe, `/auth/` → auth).

### CI/CD

- **CI**: PR → `develop`/`main` 시 자동 빌드/테스트 (path filter로 변경 서비스만)
  - `ci-auth.yml`: Gradle test + bootJar
  - `ci-fe.yml`: pnpm test + build
- **CD**: `main` 머지 시 자동 배포
  - 변경 감지 → Docker build → GCP Artifact Registry push → SSH → OCI VM(K3s) 배포
  - 상세 설계: `docs/spec/shared/ci-cd-design.md`
- **K8s 환경 분리**: Kustomize (`infra/k8s/overlays/dev/`, `infra/k8s/overlays/prod/`)

### Auth Service 첫 실행 (1회 세팅)

Auth Service는 RSA 키쌍과 Google OAuth Client가 필요하다. 상세: [`docs/guides/auth-local-setup.md`](docs/guides/auth-local-setup.md).

요약:

1. `cp .env.example .env`
2. `openssl`로 RSA 키쌍 생성 → base64 인코딩 → `.env`에 기입
3. Google Cloud Console에서 OAuth 2.0 Client ID 생성, redirect URI에 `http://localhost/auth/google/callback` 추가 → `.env`에 기입
4. `./scripts/docker.sh build` + `./scripts/infra.sh up test`
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

## Architecture

상세 설계: `docs/spec/overview.md` (서비스별 폴더 구조, ADR 11개)

핵심 구성:

- **Auth Service** — Google OAuth → JWT 발급, Provider 토큰 관리, Kafka OAUTHBEARER 토큰
- **API Service** — Agent 등록/조회, 태스크 처리(동기+SSE), Agent Card proxy
- **Scheduler Service** — cron 기반 반복 태스크 발행, 결과 전달
- **SDK** — Python, TypeScript, Java 공용 SDK (Kafka 통신, 인증, 메시지 포맷팅)
- **Clients** — Web FE, Telegram

모든 Agent 통신은 Kafka를 통해 비동기로 처리. Redis로 Agent 상태 관리(heartbeat) 및 SSE 버퍼링.

## Documentation

- `docs/spec/` — 시스템 설계 문서 (서비스별: auth/, api/, scheduler/, clients/ | 공통: shared/ | ADR: decisions/)
- `docs/guides/` — 개발자 가이드 (git-convention.md 등)
