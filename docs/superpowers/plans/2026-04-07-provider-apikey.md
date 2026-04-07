# Provider + API Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **IMPORTANT:** 커밋 메시지에 Co-Authored-By 트레일러를 절대 붙이지 마라. git commit 시 --no-verify 플래그를 사용하지 마라.

**Goal:** Provider 등록 + API Key CRUD (발급/목록/이름수정/폐기) 기능을 Auth Service에 추가한다.

**Architecture:** Provider 도메인 모델과 ApiKey 도메인 모델을 별도 MongoDB 컬렉션으로 분리하고, 헥사고날 아키텍처 패턴을 따라 포트/어댑터/서비스를 구현한다. 모든 Provider 엔드포인트는 User Access Token 인증이 필요하며, Controller에서 JwtVerifier.verify()로 userId를 추출한다.

**Tech Stack:** Kotlin, Spring Boot, Spring Data MongoDB, Auth0 java-jwt (JwtVerifier), MockK

**Spec:** `docs/superpowers/specs/2026-04-07-provider-auth-design.md` 섹션 2~5

---

## File Structure

### 새로 생성

| 파일 | 책임 |
|------|------|
| `domain/model/Provider.kt` | Provider 도메인 모델 + ProviderStatus enum |
| `domain/model/ApiKey.kt` | ApiKey 도메인 모델 |
| `domain/exception/ProviderAlreadyExistsException.kt` | User가 이미 Provider를 등록한 경우 |
| `domain/exception/ProviderNotActiveException.kt` | Provider가 ACTIVE가 아닌 경우 |
| `domain/exception/ProviderNotFoundException.kt` | Provider를 찾을 수 없는 경우 |
| `domain/exception/ApiKeyLimitExceededException.kt` | API Key 5개 초과 |
| `domain/exception/ApiKeyNotFoundException.kt` | API Key를 찾을 수 없는 경우 |
| `application/port/in/command/RegisterProviderUseCase.kt` | Provider 등록 유스케이스 |
| `application/port/in/command/IssueApiKeyUseCase.kt` | API Key 발급 유스케이스 |
| `application/port/in/query/ListApiKeysQuery.kt` | API Key 목록 조회 |
| `application/port/in/command/UpdateApiKeyNameUseCase.kt` | API Key 이름 수정 |
| `application/port/in/command/DeleteApiKeyUseCase.kt` | API Key 삭제 |
| `application/port/out/ProviderRepository.kt` | Provider 저장/조회 포트 |
| `application/port/out/ApiKeyRepository.kt` | ApiKey 저장/조회/삭제 포트 |
| `application/port/out/ApiKeyGenerator.kt` | API Key 생성 + 해싱 포트 |
| `application/service/command/RegisterProviderService.kt` | Provider 등록 서비스 |
| `application/service/command/IssueApiKeyService.kt` | API Key 발급 서비스 |
| `application/service/query/ListApiKeysService.kt` | API Key 목록 서비스 |
| `application/service/command/UpdateApiKeyNameService.kt` | API Key 이름 수정 서비스 |
| `application/service/command/DeleteApiKeyService.kt` | API Key 삭제 서비스 |
| `adapter/in/rest/ProviderController.kt` | Provider 등록 엔드포인트 |
| `adapter/in/rest/ApiKeyController.kt` | API Key CRUD 엔드포인트 |
| `adapter/out/persistence/ProviderDocument.kt` | Provider MongoDB 문서 |
| `adapter/out/persistence/ProviderMongoDataRepository.kt` | Spring Data 인터페이스 |
| `adapter/out/persistence/ProviderMongoRepository.kt` | Provider 어댑터 |
| `adapter/out/persistence/ApiKeyDocument.kt` | ApiKey MongoDB 문서 |
| `adapter/out/persistence/ApiKeyMongoDataRepository.kt` | Spring Data 인터페이스 |
| `adapter/out/persistence/ApiKeyMongoRepository.kt` | ApiKey 어댑터 |
| `adapter/out/external/ApiKeyGeneratorAdapter.kt` | SecureRandom + SHA-256 |

### 테스트

| 파일 | 대상 |
|------|------|
| `test/.../service/command/RegisterProviderServiceTest.kt` | Provider 등록 |
| `test/.../service/command/IssueApiKeyServiceTest.kt` | API Key 발급 |
| `test/.../service/query/ListApiKeysServiceTest.kt` | 목록 조회 |
| `test/.../service/command/UpdateApiKeyNameServiceTest.kt` | 이름 수정 |
| `test/.../service/command/DeleteApiKeyServiceTest.kt` | 삭제 |
| `test/.../adapter/in/rest/ProviderControllerTest.kt` | Provider 엔드포인트 |
| `test/.../adapter/in/rest/ApiKeyControllerTest.kt` | API Key 엔드포인트 |
| `test/.../adapter/out/persistence/ProviderMongoRepositoryTest.kt` | Provider 영속화 |
| `test/.../adapter/out/persistence/ApiKeyMongoRepositoryTest.kt` | ApiKey 영속화 |
| `test/.../adapter/out/external/ApiKeyGeneratorAdapterTest.kt` | 키 생성/해싱 |

