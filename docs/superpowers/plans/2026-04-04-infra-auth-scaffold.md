# 인프라 세팅 + Auth Service 스캐폴딩 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gradle 멀티 프로젝트 빌드 환경, 로컬 개발용 Docker Compose, Auth Service 빈 스캐폴딩을 구성한다.

**Architecture:** Convention Plugin 기반 Gradle 멀티 프로젝트. `build-logic/`에 공통 설정을 플러그인으로 추출하고, 각 서비스는 플러그인만 적용. Version Catalog(`libs.versions.toml`)로 의존성 버전 중앙 관리.

**Tech Stack:** Kotlin 2.1.20, Spring Boot 3.4.4, Java 21, Gradle 8.12 (Kotlin DSL), Docker Compose

---

## File Structure

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
│           │   ├── domain/model/.gitkeep
│           │   ├── domain/exception/.gitkeep
│           │   ├── application/port/in/command/.gitkeep
│           │   ├── application/port/in/query/.gitkeep
│           │   ├── application/port/out/.gitkeep
│           │   ├── application/service/command/.gitkeep
│           │   ├── application/service/query/.gitkeep
│           │   ├── adapter/in/rest/.gitkeep
│           │   ├── adapter/out/persistence/.gitkeep
│           │   ├── adapter/out/external/.gitkeep
│           │   └── config/.gitkeep
│           ├── main/resources/application.yml
│           └── test/kotlin/com/bara/auth/BaraAuthApplicationTest.kt
├── libs/
│   └── common/
│       ├── build.gradle.kts
│       └── src/main/kotlin/com/bara/common/.gitkeep
├── infra/
│   └── docker-compose.dev.yml
├── .gitignore                    (수정 — Gradle 빌드 아티팩트 추가)
├── build.gradle.kts              (신규)
├── settings.gradle.kts           (신규)
├── gradlew                       (자동 생성)
└── gradlew.bat                   (자동 생성)
```

---

### Task 1: Gradle Wrapper 생성

**Files:**

- Create: `gradle/wrapper/gradle-wrapper.jar` (자동 생성)
- Create: `gradle/wrapper/gradle-wrapper.properties` (자동 생성)
- Create: `gradlew` (자동 생성)
- Create: `gradlew.bat` (자동 생성)
- Modify: `.gitignore`

- [ ] **Step 1: Gradle 설치 확인**

Run: `gradle --version`

Gradle가 설치되어 있지 않으면:

```bash
brew install gradle
```

- [ ] **Step 2: Gradle Wrapper 생성**

```bash
cd /Users/genie/workspace/bara-world
gradle wrapper --gradle-version 8.12
```

Expected: `gradle/wrapper/`, `gradlew`, `gradlew.bat` 파일 생성

- [ ] **Step 3: Wrapper 동작 확인**

Run: `./gradlew --version`
Expected: `Gradle 8.12` 출력

- [ ] **Step 4: .gitignore에 Gradle 빌드 아티팩트 추가**

`.gitignore` 파일에 다음 추가:

```
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 5: 커밋**

```bash
git add gradlew gradlew.bat gradle/ .gitignore
git commit -m "chore(infra): Gradle Wrapper 8.12 추가"
```

---

### Task 2: Version Catalog + Convention Plugins (build-logic)

**Files:**

- Create: `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/bara-spring-boot.gradle.kts`
- Create: `build-logic/src/main/kotlin/bara-kotlin-library.gradle.kts`

- [ ] **Step 1: Version Catalog 생성**

Create `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.1.20"
spring-boot = "3.4.4"
spring-dependency-management = "1.1.7"
mockk = "1.13.16"

[libraries]
# Application dependencies
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-mongodb = { module = "org.springframework.boot:spring-boot-starter-data-mongodb" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }

# Gradle plugin artifacts (for build-logic)
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-allopen = { module = "org.jetbrains.kotlin:kotlin-allopen", version.ref = "kotlin" }
spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "spring-boot" }
spring-dependency-management-plugin = { module = "io.spring.gradle:dependency-management-plugin", version.ref = "spring-dependency-management" }
```

- [ ] **Step 2: build-logic/settings.gradle.kts 생성**

```kotlin
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

- [ ] **Step 3: build-logic/build.gradle.kts 생성**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
}
```

- [ ] **Step 4: bara-spring-boot Convention Plugin 생성**

Create `build-logic/src/main/kotlin/bara-spring-boot.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.bara"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 5: bara-kotlin-library Convention Plugin 생성**

Create `build-logic/src/main/kotlin/bara-kotlin-library.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.bara"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 6: 커밋**

```bash
git add gradle/libs.versions.toml build-logic/
git commit -m "chore(infra): Version Catalog + Convention Plugins 추가"
```

---

### Task 3: Root Gradle 설정 + libs/common

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `libs/common/build.gradle.kts`
- Create: `libs/common/src/main/kotlin/com/bara/common/.gitkeep`

- [ ] **Step 1: Root settings.gradle.kts 생성**

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "bara-world"

include(
    ":libs:common",
)
```

- [ ] **Step 2: Root build.gradle.kts 생성**

```kotlin
// Root project — 공통 설정은 build-logic convention plugins에서 관리
```

- [ ] **Step 3: libs/common 생성**

Create `libs/common/build.gradle.kts`:

```kotlin
plugins {
    id("bara-kotlin-library")
}
```

Create `libs/common/src/main/kotlin/com/bara/common/.gitkeep`:

```

```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew :libs:common:build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add settings.gradle.kts build.gradle.kts libs/
git commit -m "chore(infra): Root Gradle 설정 + libs/common 모듈 추가"
```

---

### Task 4: Auth Service 스캐폴딩

