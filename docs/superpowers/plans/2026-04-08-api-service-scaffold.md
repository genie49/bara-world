# API Service Scaffold 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** API Service의 Gradle 모듈, Hexagonal 패키지 구조, Dockerfile, K8s manifest, Traefik 라우팅을 구성하고, 서비스 기동 + health check + Swagger UI가 동작하는 스캐폴드를 완성한다.

**Architecture:** Auth Service와 동일한 `bara-spring-boot` 컨벤션 플러그인 기반. Hexagonal + CQRS 패키지 구조. MongoDB만 연결하고 Redis/Kafka는 이후 추가. 공용 DB명 `bara`로 Auth와 통합.

**Tech Stack:** Kotlin 2.1.20, Spring Boot 3.4.4, Java 21, Gradle 8.12, Docker, K3s/Kustomize, Traefik

---

## File Structure

```
bara-world/
├── apps/api/
│   ├── build.gradle.kts                          (신규)
│   ├── Dockerfile                                 (신규)
│   └── src/
│       ├── main/kotlin/com/bara/api/
│       │   ├── BaraApiApplication.kt              (신규)
│       │   ├── domain/model/.gitkeep              (신규)
│       │   ├── domain/exception/.gitkeep          (신규)
│       │   ├── application/port/in/command/.gitkeep (신규)
│       │   ├── application/port/in/query/.gitkeep (신규)
│       │   ├── application/port/out/.gitkeep      (신규)
│       │   ├── application/service/command/.gitkeep (신규)
│       │   ├── application/service/query/.gitkeep (신규)
│       │   ├── adapter/in/rest/.gitkeep           (신규)
│       │   ├── adapter/out/persistence/.gitkeep   (신규)
│       │   └── config/.gitkeep                    (신규)
│       ├── main/resources/application.yml         (신규)
│       └── test/kotlin/com/bara/api/
│           └── BaraApiApplicationTest.kt          (신규)
├── infra/k8s/base/
│   ├── core/api.yaml                              (신규)
│   ├── gateway/routes.yaml                        (수정)
│   └── kustomization.yaml                         (수정)
├── apps/auth/src/main/resources/application.yml   (수정 — DB명 변경)
├── infra/k8s/base/core/auth.yaml                  (수정 — DB명 변경)
├── scripts/docker.sh                              (수정 — api 등록)
└── settings.gradle.kts                            (수정 — 모듈 등록)
```

---

### Task 1: Gradle 모듈 등록 + build.gradle.kts

**Files:**
- Modify: `settings.gradle.kts`
- Create: `apps/api/build.gradle.kts`

- [ ] **Step 1: settings.gradle.kts에 API 모듈 추가**

`settings.gradle.kts`를 수정하여 `:apps:api` 모듈을 등록한다:

```kotlin
pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "bara-world"

include(
    ":apps:auth",
    ":apps:api",
    ":libs:common",
)
```

- [ ] **Step 2: build.gradle.kts 작성**

`apps/api/build.gradle.kts`를 생성한다:

```kotlin
plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}
```

- [ ] **Step 3: Gradle sync 확인**

Run: `./gradlew :apps:api:dependencies --no-daemon -q | head -5`

Expected: 의존성 트리 출력 (에러 없이)

- [ ] **Step 4: 커밋**

```bash
git add settings.gradle.kts apps/api/build.gradle.kts
git commit -m "chore(api): register API service Gradle module"
```

---

### Task 2: Application 클래스 + 패키지 구조

**Files:**
- Create: `apps/api/src/main/kotlin/com/bara/api/BaraApiApplication.kt`
- Create: 11개 `.gitkeep` 파일 (빈 패키지)

- [ ] **Step 1: Application 클래스 작성**

`apps/api/src/main/kotlin/com/bara/api/BaraApiApplication.kt`:

```kotlin
package com.bara.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BaraApiApplication

fun main(args: Array<String>) {
    runApplication<BaraApiApplication>(*args)
}
```

- [ ] **Step 2: Hexagonal 패키지 구조 생성**

빈 패키지마다 `.gitkeep` 파일을 생성한다:

