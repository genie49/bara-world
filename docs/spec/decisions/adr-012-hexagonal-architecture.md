# ADR-012: 서비스 아키텍처로 헥사고날 (Ports and Adapters) 채택

- **상태**: 채택
- **일자**: 2026-04-04

## 맥락

Auth, API, Scheduler 등 모든 Spring Boot 서비스의 내부 아키텍처 패턴을 결정해야 했다. 각 서비스는 REST, Kafka, MongoDB, Redis 등 여러 인프라에 동시에 의존하며, 특히 API Service는 REST와 Kafka 양쪽에서 동일한 비즈니스 로직을 호출하는 구조이다.

## 선택지

1. **Layered Architecture**: Controller → Service → Repository 전통 3계층. 단순하지만 인프라 의존성이 Service 레이어에 침투.
2. **Hexagonal Architecture (Ports and Adapters)**: 도메인을 중심에 두고 Port 인터페이스로 외부와 격리. 어댑터가 실제 기술을 담당.
3. **Vertical Slice Architecture**: 기능 단위로 코드를 묶음. 응집도 높지만 어댑터 교체가 어려움.

## 결정

**헥사고날 아키텍처**를 모든 서비스에 통일 적용한다.

단순 CRUD를 포함한 모든 기능에 동일한 패턴을 적용한다. "이 기능은 단순하니 shortcut" 같은 예외를 두지 않는다. 패턴이 일관되면 어떤 기능을 찾든 항상 같은 구조이므로 판단 비용이 사라진다.

## 이유

- REST + Kafka 양쪽 인바운드가 동일한 비즈니스 로직을 호출하는 구조에서 Port 패턴이 자연스러움
- 공용 SDK는 외부 라이브러리이므로 어댑터로 감싸서 도메인에 직접 결합되지 않도록 격리
- 테스트 시 Port만 mock하면 DB/Kafka 없이 비즈니스 로직 검증 가능
- 도메인이 현재는 얇지만 서비스 성장 시 복잡해질 수 있으며, 그때 구조 변경 없이 확장 가능
- 모든 기능에 통일 적용하여 "단순 CRUD인가 UseCase가 필요한가" 판단 비용 제거

## 결과

- 상세 패키지 구조와 규칙은 [shared/service-architecture.md](../shared/service-architecture.md) 참고
- Domain 모델에는 Spring/MongoDB 등 프레임워크 의존성 없음
- 모든 아웃바운드 인프라(MongoDB, Redis, Kafka, 외부 API)는 Port 인터페이스로 격리
- SDK 사용은 Kafka 아웃바운드 어댑터 내부에서만 발생