**Files:**

- Modify: `settings.gradle.kts` (`:apps:auth` 추가)
- Create: `apps/auth/build.gradle.kts`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt`
- Create: `apps/auth/src/main/resources/application.yml`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/BaraAuthApplicationTest.kt`
- Create: 빈 패키지 디렉토리 (.gitkeep)

- [ ] **Step 1: settings.gradle.kts에 auth 모듈 추가**

`settings.gradle.kts` 수정:

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "bara-world"

include(
    ":apps:auth",
    ":libs:common",
)
```

- [ ] **Step 2: apps/auth/build.gradle.kts 생성**

```kotlin
plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongodb)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 3: Application 클래스 생성**

Create `apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt`:

```kotlin
package com.bara.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BaraAuthApplication

fun main(args: Array<String>) {
    runApplication<BaraAuthApplication>(*args)
}
```

- [ ] **Step 4: application.yml 생성**

Create `apps/auth/src/main/resources/application.yml`:

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

- [ ] **Step 5: 스모크 테스트 생성**

Create `apps/auth/src/test/kotlin/com/bara/auth/BaraAuthApplicationTest.kt`:

```kotlin
package com.bara.auth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(properties = [
    "spring.data.mongodb.uri=mongodb://localhost:27017/bara-auth-test",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
])
class BaraAuthApplicationTest {

    @Test
    fun contextLoads() {
    }
}
```

- [ ] **Step 6: 빈 헥사고날 패키지 생성**

```bash
cd /Users/genie/workspace/bara-world

# Domain
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/domain/model
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/domain/exception
touch apps/auth/src/main/kotlin/com/bara/auth/domain/model/.gitkeep
touch apps/auth/src/main/kotlin/com/bara/auth/domain/exception/.gitkeep

# Application - Port IN (Command/Query)
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query
touch apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/.gitkeep
touch apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/.gitkeep

# Application - Port OUT
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/application/port/out
touch apps/auth/src/main/kotlin/com/bara/auth/application/port/out/.gitkeep

# Application - Service (Command/Query)
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/application/service/command
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/application/service/query
touch apps/auth/src/main/kotlin/com/bara/auth/application/service/command/.gitkeep
touch apps/auth/src/main/kotlin/com/bara/auth/application/service/query/.gitkeep

# Adapter IN
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest
touch apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/.gitkeep

# Adapter OUT
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external
touch apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/.gitkeep
touch apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/.gitkeep

# Config
mkdir -p apps/auth/src/main/kotlin/com/bara/auth/config
touch apps/auth/src/main/kotlin/com/bara/auth/config/.gitkeep
```

- [ ] **Step 7: 빌드 확인**

Run: `./gradlew :apps:auth:build`
Expected: `BUILD SUCCESSFUL` (테스트는 MongoDB 없이도 통과해야 함 — MongoAutoConfiguration 제외됨)

- [ ] **Step 8: 커밋**

```bash
git add settings.gradle.kts apps/
git commit -m "chore(infra): Auth Service 스캐폴딩 (헥사고날 + CQRS 패키지 구조)"
```

---

### Task 5: Docker Compose (로컬 개발 환경)

**Files:**

- Create: `infra/docker-compose.dev.yml`

- [ ] **Step 1: docker-compose.dev.yml 생성**

Create `infra/docker-compose.dev.yml`:

```yaml
services:
  mongodb:
    image: mongo:7
    ports:
      - '27017:27017'
    volumes:
      - mongodb_data:/data/db

  redis:
    image: redis:7-alpine
    ports:
      - '6379:6379'

  kafka:
    image: bitnami/kafka:3.9
    ports:
      - '9092:9092'
    environment:
      - KAFKA_CFG_NODE_ID=0
      - KAFKA_CFG_PROCESS_ROLES=controller,broker
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092

volumes:
  mongodb_data:
```

- [ ] **Step 2: 커밋**

```bash
git add infra/
git commit -m "chore(infra): Docker Compose 로컬 개발 환경 추가 (MongoDB, Redis, Kafka)"
```

---

### Task 6: End-to-End 검증

- [ ] **Step 1: Docker Compose 시작**

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

Expected: 3개 컨테이너 모두 healthy/running

```bash
docker compose -f infra/docker-compose.dev.yml ps
```

Expected:

```
NAME        SERVICE    STATUS
...-mongodb-1    mongodb    running
...-redis-1      redis      running
...-kafka-1      kafka      running
```

- [ ] **Step 2: 전체 Gradle 빌드**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Auth Service 기동 확인**

```bash
./gradlew :apps:auth:bootRun
```

Expected: 콘솔에 다음과 유사한 출력:

```
Started BaraAuthApplication in X.XXX seconds
Tomcat started on port 8081
```

`Ctrl+C`로 종료.

- [ ] **Step 4: Docker Compose 정리**

```bash
docker compose -f infra/docker-compose.dev.yml down
```

- [ ] **Step 5: CLAUDE.md 업데이트**

`CLAUDE.md`의 Development Setup 섹션에 Gradle 명령어 추가:

````markdown
## Development Setup

\```bash
npm install # Husky Git hooks 자동 설정 (prepare 스크립트)

# 로컬 인프라 시작 (MongoDB, Redis, Kafka)

docker compose -f infra/docker-compose.dev.yml up -d

# 전체 빌드

./gradlew build

# 개별 서비스 실행

./gradlew :apps:auth:bootRun
\```
````

- [ ] **Step 6: 최종 커밋**

```bash
git add CLAUDE.md
git commit -m "docs(docs): CLAUDE.md에 Gradle/Docker 개발 환경 명령어 추가"
```
