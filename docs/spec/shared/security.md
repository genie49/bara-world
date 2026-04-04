# 보안

## 위협 매트릭스

| 위협                 | 심각도 | 대응                                                       |
| -------------------- | ------ | ---------------------------------------------------------- |
| 패킷 스니핑          | 높음   | 전 구간 TLS 강제 (내부 mTLS는 후순위)                      |
| MITM (중간자 공격)   | 높음   | TLS, Agent Card 서명 (mTLS는 후순위)                       |
| JWT 위조             | 높음   | RS256, 알고리즘 고정                                       |
| 토큰 탈취/재사용     | 높음   | 짧은 만료, JTI 1회용                                       |
| X-User-Id 스푸핑     | 높음   | Nginx 헤더 제거 후 덮어씌움                                |
| Agent Card 스푸핑    | 높음   | 서명 검증, 등록 인증                                       |
| Redis 직접 접근      | 높음   | bind 제한, TLS, AUTH                                       |
| Agent 서버 직접 접근 | 높음   | Cloudflare IP만 허용, 내부 시크릿 헤더                     |
| Provider 토큰 탈취   | 높음   | 만료 주기 (30~90일), Admin이 즉시 비활성화(SUSPENDED) 가능 |
| Kafka 토픽 무단 접근 | 높음   | SASL + ACL                                                 |
| Kafka 메시지 위변조  | 높음   | TLS + 메시지 서명                                          |
| Webhook SSRF         | 높음   | HTTPS 강제, Private IP/메타데이터 차단, DNS Rebinding 방지 |
| 스케줄 남용          | 높음   | 사용자당 30개 제한, cron 최소 10분 간격, 표현식 검증       |
| 미허가 Agent 호출    | 높음   | Agent 화이트리스트 + allowed_agents 메시지 포함 + SDK 검증 |
| 권한 상승            | 중간   | 토큰 서명, 권한 검증                                       |
| Task Replay          | 중간   | JTI, Idempotency Key                                       |
| DDoS                 | 중간   | Cloudflare, Rate Limiting, 캐싱                            |
| Kafka 토픽 폭주      | 중간   | Provider별 Rate Limit                                      |

## 네트워크 레이어

### 스니핑 방지

- 외부 구간: TLS 1.3 강제 (1.2 이하 차단)
- 내부 구간: TLS 적용 (현재 단일 VM이지만 향후 분리 대비). mTLS는 안정화 후 도입 (우선순위 로드맵 참고)
- Kafka 포함 전 구간 TLS: 내부 9092도 TLS 적용, 외부 9093도 TLS
- HTTPS 구간에서는 패킷 캡처해도 암호화된 바이트만 보임

### MITM 방지

- Certificate Pinning
- TLS로 전송 구간 암호화 (mTLS는 후순위)
- A2A v0.3 서명된 Agent Card 사용

### DDoS 대응

- Cloudflare 앞단에서 기본 DDoS 차단
- Nginx에서 IP/토큰 단위 Rate Limiting
- Auth Service 검증 결과 짧은 캐싱 (30초)
- Auth Service 다운 시 Circuit Breaker fallback

## 인증 레이어

### JWT 위조 방지

- RS256 (비대칭키) 사용, HS256 금지
- `none` 알고리즘 명시적 차단
- `issuer`, `audience` 검증 필수

### 토큰 탈취/Replay 대응

- 짧은 만료시간: User JWT 1시간, Agent 간 토큰 5분
- JTI(JWT ID) 클레임: Redis에 사용된 JTI 저장, 1회 사용 후 무효화
- Refresh Token: 별도 보관, Rotate 방식 (상세 흐름은 구현 시 정의)

### 토큰이 탈취되는 경로 (스니핑 아님)

- 로그에 `Authorization` 헤더 출력 → 헤더 마스킹 필수
- 코드에 하드코딩 후 Git 커밋 → Secret Manager/환경변수 사용
- 서버 침해 시 메모리 덤프 → 근본 방어 어려움

## 헤더/스푸핑 레이어

### X-User-Id 스푸핑 방지

Nginx에서 클라이언트가 보낸 모든 내부 헤더를 **초기화한 후** Auth Service 검증 결과로 덮어씌운다.

