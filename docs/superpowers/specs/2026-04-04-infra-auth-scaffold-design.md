# 인프라 세팅 + Auth Service 스캐폴딩 설계

## 목표

Gradle 멀티 프로젝트 빌드 환경, 로컬 개발용 Docker Compose, Auth Service 빈 스캐폴딩을 구성한다. 기능 구현은 포함하지 않는다.

## 범위

- Gradle 루트 설정 (settings, wrapper)
- Version Catalog (`gradle/libs.versions.toml`)
- Convention Plugins (`build-logic/`) — `bara-spring-boot`, `bara-kotlin-library`
- Docker Compose 로컬 개발 환경 (MongoDB, Redis, Kafka)
- Auth Service 빈 스캐폴딩 (헥사고날 + 경량 CQRS 패키지 구조)
- libs/common 빈 스캐폴딩

## 파일 구조

```
bara-world/
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml
├── build-logic/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/kotlin/
│       ├── bara-spring-boot.gradle.kts
│       └── bara-kotlin-library.gradle.kts
├── apps/
│   └── auth/
│       ├── build.gradle.kts
│       └── src/
│           ├── main/kotlin/com/bara/auth/
│           │   ├── BaraAuthApplication.kt
│           │   ├── domain/
│           │   │   ├── model/.gitkeep
│           │   │   └── exception/.gitkeep
│           │   ├── application/
│           │   │   ├── port/
│           │   │   │   ├── in/
│           │   │   │   │   ├── command/.gitkeep
│           │   │   │   │   └── query/.gitkeep
│           │   │   │   └── out/.gitkeep
│           │   │   └── service/
│           │   │       ├── command/.gitkeep
│           │   │       └── query/.gitkeep
│           │   ├── adapter/
│           │   │   ├── in/
│           │   │   │   └── rest/.gitkeep
│           │   │   └── out/
│           │   │       ├── persistence/.gitkeep
│           │   │       └── external/.gitkeep
│           │   └── config/.gitkeep
│           └── main/resources/
│               └── application.yml
├── libs/
│   └── common/
│       ├── build.gradle.kts
│       └── src/main/kotlin/com/bara/common/.gitkeep
├── infra/
│   └── docker-compose.dev.yml
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── gradlew.bat
```

## Gradle 설정

### Version Catalog

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.1.21"
spring-boot = "3.4.5"
spring-dependency-management = "1.1.7"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-mongodb = { module = "org.springframework.boot:spring-boot-starter-data-mongodb" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
```

### Convention Plugin: bara-spring-boot

모든 Spring Boot 서비스(auth, api, scheduler)가 적용하는 공통 설정:

- Kotlin JVM + Spring 플러그인
- Spring Boot + dependency management 플러그인
- Java 21 타겟
- JUnit 5 + MockK 테스트 의존성

### Convention Plugin: bara-kotlin-library

libs/common 등 순수 Kotlin 라이브러리용:

- Kotlin JVM 플러그인만
- Java 21 타겟
- Spring Boot 플러그인 없음 (실행 가능 jar 아님)

### 서비스 build.gradle.kts

```kotlin
// apps/auth/build.gradle.kts
plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongodb)
}
```

## Docker Compose (로컬 개발)

```yaml
# infra/docker-compose.dev.yml
services:
  mongodb:
    image: mongo:7
    ports: ['27017:27017']
    volumes:
      - mongodb_data:/data/db

  redis:
    image: redis:7-alpine
    ports: ['6379:6379']

  kafka:
    image: bitnami/kafka:3.9
    ports: ['9092:9092']
    environment:
      - KAFKA_CFG_NODE_ID=0
      - KAFKA_CFG_PROCESS_ROLES=controller,broker
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER

volumes:
  mongodb_data:
```

설계 포인트:

- MongoDB만 volume 마운트 — 개발 데이터 유지
- Kafka KRaft 모드 — ZooKeeper 없이 단일 노드, 운영 환경과 동일 모드
- SASL/TLS 없음 — 로컬 개발용, 운영과의 차이는 Spring 프로필로 분리
- 포트는 기본값 — 로컬 전용

## Auth Service 스캐폴딩

### Application 클래스

```kotlin
@SpringBootApplication
class BaraAuthApplication

fun main(args: Array<String>) {
    runApplication<BaraAuthApplication>(*args)
}
```

### application.yml

```yaml
spring:
  application:
    name: bara-auth
  data:
    mongodb:
      uri: mongodb://localhost:27017/bara-auth

server:
  port: 8081
```

### 패키지 구조

서비스 아키텍처 문서에 정의된 헥사고날 + 경량 CQRS 구조를 빈 패키지(.gitkeep)로 생성한다. 기능 구현 시 해당 패키지에 파일을 추가하는 방식.

## libs/common 스캐폴딩

빈 Kotlin 라이브러리. 공유 코드(JSON-RPC 에러 포맷, Kafka 메시지 DTO, JWT 검증)는 Auth Service 기능 구현 시 필요에 따라 추가한다.

## 성공 기준

- `./gradlew :apps:auth:bootRun`으로 Auth Service가 8081 포트에서 기동
- `docker compose -f infra/docker-compose.dev.yml up -d`로 MongoDB, Redis, Kafka 기동
- `./gradlew build`로 전체 빌드 성공