> 모든 파일 경로의 루트는 `apps/auth/src/main/kotlin/com/bara/auth/` (소스) 또는 `apps/auth/src/test/kotlin/com/bara/auth/` (테스트)

---

### Task 1: Provider + ApiKey 도메인 모델 + 예외 클래스

**Files:**
- Create: `domain/model/Provider.kt`
- Create: `domain/model/ApiKey.kt`
- Create: `domain/exception/ProviderAlreadyExistsException.kt`
- Create: `domain/exception/ProviderNotActiveException.kt`
- Create: `domain/exception/ProviderNotFoundException.kt`
- Create: `domain/exception/ApiKeyLimitExceededException.kt`
- Create: `domain/exception/ApiKeyNotFoundException.kt`

- [ ] **Step 1: Provider 도메인 모델**

```kotlin
// domain/model/Provider.kt
package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class Provider(
    val id: String,
    val userId: String,
    val name: String,
    val status: ProviderStatus,
    val createdAt: Instant,
) {
    enum class ProviderStatus {
        PENDING, ACTIVE, SUSPENDED
    }

    companion object {
        fun create(userId: String, name: String, now: Instant = Instant.now()): Provider =
            Provider(
                id = UUID.randomUUID().toString(),
                userId = userId,
                name = name,
                status = ProviderStatus.PENDING,
                createdAt = now,
            )
    }
}
```

- [ ] **Step 2: ApiKey 도메인 모델**

```kotlin
// domain/model/ApiKey.kt
package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class ApiKey(
    val id: String,
    val providerId: String,
    val name: String,
    val keyHash: String,
    val keyPrefix: String,
    val createdAt: Instant,
) {
    companion object {
        fun create(
            providerId: String,
            name: String,
            keyHash: String,
            keyPrefix: String,
            now: Instant = Instant.now(),
        ): ApiKey = ApiKey(
            id = UUID.randomUUID().toString(),
            providerId = providerId,
            name = name,
            keyHash = keyHash,
            keyPrefix = keyPrefix,
            createdAt = now,
        )
    }
}
```

- [ ] **Step 3: 예외 클래스 5개 생성**

기존 예외 패턴 확인 후 동일하게 생성. 기존 `InvalidTokenException`의 생성자 패턴을 따른다.

```kotlin
// domain/exception/ProviderAlreadyExistsException.kt
package com.bara.auth.domain.exception
class ProviderAlreadyExistsException : RuntimeException("Provider already exists for this user")

// domain/exception/ProviderNotActiveException.kt
package com.bara.auth.domain.exception
class ProviderNotActiveException : RuntimeException("Provider is not active")

// domain/exception/ProviderNotFoundException.kt
package com.bara.auth.domain.exception
class ProviderNotFoundException : RuntimeException("Provider not found")

// domain/exception/ApiKeyLimitExceededException.kt
package com.bara.auth.domain.exception
class ApiKeyLimitExceededException : RuntimeException("API key limit exceeded (max 5)")

// domain/exception/ApiKeyNotFoundException.kt
package com.bara.auth.domain.exception
class ApiKeyNotFoundException : RuntimeException("API key not found")
```

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/domain/
git commit -m "feat(auth): add Provider and ApiKey domain models with exceptions"
```

---

### Task 2: Provider + ApiKey 포트 정의

**Files:**
- Create: `application/port/in/command/RegisterProviderUseCase.kt`
- Create: `application/port/in/command/IssueApiKeyUseCase.kt`
- Create: `application/port/in/query/ListApiKeysQuery.kt`
- Create: `application/port/in/command/UpdateApiKeyNameUseCase.kt`
- Create: `application/port/in/command/DeleteApiKeyUseCase.kt`
- Create: `application/port/out/ProviderRepository.kt`
- Create: `application/port/out/ApiKeyRepository.kt`
- Create: `application/port/out/ApiKeyGenerator.kt`

- [ ] **Step 1: In 포트 (UseCase/Query)**

```kotlin
// application/port/in/command/RegisterProviderUseCase.kt
package com.bara.auth.application.port.`in`.command

import com.bara.auth.domain.model.Provider

interface RegisterProviderUseCase {
    fun register(userId: String, name: String): Provider
}
```

```kotlin
// application/port/in/command/IssueApiKeyUseCase.kt
package com.bara.auth.application.port.`in`.command

import com.bara.auth.domain.model.ApiKey

data class IssuedApiKey(
    val apiKey: ApiKey,
    val rawKey: String,
)

interface IssueApiKeyUseCase {
    fun issue(userId: String, name: String): IssuedApiKey
}
```

```kotlin
// application/port/in/query/ListApiKeysQuery.kt
package com.bara.auth.application.port.`in`.query

import com.bara.auth.domain.model.ApiKey

interface ListApiKeysQuery {
    fun listByUserId(userId: String): List<ApiKey>
}
```

```kotlin
// application/port/in/command/UpdateApiKeyNameUseCase.kt
package com.bara.auth.application.port.`in`.command

import com.bara.auth.domain.model.ApiKey

interface UpdateApiKeyNameUseCase {
    fun update(userId: String, keyId: String, newName: String): ApiKey
}
```

```kotlin
// application/port/in/command/DeleteApiKeyUseCase.kt
package com.bara.auth.application.port.`in`.command