```bash
BASE="apps/api/src/main/kotlin/com/bara/api"
for dir in \
    domain/model \
    domain/exception \
    application/port/in/command \
    application/port/in/query \
    application/port/out \
    application/service/command \
    application/service/query \
    adapter/in/rest \
    adapter/out/persistence \
    config; do
    mkdir -p "$BASE/$dir"
    touch "$BASE/$dir/.gitkeep"
done
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add apps/api/src/main/
git commit -m "feat(api): add BaraApiApplication and hexagonal package structure"
```

---

### Task 3: application.yml + 스모크 테스트

**Files:**
- Create: `apps/api/src/main/resources/application.yml`
- Create: `apps/api/src/test/kotlin/com/bara/api/BaraApiApplicationTest.kt`

- [ ] **Step 1: 스모크 테스트 작성**

`apps/api/src/test/kotlin/com/bara/api/BaraApiApplicationTest.kt`:

```kotlin
package com.bara.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
    ]
)
class BaraApiApplicationTest {

    @Test
    fun contextLoads() {
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:api:test --no-daemon`

Expected: FAIL — `application.yml`이 없어서 설정 로딩 실패

- [ ] **Step 3: application.yml 작성**

`apps/api/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: bara-api
  data:
    mongodb:
      uri: mongodb://localhost:27017/bara

server:
  port: 8082
  servlet:
    context-path: /api/core

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

bara:
  openapi:
    title: Bara API
    version: 1.0.0
    description: Agent Registry & A2A Gateway API
```

- [ ] **Step 4: 테스트 실행 — 성공 확인**

Run: `./gradlew :apps:api:test --no-daemon`

Expected: `BUILD SUCCESSFUL` — `BaraApiApplicationTest > contextLoads()` PASSED

- [ ] **Step 5: 커밋**

```bash
git add apps/api/src/main/resources/application.yml apps/api/src/test/
git commit -m "feat(api): add application.yml and smoke test"
```

---

### Task 4: MongoDB URI 통합 (`bara-auth` → `bara`)

**Files:**
- Modify: `apps/auth/src/main/resources/application.yml`
- Modify: `infra/k8s/base/core/auth.yaml`

- [ ] **Step 1: Auth application.yml 수정**

`apps/auth/src/main/resources/application.yml`에서 MongoDB URI를 변경한다:

변경 전:
```yaml
      uri: mongodb://localhost:27017/bara-auth
```

변경 후:
```yaml
      uri: mongodb://localhost:27017/bara
```

- [ ] **Step 2: Auth K8s manifest 수정**

`infra/k8s/base/core/auth.yaml`에서 `SPRING_DATA_MONGODB_URI` 값을 변경한다:

변경 전:
```yaml
            - name: SPRING_DATA_MONGODB_URI
              value: 'mongodb://mongodb.data.svc.cluster.local:27017/bara-auth'
```

변경 후:
```yaml
            - name: SPRING_DATA_MONGODB_URI
              value: 'mongodb://mongodb.data.svc.cluster.local:27017/bara'
```

- [ ] **Step 3: Auth 기존 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --no-daemon`

Expected: `BUILD SUCCESSFUL` — 모든 기존 테스트 통과 (테스트는 MongoDB auto-config exclude이므로 URI 변경에 영향 없음)

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/resources/application.yml infra/k8s/base/core/auth.yaml
git commit -m "chore(infra): unify MongoDB database name to bara"
```

---

### Task 5: Dockerfile

**Files:**
- Create: `apps/api/Dockerfile`

- [ ] **Step 1: Dockerfile 작성**