초기화 대상: `X-User-Id`, `X-User-Role`, `X-Agent-Id`, `X-Provider-Id`, `X-Gateway`

> 헤더 주입 흐름 상세는 [인증 문서](../auth/authentication.md#nginx-헤더-주입) 참고.

### Agent Card 스푸핑 방지

- A2A v0.3 서명된 Agent Card 사용
- API Service 등록 시 Provider 토큰 인증 필수
- Agent Card URL이 등록된 endpoint와 일치 여부 확인
- 등록 허가된 Provider만 등록 가능

## 인프라 레이어

### Redis 보안

- AUTH: 강력한 패스워드 설정
- bind: 로컬호스트 및 내부망만 허용
- TLS: Redis 6.0+ TLS 지원 활용
- 권한 분리: API Service는 읽기/쓰기(등록), Agent는 heartbeat 갱신(API Service 경유)

### Agent 서버 직접 접근 차단

- OCI 방화벽에서 Cloudflare IP만 443 허용
- Nginx에서 내부 시크릿 헤더(`X-Internal-Token`) 주입
- 다운스트림 서비스에서 시크릿 헤더 없으면 403 거부

## Kafka 레이어

### 토픽 무단 접근

- SASL OAUTHBEARER 인증: 인증된 계정만 연결 가능
- ACL: Provider/Agent별 자기 토픽만 접근 가능

### 메시지 위변조

- TLS로 전송 구간 암호화
- 메시지 서명: Auth Service 개인키로 서명, 수신 시 검증

### 결과 토픽 위조 방지

- 태스크 생성 시 MongoDB에 `task_id → agent_id` 매핑 저장
- 채널 서비스 및 Agent가 결과 수신 시 메시지의 `agent_id`와 원래 태스크 대상 `agent_id` 일치 검증
- `results.api`, `results.telegram`, `results.agents` 모든 결과 토픽에 적용
- 불일치 시 결과 폐기 + WARN 로그
- task_id는 UUID v4로 추측 불가하지만, defense in depth로 검증 추가

### 민감 데이터 노출

- 메시지에는 최소한의 정보만 포함 (user_id, task_id, request_id)
- 실제 데이터는 별도 저장소에 보관 후 task_id로 참조
- 로그에 메시지 내용 출력 금지

### Replay 공격

- JTI(메시지 고유 ID)를 Redis에 저장, 1회 처리 후 무효화
- 타임스탬프 검증: 5분 이상 지난 메시지 거부
- Idempotency Key: 태스크 ID 기반 중복 실행 방지

### Kafka 외부 포트(9093) 보호

- SASL 인증 실패 N회(예: 5회/분) 시 해당 IP 자동 차단 (fail2ban)
- 인증 실패 로그 기록 + 알람 연동
- Kafka `max.connections.per.ip` 설정으로 연결 수 제한
- L4 DDoS는 Cloudflare를 우회하므로 완벽 방어 불가 — 공격 발생 시 Kafka 전용 공인 IP 분리로 대응

### 토픽 폭주 (DoS)

- Provider별 발행 Rate Limit (API Service 레벨)
- Kafka Consumer `max.poll.records` 설정으로 처리량 제한

## 에이전트 체인 레이어

### 권한 상승 방지

- 에이전트 간 토큰에 원본 사용자 권한을 서명하여 포함
- Agent는 권한을 높일 수 없고 낮추거나 유지만 가능
- 토큰에 `target_agent_id` 포함: 특정 대상 Agent에만 유효

### Task Replay 방지

- JTI 1회용 검증 (Redis)
- Idempotency Key 기반 중복 실행 방지
- 타임스탬프 검증

## 우선순위 로드맵

### 서비스 오픈 전 필수

1. OCI 방화벽 + Cloudflare IP만 허용
2. 전 구간 TLS (내부 포함)
3. Nginx 헤더 초기화 후 재주입
4. Redis AUTH + bind 제한

### 안정화 후 순차 추가

5. JWT RS256 + JTI 1회용
6. Agent Card 서명 검증
7. Kafka 메시지 서명
8. 내부 mTLS
9. Auth 결과 캐싱 + Circuit Breaker