interface DeleteApiKeyUseCase {
    fun delete(userId: String, keyId: String)
}
```

- [ ] **Step 2: Out 포트 (Repository/Generator)**

```kotlin
// application/port/out/ProviderRepository.kt
package com.bara.auth.application.port.out

import com.bara.auth.domain.model.Provider

interface ProviderRepository {
    fun save(provider: Provider): Provider
    fun findByUserId(userId: String): Provider?
    fun findById(id: String): Provider?
}
```

```kotlin
// application/port/out/ApiKeyRepository.kt
package com.bara.auth.application.port.out

import com.bara.auth.domain.model.ApiKey

interface ApiKeyRepository {
    fun save(apiKey: ApiKey): ApiKey
    fun findByProviderId(providerId: String): List<ApiKey>
    fun findById(id: String): ApiKey?
    fun countByProviderId(providerId: String): Long
    fun deleteById(id: String)
    fun findByKeyHash(keyHash: String): ApiKey?
}
```

```kotlin
// application/port/out/ApiKeyGenerator.kt
package com.bara.auth.application.port.out

data class GeneratedApiKey(
    val rawKey: String,
    val keyHash: String,
    val keyPrefix: String,
)

interface ApiKeyGenerator {
    fun generate(): GeneratedApiKey
}
```

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/
git commit -m "feat(auth): add Provider and ApiKey ports (in/out)"
```

---

### Task 3: ApiKeyGeneratorAdapter 구현 + 테스트

**Files:**
- Create: `adapter/out/external/ApiKeyGeneratorAdapter.kt`
- Create: `test/.../adapter/out/external/ApiKeyGeneratorAdapterTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
// test/.../adapter/out/external/ApiKeyGeneratorAdapterTest.kt
package com.bara.auth.adapter.out.external

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiKeyGeneratorAdapterTest {

    private val generator = ApiKeyGeneratorAdapter()

    @Test
    fun `생성된 키는 bk_ 접두사로 시작하고 67자이다`() {
        val result = generator.generate()
        assertTrue(result.rawKey.startsWith("bk_"))
        assertEquals(67, result.rawKey.length)
    }

    @Test
    fun `keyPrefix는 rawKey의 앞 10자이다`() {
        val result = generator.generate()
        assertEquals(result.rawKey.substring(0, 10), result.keyPrefix)
    }

    @Test
    fun `keyHash는 rawKey의 SHA-256 해시이다`() {
        val result = generator.generate()
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(result.rawKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, result.keyHash)
    }

    @Test
    fun `두 번 생성하면 서로 다른 키가 나온다`() {
        val key1 = generator.generate()
        val key2 = generator.generate()
        assertNotEquals(key1.rawKey, key2.rawKey)
        assertNotEquals(key1.keyHash, key2.keyHash)
    }
}
```

- [ ] **Step 2: ApiKeyGeneratorAdapter 구현**

```kotlin
// adapter/out/external/ApiKeyGeneratorAdapter.kt
package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.ApiKeyGenerator
import com.bara.auth.application.port.out.GeneratedApiKey
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom

@Component
class ApiKeyGeneratorAdapter : ApiKeyGenerator {

    private val secureRandom = SecureRandom()

    override fun generate(): GeneratedApiKey {
        val randomHex = ByteArray(32).also { secureRandom.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val rawKey = "bk_$randomHex"
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 10)
        return GeneratedApiKey(rawKey = rawKey, keyHash = keyHash, keyPrefix = keyPrefix)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.ApiKeyGeneratorAdapterTest"`
Expected: PASS (4 tests)

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/ApiKeyGeneratorAdapter.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/ApiKeyGeneratorAdapterTest.kt
git commit -m "feat(auth): implement ApiKeyGeneratorAdapter with SecureRandom + SHA-256"
```

---

### Task 4: Provider MongoDB 영속화 (Document + Repository + 테스트)

**Files:**
- Create: `adapter/out/persistence/ProviderDocument.kt`
- Create: `adapter/out/persistence/ProviderMongoDataRepository.kt`
- Create: `adapter/out/persistence/ProviderMongoRepository.kt`
- Create: `test/.../adapter/out/persistence/ProviderMongoRepositoryTest.kt`

- [ ] **Step 1: ProviderDocument**

```kotlin
// adapter/out/persistence/ProviderDocument.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.Provider
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "providers")
data class ProviderDocument(
    @Id val id: String,
    @Indexed(unique = true) val userId: String,
    val name: String,
    val status: String,
    val createdAt: Instant,
) {
    fun toDomain(): Provider = Provider(
        id = id, userId = userId, name = name,
        status = Provider.ProviderStatus.valueOf(status),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(provider: Provider): ProviderDocument = ProviderDocument(
            id = provider.id, userId = provider.userId, name = provider.name,
            status = provider.status.name, createdAt = provider.createdAt,
        )
    }
}
```

- [ ] **Step 2: ProviderMongoDataRepository (Spring Data)**

```kotlin
// adapter/out/persistence/ProviderMongoDataRepository.kt
package com.bara.auth.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository

interface ProviderMongoDataRepository : MongoRepository<ProviderDocument, String> {
    fun findByUserId(userId: String): ProviderDocument?
}
```

- [ ] **Step 3: ProviderMongoRepository 어댑터**

```kotlin
// adapter/out/persistence/ProviderMongoRepository.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import org.springframework.stereotype.Repository

