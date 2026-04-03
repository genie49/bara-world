# ADR-002: HTTP 직접 호출 대신 Kafka 메시지 기반 비동기 통신

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

Agent 서버가 AWS, GCP, Railway 등 다양한 환경에 위치한다. User Gateway나 다른 Agent가 특정 Agent를 호출할 때 어떤 방식으로 통신할지 결정이 필요했다.

## 선택지

1. **HTTP 직접 호출**: Gateway/Agent가 대상 Agent의 endpoint로 직접 HTTP 요청. 동기 방식.
2. **Redis Streams**: 이미 사용 중인 Redis에 메시지 큐 역할 추가. 경량.
3. **Kafka 메시지 기반**: Kafka 토픽을 통한 비동기 통신.

## 결정

**Kafka 메시지 기반 비동기 통신**을 채택한다.

## 이유

- HTTP 직접 호출은 Agent 서버가 HTTPS가 아닌 경우 보안 위험이 있고, Agent 장애 시 요청이 유실됨
- Kafka를 사용하면 Agent 서버의 프로토콜(HTTP/HTTPS)에 의존하지 않음
- Agent 장애 시 메시지가 큐에 보존되어 유실 방지
- 호출자가 Agent 서버 주소를 알 필요 없음 (느슨한 결합)
- 같은 토픽을 여러 Agent가 구독하면 자연스러운 부하 분산
- Redis Streams도 가능하지만, 이미 로그 파이프라인에 Kafka를 사용하므로 통합
- Kafka의 ACL, SASL 등 보안 기능이 더 성숙

## 결과

- 사용자 응답이 비동기로 변경 → SSE/WebSocket/폴링 필요
- Nginx Provider 경로(`/api`)의 역할이 단순화 (직접 포워딩 → Kafka 발행)
- Agent 서버는 Kafka 클라이언트 라이브러리 필요
- Kafka 외부 포트(9093) 오픈 필요 → SASL + TLS로 보호