`apps/api/Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 1) Gradle wrapper + 빌드 스크립트 (변경 드묾 → 레이어 캐시)
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY build-logic/ build-logic/
COPY libs/common/build.gradle.kts libs/common/
COPY apps/api/build.gradle.kts apps/api/

# 2) 의존성 다운로드 (Gradle 캐시를 BuildKit 마운트로 유지)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:api:dependencies --no-daemon -q

# 3) 소스 복사 + 빌드
COPY libs/ libs/
COPY apps/api/ apps/api/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:api:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/apps/api/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: docker.sh에 api 서비스 등록**

`scripts/docker.sh`의 SERVICES 배열을 수정한다:

변경 전:
```bash
SERVICES=(
    "auth|apps/auth/Dockerfile"
    "fe|apps/fe/Dockerfile"
)
```

변경 후:
```bash
SERVICES=(
    "auth|apps/auth/Dockerfile"
    "api|apps/api/Dockerfile"
    "fe|apps/fe/Dockerfile"
)
```

- [ ] **Step 3: Docker 빌드 확인**

Run: `./scripts/docker.sh build api`

Expected: `✓ bara/api:latest 빌드 완료`

- [ ] **Step 4: 커밋**

```bash
git add apps/api/Dockerfile scripts/docker.sh
git commit -m "chore(api): add Dockerfile and register in docker.sh"
```

---

### Task 6: K8s Manifest + Traefik 라우팅

**Files:**
- Create: `infra/k8s/base/core/api.yaml`
- Modify: `infra/k8s/base/kustomization.yaml`
- Modify: `infra/k8s/base/gateway/routes.yaml`

- [ ] **Step 1: API Service K8s manifest 작성**

`infra/k8s/base/core/api.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api
  namespace: core
spec:
  replicas: 1
  selector:
    matchLabels:
      app: api
  template:
    metadata:
      labels:
        app: api
    spec:
      containers:
        - name: api
          image: bara/api:latest
          ports:
            - containerPort: 8082
          env:
            - name: SPRING_DATA_MONGODB_URI
              value: 'mongodb://mongodb.data.svc.cluster.local:27017/bara'
            - name: APP_VERSION
              value: 'local'
            - name: SERVICE_NAME
              value: 'bara-api'
          readinessProbe:
            httpGet:
              path: /api/core/actuator/health/readiness
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /api/core/actuator/health/liveness
              port: 8082
            initialDelaySeconds: 60
            periodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: api
  namespace: core
spec:
  selector:
    app: api
  ports:
    - port: 8082
      targetPort: 8082
```

- [ ] **Step 2: kustomization.yaml에 리소스 추가**

`infra/k8s/base/kustomization.yaml`에 `core/api.yaml`을 추가한다:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - namespaces.yaml
  - data/mongodb.yaml
  - data/redis.yaml
  - data/kafka.yaml
  - core/auth.yaml
  - core/api.yaml
  - core/fe.yaml
  - gateway/middlewares.yaml
  - gateway/routes.yaml
```

- [ ] **Step 3: Traefik 라우팅 추가**

`infra/k8s/base/gateway/routes.yaml` 파일 맨 끝에 API Service 라우트를 추가한다:

```yaml
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-public
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core/swagger-ui`) || PathPrefix(`/api/core/v3/api-docs`)
      kind: Rule
      middlewares:
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-protected
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/core`)
      kind: Rule
      middlewares:
        - name: auth-forward
          namespace: core
        - name: cors
          namespace: core
      services:
        - name: api
          port: 8082
```

- [ ] **Step 4: 커밋**

```bash
git add infra/k8s/base/core/api.yaml infra/k8s/base/kustomization.yaml infra/k8s/base/gateway/routes.yaml
git commit -m "feat(infra): add API service K8s manifest and Traefik routes"
```

---

### Task 7: 전체 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드 확인**

Run: `./gradlew build --no-daemon`

Expected: `BUILD SUCCESSFUL` — auth와 api 모두 빌드 + 테스트 통과

- [ ] **Step 2: API Service 로컬 기동 확인**

MongoDB가 실행 중이어야 한다. 없으면 `./scripts/infra.sh up dev`로 시작.

Run: `./gradlew :apps:api:bootRun --no-daemon`

별도 터미널에서 확인:

```bash
curl -s http://localhost:8082/api/core/actuator/health | python3 -m json.tool
```

Expected:
```json
{
    "status": "UP"
}
```

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/api/core/swagger-ui/index.html
```

Expected: `200`

- [ ] **Step 3: 기동 확인 후 서비스 종료**

Ctrl+C로 bootRun을 종료한다.