@Repository
class ProviderMongoRepository(
    private val dataRepository: ProviderMongoDataRepository,
) : ProviderRepository {

    override fun save(provider: Provider): Provider =
        dataRepository.save(ProviderDocument.fromDomain(provider)).toDomain()

    override fun findByUserId(userId: String): Provider? =
        dataRepository.findByUserId(userId)?.toDomain()

    override fun findById(id: String): Provider? =
        dataRepository.findById(id).orElse(null)?.toDomain()
}
```

- [ ] **Step 4: 테스트**

```kotlin
// test/.../adapter/out/persistence/ProviderMongoRepositoryTest.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.Provider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ProviderMongoRepositoryTest {

    private val dataRepo = mockk<ProviderMongoDataRepository>()
    private val repo = ProviderMongoRepository(dataRepo)

    private val provider = Provider(
        id = "p-1", userId = "u-1", name = "Test Provider",
        status = Provider.ProviderStatus.PENDING, createdAt = Instant.parse("2026-04-07T00:00:00Z"),
    )

    @Test
    fun `save는 Provider를 저장하고 반환한다`() {
        every { dataRepo.save(any()) } returns ProviderDocument.fromDomain(provider)
        val result = repo.save(provider)
        assertEquals(provider, result)
    }

    @Test
    fun `findByUserId는 존재하는 Provider를 반환한다`() {
        every { dataRepo.findByUserId("u-1") } returns ProviderDocument.fromDomain(provider)
        val result = repo.findByUserId("u-1")
        assertEquals(provider, result)
    }

    @Test
    fun `findByUserId는 없으면 null을 반환한다`() {
        every { dataRepo.findByUserId("unknown") } returns null
        assertNull(repo.findByUserId("unknown"))
    }

    @Test
    fun `findById는 존재하는 Provider를 반환한다`() {
        every { dataRepo.findById("p-1") } returns Optional.of(ProviderDocument.fromDomain(provider))
        val result = repo.findById("p-1")
        assertEquals(provider, result)
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.ProviderMongoRepositoryTest"`
Expected: PASS (4 tests)

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/Provider*.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/ProviderMongoRepositoryTest.kt
git commit -m "feat(auth): implement Provider MongoDB persistence layer"
```

---

### Task 5: ApiKey MongoDB 영속화 (Document + Repository + 테스트)

**Files:**
- Create: `adapter/out/persistence/ApiKeyDocument.kt`
- Create: `adapter/out/persistence/ApiKeyMongoDataRepository.kt`
- Create: `adapter/out/persistence/ApiKeyMongoRepository.kt`
- Create: `test/.../adapter/out/persistence/ApiKeyMongoRepositoryTest.kt`

- [ ] **Step 1: ApiKeyDocument**

```kotlin
// adapter/out/persistence/ApiKeyDocument.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.ApiKey
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "api_keys")
data class ApiKeyDocument(
    @Id val id: String,
    @Indexed val providerId: String,
    val name: String,
    @Indexed(unique = true) val keyHash: String,
    val keyPrefix: String,
    val createdAt: Instant,
) {
    fun toDomain(): ApiKey = ApiKey(
        id = id, providerId = providerId, name = name,
        keyHash = keyHash, keyPrefix = keyPrefix, createdAt = createdAt,
    )

    companion object {
        fun fromDomain(apiKey: ApiKey): ApiKeyDocument = ApiKeyDocument(
            id = apiKey.id, providerId = apiKey.providerId, name = apiKey.name,
            keyHash = apiKey.keyHash, keyPrefix = apiKey.keyPrefix, createdAt = apiKey.createdAt,
        )
    }
}
```

- [ ] **Step 2: ApiKeyMongoDataRepository**

```kotlin
// adapter/out/persistence/ApiKeyMongoDataRepository.kt
package com.bara.auth.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository

interface ApiKeyMongoDataRepository : MongoRepository<ApiKeyDocument, String> {
    fun findByProviderId(providerId: String): List<ApiKeyDocument>
    fun countByProviderId(providerId: String): Long
    fun findByKeyHash(keyHash: String): ApiKeyDocument?
}
```

- [ ] **Step 3: ApiKeyMongoRepository 어댑터**

```kotlin
// adapter/out/persistence/ApiKeyMongoRepository.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.domain.model.ApiKey
import org.springframework.stereotype.Repository

