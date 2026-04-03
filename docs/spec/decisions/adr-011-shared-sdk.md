# ADR-011: 별도 중계 서비스 대신 공용 SDK 도입

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

API Service와 Telegram Service가 Agent와 Kafka를 통해 통신할 때, 태스크 발행/결과 수신/멀티턴 관리 등 공통 로직이 존재한다. 이 공통 로직을 어디에 둘지 결정이 필요했다.

## 선택지

1. **Task Service (별도 중계 서비스)**: 채널 서비스 → Kafka → Task Service → Kafka → Agent. 중앙에서 태스크를 중계.
2. **공용 SDK (라이브러리)**: 채널 서비스가 SDK를 사용하여 Kafka로 Agent와 직접 통신. 중간 hop 없음.

## 결정

**공용 SDK** 방식을 채택한다. Python, TypeScript, Java를 지원한다.

## 이유

- Task Service는 채널 서비스와 Agent 사이에 불필요한 hop을 추가하여 레이턴시 증가
- Task Service가 하는 일(Kafka 발행, 결과 구독, 메시지 포맷팅)은 라이브러리로 충분히 추상화 가능
- 멀티턴 대화(`context_id`)는 A2A 프로토콜 레벨에서 Agent가 자체 관리
- Agent 라우팅은 agent-id 기반으로 단순하여 중앙 라우팅 서비스가 불필요
- 별도 서비스를 제거하면 인프라 리소스 절약, 장애 포인트 감소
- 새 채널 추가 시 SDK만 import하면 됨

## 결과

- 공용 SDK를 Python, TypeScript, Java로 배포
- API Service, Telegram Service가 SDK를 사용하여 Kafka 직접 통신
- 외부 Agent 서버도 동일한 SDK로 Kafka 연결 가능
- SDK 인터페이스: 태스크 발행, 결과 구독, Kafka 인증(OAUTHBEARER), 메시지 포맷팅, 에러 처리
- 배포: 각 언어별 패키지 매니저로 배포 (PyPI, npm, Maven Central)
- 버전 관리: Semantic Versioning, A2A 프로토콜 버전과 SDK 버전 매핑 관리
