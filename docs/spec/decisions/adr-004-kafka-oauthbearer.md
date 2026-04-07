# ADR-004: Kafka 인증에 OAUTHBEARER 단기 토큰 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

외부 Agent 서버가 Kafka에 직접 연결해야 한다. Kafka 접근 시 인증 방식을 결정해야 했다.

## 선택지

1. **SASL/SCRAM (고정 비밀번호)**: Provider별 계정/비밀번호 발급. 단순.
2. **SASL/OAUTHBEARER (단기 토큰)**: API Key로 단기 Access Token 교환. Google Pub/Sub 인증과 유사.

## 결정

**SASL/OAUTHBEARER** 방식을 채택한다.

## 이유

- 고정 비밀번호는 탈취 시 수동 rotate 전까지 무한 사용 가능
- OAUTHBEARER 토큰은 탈취해도 최대 1시간만 유효
- API Key를 삭제하면 Kafka 토큰 갱신이 즉시 불가능해져 사실상 즉시 차단
- Kafka 클라이언트 라이브러리가 만료 전 자동 갱신 콜백을 지원
- Kafka 브로커는 JWKS로 토큰 서명을 검증하므로 Auth Service에 매번 요청하지 않음
- 이미 Auth Service에 JWT 발급 인프라가 있으므로 추가 구현 비용이 낮음

## 결과

- Auth Service에 `/auth/kafka/token` 엔드포인트 추가
- Auth Service에 JWKS 엔드포인트 (`/auth/.well-known/jwks.json`) 추가
- Kafka 브로커에 OAUTHBEARER 설정 필요
- Agent 서버에서 토큰 갱신 콜백 구현 필요 (대부분 Kafka 라이브러리가 지원)
- 토큰 갱신 실패 시 exponential backoff 재시도 + 알람 처리