@Repository
class ApiKeyMongoRepository(
    private val dataRepository: ApiKeyMongoDataRepository,
) : ApiKeyRepository {

    override fun save(apiKey: ApiKey): ApiKey =
        dataRepository.save(ApiKeyDocument.fromDomain(apiKey)).toDomain()

    override fun findByProviderId(providerId: String): List<ApiKey> =
        dataRepository.findByProviderId(providerId).map { it.toDomain() }

    override fun findById(id: String): ApiKey? =
        dataRepository.findById(id).orElse(null)?.toDomain()

    override fun countByProviderId(providerId: String): Long =
        dataRepository.countByProviderId(providerId)

    override fun deleteById(id: String) =
        dataRepository.deleteById(id)

    override fun findByKeyHash(keyHash: String): ApiKey? =
        dataRepository.findByKeyHash(keyHash)?.toDomain()
}
```

- [ ] **Step 4: 테스트**

```kotlin
// test/.../adapter/out/persistence/ApiKeyMongoRepositoryTest.kt
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.ApiKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ApiKeyMongoRepositoryTest {

    private val dataRepo = mockk<ApiKeyMongoDataRepository>(relaxed = true)
    private val repo = ApiKeyMongoRepository(dataRepo)

    private val apiKey = ApiKey(
        id = "k-1", providerId = "p-1", name = "Production",
        keyHash = "hash-abc", keyPrefix = "bk_a3f2e1",
        createdAt = Instant.parse("2026-04-07T00:00:00Z"),
    )

    @Test
    fun `save는 ApiKey를 저장하고 반환한다`() {
        every { dataRepo.save(any()) } returns ApiKeyDocument.fromDomain(apiKey)
        val result = repo.save(apiKey)
        assertEquals(apiKey, result)
    }

    @Test
    fun `findByProviderId는 해당 Provider의 키 목록을 반환한다`() {
        every { dataRepo.findByProviderId("p-1") } returns listOf(ApiKeyDocument.fromDomain(apiKey))
        val result = repo.findByProviderId("p-1")
        assertEquals(1, result.size)
        assertEquals(apiKey, result[0])
    }

    @Test
    fun `countByProviderId는 키 개수를 반환한다`() {
        every { dataRepo.countByProviderId("p-1") } returns 3L
        assertEquals(3L, repo.countByProviderId("p-1"))
    }

    @Test
    fun `findByKeyHash는 해시로 키를 조회한다`() {
        every { dataRepo.findByKeyHash("hash-abc") } returns ApiKeyDocument.fromDomain(apiKey)
        val result = repo.findByKeyHash("hash-abc")
        assertEquals(apiKey, result)
    }

    @Test
    fun `deleteById는 키를 삭제한다`() {
        repo.deleteById("k-1")
        verify { dataRepo.deleteById("k-1") }
    }
}
```

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.ApiKeyMongoRepositoryTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/ApiKey*.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/ApiKeyMongoRepositoryTest.kt
git commit -m "feat(auth): implement ApiKey MongoDB persistence layer"
```

---

### Task 6: RegisterProviderService 구현 + 테스트

**Files:**
- Create: `application/service/command/RegisterProviderService.kt`
- Create: `test/.../service/command/RegisterProviderServiceTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RegisterProviderServiceTest {

    private val providerRepository = mockk<ProviderRepository>()
    private val service = RegisterProviderService(providerRepository)

    @Test
    fun `신규 Provider를 등록하면 PENDING 상태로 저장된다`() {
        every { providerRepository.findByUserId("u-1") } returns null
        every { providerRepository.save(any()) } answers { firstArg() }

        val result = service.register("u-1", "My Server")

        assertEquals("My Server", result.name)
        assertEquals(Provider.ProviderStatus.PENDING, result.status)
        assertEquals("u-1", result.userId)
        verify { providerRepository.save(any()) }
    }

    @Test
    fun `이미 Provider가 있는 User는 ProviderAlreadyExistsException`() {
        val existing = Provider.create("u-1", "Existing")
        every { providerRepository.findByUserId("u-1") } returns existing

        assertThrows<ProviderAlreadyExistsException> {
            service.register("u-1", "New Server")
        }
    }
}
```

