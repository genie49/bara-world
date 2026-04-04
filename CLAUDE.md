# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Google A2A(Agent-to-Agent) 프로토콜 기반 멀티 에이전트 플랫폼. Agent를 제외한 모든 서비스를 이 모노레포에서 관리한다.

**Tech Stack:** Kotlin + Spring Boot (API/Auth), Apache Kafka (KRaft), MongoDB, Redis, K3s, Cloudflare, Nginx

## Development Setup

```bash
npm install    # Husky Git hooks 자동 설정 (prepare 스크립트)

# 로컬 인프라 시작 (MongoDB, Redis, Kafka)
docker compose -f infra/docker-compose.dev.yml up -d

# 전체 빌드
./gradlew build

# 개별 서비스 실행
./gradlew :apps:auth:bootRun
```

`package.json`은 Git 도구 전용(devDependencies만). 서비스 빌드는 Gradle 멀티 프로젝트로 관리한다. JDK 21 필수.

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
