# API Service Scaffold Design

## 개요

API Service의 스캐폴드를 구성한다. Auth Service의 검증된 패턴(Hexagonal + CQRS 패키지 구조, bara-spring-boot 컨벤션 플러그인, 멀티스테이지 Docker 빌드)을 참조하되, 빈 스캐폴드에서 새로 작성한다.

## 확정 사항

| 항목 | 값 |
|------|-----|
| 포트 | 8082 |
| context-path | `/api/core` |
| MongoDB | `bara` (Auth와 공용 DB — Auth도 `bara-auth` → `bara`로 변경) |
| 인프라 의존성 | MongoDB만 (Redis/Kafka는 이후 기능 구현 시 추가) |
| 스캐폴드 범위 | 서비스 기동 + health check + OpenAPI(Swagger UI) |

## 1. Gradle 모듈 등록

### settings.gradle.kts

`:apps:api` 모듈 추가:

```kotlin
include(
    ":apps:auth",
    ":apps:api",
    ":libs:common",
)
```

### apps/api/build.gradle.kts

Auth와 동일한 `bara-spring-boot` 컨벤션 플러그인 사용. 의존성은 최소한:

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

E2E 소스셋과 `.env` 로딩 bootRun task는 스캐폴드에서 제외. 이후 기능 구현 시 추가한다.

## 2. 소스 구조

### 패키지 레이아웃

Hexagonal + CQRS 패턴. 빈 패키지는 `.gitkeep`으로 유지:

```
apps/api/src/
├── main/
│   ├── kotlin/com/bara/api/
│   │   ├── BaraApiApplication.kt
│   │   ├── domain/
│   │   │   ├── model/.gitkeep
│   │   │   └── exception/.gitkeep
│   │   ├── application/
│   │   │   ├── port/
│   │   │   │   ├── in/
│   │   │   │   │   ├── command/.gitkeep
│   │   │   │   │   └── query/.gitkeep
│   │   │   │   └── out/.gitkeep
│   │   │   └── service/
│   │   │       ├── command/.gitkeep
│   │   │       └── query/.gitkeep
│   │   ├── adapter/
│   │   │   ├── in/rest/.gitkeep
│   │   │   └── out/persistence/.gitkeep
│   │   └── config/.gitkeep
│   └── resources/
│       └── application.yml
└── test/
    ├── kotlin/com/bara/api/
    │   └── BaraApiApplicationTest.kt
    └── resources/
        (application-test.yml 불필요 — TestPropertySource로 처리)
```

### BaraApiApplication.kt

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

### BaraApiApplicationTest.kt

MongoDB auto-config exclude로 외부 의존 없이 컨텍스트 로딩만 검증:

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

### application.yml

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

## 3. Dockerfile

Auth와 동일한 멀티스테이지 패턴. 경로만 `:apps:api`로 변경:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY build-logic/ build-logic/
COPY libs/common/build.gradle.kts libs/common/
COPY apps/api/build.gradle.kts apps/api/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:api:dependencies --no-daemon -q

COPY libs/ libs/
COPY apps/api/ apps/api/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:api:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/apps/api/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 4. K8s Manifest

### infra/k8s/base/core/api.yaml

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

### kustomization.yaml

`core/api.yaml` 리소스 추가.

## 5. Traefik 라우팅

`infra/k8s/base/gateway/routes.yaml`에 API Service 라우트 추가:

```yaml
# Swagger UI — public (인증 불필요)
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
# 나머지 — forwardAuth 보호
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

## 6. 기존 파일 변경

### Auth Service — MongoDB URI 변경 (`bara-auth` → `bara`)

- `apps/auth/src/main/resources/application.yml`: `mongodb://localhost:27017/bara-auth` → `mongodb://localhost:27017/bara`
- `infra/k8s/base/core/auth.yaml`: `SPRING_DATA_MONGODB_URI` 값 → `mongodb://mongodb.data.svc.cluster.local:27017/bara`
- E2E 테스트(`apps/auth/src/e2eTest/resources/application-e2e.yml`)는 MongoDB URI를 오버라이드하지 않음 (TestContainers 동적 주입). 변경 불필요

### scripts/docker.sh — api 서비스 등록

SERVICES 배열에 추가:

```bash
SERVICES=(
    "auth|apps/auth/Dockerfile"
    "api|apps/api/Dockerfile"
    "fe|apps/fe/Dockerfile"
)
```

### infra/k8s/base/kustomization.yaml

리소스에 `core/api.yaml` 추가.

## 7. 검증 기준

1. `./gradlew :apps:api:build` 성공
2. `./gradlew :apps:api:test` — `BaraApiApplicationTest.contextLoads()` 통과
3. `./gradlew :apps:api:bootRun` → `http://localhost:8082/api/core/actuator/health` 응답 확인
4. MongoDB 연결 시 `http://localhost:8082/api/core/swagger-ui/index.html` 접근 가능
5. `./scripts/docker.sh build api` 성공
6. Auth Service의 기존 테스트가 MongoDB URI 변경 후에도 통과