- [ ] **Step 2: RegisterProviderService 구현**

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.model.Provider
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegisterProviderService(
    private val providerRepository: ProviderRepository,
) : RegisterProviderUseCase {

    override fun register(userId: String, name: String): Provider {
        providerRepository.findByUserId(userId)?.let {
            throw ProviderAlreadyExistsException()
        }

        val provider = Provider.create(userId = userId, name = name)
        val saved = providerRepository.save(provider)

        WideEvent.put("provider_id", saved.id)
        WideEvent.put("provider_name", saved.name)
        WideEvent.put("user_id", userId)
        WideEvent.put("outcome", "provider_registered")
        WideEvent.message("Provider 등록 완료 (PENDING)")

        return saved
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.RegisterProviderServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/command/RegisterProviderService.kt \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/command/RegisterProviderServiceTest.kt
git commit -m "feat(auth): implement RegisterProviderService"
```

---

### Task 7: IssueApiKeyService 구현 + 테스트

**Files:**
- Create: `application/service/command/IssueApiKeyService.kt`
- Create: `test/.../service/command/IssueApiKeyServiceTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.*
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class IssueApiKeyServiceTest {

    private val providerRepository = mockk<ProviderRepository>()
    private val apiKeyRepository = mockk<ApiKeyRepository>(relaxed = true)
    private val apiKeyGenerator = mockk<ApiKeyGenerator>()
    private val service = IssueApiKeyService(providerRepository, apiKeyRepository, apiKeyGenerator)

    private val activeProvider = Provider(
        id = "p-1", userId = "u-1", name = "Server",
        status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
    )

    @Test
    fun `ACTIVE Provider에 API Key를 발급한다`() {
        every { providerRepository.findByUserId("u-1") } returns activeProvider
        every { apiKeyRepository.countByProviderId("p-1") } returns 0L
        every { apiKeyGenerator.generate() } returns GeneratedApiKey(
            rawKey = "bk_abc123...", keyHash = "hash-abc", keyPrefix = "bk_abc123",
        )
        every { apiKeyRepository.save(any()) } answers { firstArg() }

        val result = service.issue("u-1", "Prod Key")

        assertEquals("bk_abc123...", result.rawKey)
        assertEquals("Prod Key", result.apiKey.name)
        verify { apiKeyRepository.save(any()) }
    }

    @Test
    fun `Provider가 없으면 ProviderNotFoundException`() {
        every { providerRepository.findByUserId("u-1") } returns null

        assertThrows<ProviderNotFoundException> { service.issue("u-1", "Key") }
    }

    @Test
    fun `Provider가 PENDING이면 ProviderNotActiveException`() {
        val pending = activeProvider.copy(status = Provider.ProviderStatus.PENDING)
        every { providerRepository.findByUserId("u-1") } returns pending

        assertThrows<ProviderNotActiveException> { service.issue("u-1", "Key") }
    }

    @Test
    fun `API Key가 5개면 ApiKeyLimitExceededException`() {
        every { providerRepository.findByUserId("u-1") } returns activeProvider
        every { apiKeyRepository.countByProviderId("p-1") } returns 5L

        assertThrows<ApiKeyLimitExceededException> { service.issue("u-1", "Key") }
    }
}
```

- [ ] **Step 2: IssueApiKeyService 구현**

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.IssueApiKeyUseCase
import com.bara.auth.application.port.`in`.command.IssuedApiKey
import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.*
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class IssueApiKeyService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyGenerator: ApiKeyGenerator,
) : IssueApiKeyUseCase {

    override fun issue(userId: String, name: String): IssuedApiKey {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()

        if (provider.status != Provider.ProviderStatus.ACTIVE) {
            throw ProviderNotActiveException()
        }

        if (apiKeyRepository.countByProviderId(provider.id) >= 5) {
            throw ApiKeyLimitExceededException()
        }

        val generated = apiKeyGenerator.generate()
        val apiKey = ApiKey.create(
            providerId = provider.id,
            name = name,
            keyHash = generated.keyHash,
            keyPrefix = generated.keyPrefix,
        )
        val saved = apiKeyRepository.save(apiKey)

        WideEvent.put("user_id", userId)
        WideEvent.put("provider_id", provider.id)
        WideEvent.put("api_key_id", saved.id)
        WideEvent.put("api_key_prefix", saved.keyPrefix)
        WideEvent.put("outcome", "api_key_issued")
        WideEvent.message("API Key 발급 완료")

        return IssuedApiKey(apiKey = saved, rawKey = generated.rawKey)
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.IssueApiKeyServiceTest"`
Expected: PASS (4 tests)

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/command/IssueApiKeyService.kt \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/command/IssueApiKeyServiceTest.kt
git commit -m "feat(auth): implement IssueApiKeyService with limit check"
```

---

### Task 8: ListApiKeysService + UpdateApiKeyNameService + DeleteApiKeyService + 테스트

**Files:**
- Create: `application/service/query/ListApiKeysService.kt`
- Create: `application/service/command/UpdateApiKeyNameService.kt`
- Create: `application/service/command/DeleteApiKeyService.kt`
- Create: `test/.../service/query/ListApiKeysServiceTest.kt`
- Create: `test/.../service/command/UpdateApiKeyNameServiceTest.kt`
- Create: `test/.../service/command/DeleteApiKeyServiceTest.kt`

- [ ] **Step 1: ListApiKeysService + 테스트**

```kotlin
// application/service/query/ListApiKeysService.kt
package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import org.springframework.stereotype.Service

@Service
class ListApiKeysService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : ListApiKeysQuery {

    override fun listByUserId(userId: String): List<ApiKey> {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        return apiKeyRepository.findByProviderId(provider.id)
    }
}
```

- [ ] **Step 2: UpdateApiKeyNameService + 테스트**

```kotlin
// application/service/command/UpdateApiKeyNameService.kt
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.UpdateApiKeyNameUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class UpdateApiKeyNameService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : UpdateApiKeyNameUseCase {

    override fun update(userId: String, keyId: String, newName: String): ApiKey {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        val apiKey = apiKeyRepository.findById(keyId)
            ?: throw ApiKeyNotFoundException()
        if (apiKey.providerId != provider.id) {
            throw ApiKeyNotFoundException()
        }

        val updated = apiKey.copy(name = newName)
        val saved = apiKeyRepository.save(updated)

        WideEvent.put("user_id", userId)
        WideEvent.put("api_key_id", keyId)
        WideEvent.put("outcome", "api_key_name_updated")
        WideEvent.message("API Key 이름 수정")

        return saved
    }
}
```

- [ ] **Step 3: DeleteApiKeyService + 테스트**

```kotlin
// application/service/command/DeleteApiKeyService.kt
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.DeleteApiKeyUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class DeleteApiKeyService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : DeleteApiKeyUseCase {

    override fun delete(userId: String, keyId: String) {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        val apiKey = apiKeyRepository.findById(keyId)
            ?: throw ApiKeyNotFoundException()
        if (apiKey.providerId != provider.id) {
            throw ApiKeyNotFoundException()
        }

        apiKeyRepository.deleteById(keyId)

        WideEvent.put("user_id", userId)
        WideEvent.put("api_key_id", keyId)
        WideEvent.put("api_key_prefix", apiKey.keyPrefix)
        WideEvent.put("outcome", "api_key_deleted")
        WideEvent.message("API Key 삭제")
    }
}
```

- [ ] **Step 4: 각 서비스의 테스트 작성**

각 서비스에 대해 성공/실패 경로 테스트. ListApiKeysService는 Provider 없음 예외 + 정상 조회. UpdateApiKeyNameService는 정상 수정 + Provider 없음 + 키 없음 + 소유권 불일치. DeleteApiKeyService는 정상 삭제 + 소유권 불일치.

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.ListApiKeysServiceTest" --tests "*.UpdateApiKeyNameServiceTest" --tests "*.DeleteApiKeyServiceTest"`
Expected: ALL PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/ \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/
git commit -m "feat(auth): implement ListApiKeys, UpdateApiKeyName, DeleteApiKey services"
```

---

### Task 9: ProviderController + ApiKeyController + 테스트

**Files:**
- Create: `adapter/in/rest/ProviderController.kt`
- Create: `adapter/in/rest/ApiKeyController.kt`
- Modify: `adapter/in/rest/AuthExceptionHandler.kt` — Provider/ApiKey 예외 매핑 추가
- Create: `test/.../adapter/in/rest/ProviderControllerTest.kt`
- Create: `test/.../adapter/in/rest/ApiKeyControllerTest.kt`

- [ ] **Step 1: AuthExceptionHandler에 새 예외 매핑 추가**

```kotlin
// 추가할 핸들러:
@ExceptionHandler(ProviderAlreadyExistsException::class)
fun handleProviderAlreadyExists(e: ProviderAlreadyExistsException): ResponseEntity<Map<String, String>> {
    WideEvent.put("error_type", "ProviderAlreadyExistsException")
    WideEvent.put("outcome", "provider_already_exists")
    WideEvent.message("Provider 중복 등록 시도")
    return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message!!))
}

@ExceptionHandler(ProviderNotFoundException::class)
fun handleProviderNotFound(e: ProviderNotFoundException): ResponseEntity<Map<String, String>> {
    WideEvent.put("error_type", "ProviderNotFoundException")
    WideEvent.put("outcome", "provider_not_found")
    WideEvent.message("Provider를 찾을 수 없음")
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message!!))
}

@ExceptionHandler(ProviderNotActiveException::class)
fun handleProviderNotActive(e: ProviderNotActiveException): ResponseEntity<Map<String, String>> {
    WideEvent.put("error_type", "ProviderNotActiveException")
    WideEvent.put("outcome", "provider_not_active")
    WideEvent.message("Provider가 ACTIVE 상태가 아님")
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to e.message!!))
}

@ExceptionHandler(ApiKeyLimitExceededException::class)
fun handleApiKeyLimitExceeded(e: ApiKeyLimitExceededException): ResponseEntity<Map<String, String>> {
    WideEvent.put("error_type", "ApiKeyLimitExceededException")
    WideEvent.put("outcome", "api_key_limit_exceeded")
    WideEvent.message("API Key 한도 초과")
    return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to e.message!!))
}

@ExceptionHandler(ApiKeyNotFoundException::class)
fun handleApiKeyNotFound(e: ApiKeyNotFoundException): ResponseEntity<Map<String, String>> {
    WideEvent.put("error_type", "ApiKeyNotFoundException")
    WideEvent.put("outcome", "api_key_not_found")
    WideEvent.message("API Key를 찾을 수 없음")
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message!!))
}
```

- [ ] **Step 2: ProviderController**

```kotlin
// adapter/in/rest/ProviderController.kt
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.out.JwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class RegisterProviderRequest(val name: String)
data class ProviderResponse(val id: String, val name: String, val status: String, val createdAt: String)

@RestController
@RequestMapping("/auth/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val jwtVerifier: JwtVerifier,
) {
    @PostMapping("/register")
    fun register(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: RegisterProviderRequest,
    ): ResponseEntity<ProviderResponse> {
        val userId = extractUserId(authorization)
        val provider = registerUseCase.register(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ProviderResponse(
                id = provider.id, name = provider.name,
                status = provider.status.name, createdAt = provider.createdAt.toString(),
            )
        )
    }

    private fun extractUserId(authorization: String): String {
        val token = authorization.removePrefix("Bearer ").trim()
        return jwtVerifier.verify(token).userId
    }
}
```

- [ ] **Step 3: ApiKeyController**

```kotlin
// adapter/in/rest/ApiKeyController.kt
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.*
import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
import com.bara.auth.application.port.out.JwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class IssueApiKeyRequest(val name: String)
data class UpdateApiKeyNameRequest(val name: String)
data class ApiKeyResponse(val id: String, val name: String, val prefix: String, val createdAt: String)
data class IssuedApiKeyResponse(val id: String, val name: String, val apiKey: String, val prefix: String, val createdAt: String)
data class ApiKeyListResponse(val keys: List<ApiKeyResponse>)

@RestController
@RequestMapping("/auth/provider/api-key")
class ApiKeyController(
    private val issueUseCase: IssueApiKeyUseCase,
    private val listQuery: ListApiKeysQuery,
    private val updateUseCase: UpdateApiKeyNameUseCase,
    private val deleteUseCase: DeleteApiKeyUseCase,
    private val jwtVerifier: JwtVerifier,
) {
    @PostMapping
    fun issue(
        @RequestHeader("Authorization") auth: String,
        @RequestBody request: IssueApiKeyRequest,
    ): ResponseEntity<IssuedApiKeyResponse> {
        val userId = extractUserId(auth)
        val result = issueUseCase.issue(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssuedApiKeyResponse(
                id = result.apiKey.id, name = result.apiKey.name,
                apiKey = result.rawKey, prefix = result.apiKey.keyPrefix,
                createdAt = result.apiKey.createdAt.toString(),
            )
        )
    }

    @GetMapping
    fun list(@RequestHeader("Authorization") auth: String): ResponseEntity<ApiKeyListResponse> {
        val userId = extractUserId(auth)
        val keys = listQuery.listByUserId(userId)
        return ResponseEntity.ok(
            ApiKeyListResponse(keys = keys.map {
                ApiKeyResponse(id = it.id, name = it.name, prefix = it.keyPrefix, createdAt = it.createdAt.toString())
            })
        )
    }

    @PatchMapping("/{keyId}")
    fun updateName(
        @RequestHeader("Authorization") auth: String,
        @PathVariable keyId: String,
        @RequestBody request: UpdateApiKeyNameRequest,
    ): ResponseEntity<ApiKeyResponse> {
        val userId = extractUserId(auth)
        val updated = updateUseCase.update(userId, keyId, request.name)
        return ResponseEntity.ok(
            ApiKeyResponse(id = updated.id, name = updated.name, prefix = updated.keyPrefix, createdAt = updated.createdAt.toString())
        )
    }

    @DeleteMapping("/{keyId}")
    fun delete(
        @RequestHeader("Authorization") auth: String,
        @PathVariable keyId: String,
    ): ResponseEntity<Void> {
        val userId = extractUserId(auth)
        deleteUseCase.delete(userId, keyId)
        return ResponseEntity.noContent().build()
    }

    private fun extractUserId(authorization: String): String {
        val token = authorization.removePrefix("Bearer ").trim()
        return jwtVerifier.verify(token).userId
    }
}
```

- [ ] **Step 4: ProviderControllerTest + ApiKeyControllerTest 작성**

`@WebMvcTest` + `@MockkBean` 패턴으로 각 엔드포인트의 성공/실패 케이스 테스트. 기존 `AuthControllerTest`, `RefreshControllerTest` 패턴 참고.

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.ProviderControllerTest" --tests "*.ApiKeyControllerTest"`
Expected: ALL PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt
git commit -m "feat(auth): add Provider and ApiKey REST controllers with exception handling"
```

---

### Task 10: 로깅 플로우 문서 추가

**Files:**
- Create: `docs/guides/logging/flows/auth-provider.md`

- [ ] **Step 1: 로깅 플로우 문서 작성**

Provider 등록, API Key 발급/목록/수정/삭제 각 흐름의 WideEvent 필드 정의. `auth-login.md`, `auth-refresh.md` 형식 참고.

- [ ] **Step 2: 커밋**

```bash
git add docs/guides/logging/flows/auth-provider.md
git commit -m "docs: add provider/apikey logging flow documentation"
```

---

### Task 11: 전체 통합 확인 + 빌드

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew :apps:auth:test`
Expected: ALL PASS

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew :apps:auth:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 최종 커밋 (필요 시)**

---

## Self-Review Checklist

### Spec Coverage
| 스펙 항목 | 태스크 |
|-----------|--------|
| Provider 도메인 모델 (1:1 User, status enum) | Task 1 |
| ApiKey 도메인 모델 (별도 컬렉션) | Task 1 |
| API Key 형식 (bk_ + 64hex, SHA-256) | Task 3 |
| POST /auth/provider/register (201, 400, 401, 409) | Task 9 |
| POST /auth/provider/api-key (201, 400, 401, 403, 404, 409) | Task 9 |
| GET /auth/provider/api-key (200, 401, 404) | Task 9 |
| PATCH /auth/provider/api-key/{keyId} (200, 400, 401, 404) | Task 9 |
| DELETE /auth/provider/api-key/{keyId} (204, 401, 404) | Task 9 |
| JWT 인증 (Controller에서 JwtVerifier 직접 호출) | Task 9 |
| Provider MongoDB 영속화 (userId unique index) | Task 4 |
| ApiKey MongoDB 영속화 (keyHash unique index) | Task 5 |
| Provider당 API Key 최대 5개 | Task 7 |
| 로깅 (WideEvent) | Task 6, 7, 8, 10 |

### Placeholder Scan
- 모든 step에 코드 포함 ✅
- TBD/TODO 없음 ✅

### Type Consistency
- `Provider(id, userId, name, status, createdAt)` 일관 ✅
- `ApiKey(id, providerId, name, keyHash, keyPrefix, createdAt)` 일관 ✅
- `IssuedApiKey(apiKey, rawKey)` — 발급 시에만 rawKey 포함 ✅
- `GeneratedApiKey(rawKey, keyHash, keyPrefix)` — Generator 결과 ✅
