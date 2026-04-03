# ADR-003: mTLS / API Key 대신 Provider 토큰 방식 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

Agent 서버가 다양한 클라우드/서버에 위치하므로, VPC 기반 IP 차단이 불가능하다. 외부 Agent 서버의 인증 방식을 결정해야 했다.

## 선택지

1. **mTLS (Mutual TLS)**: 자체 CA에서 클라이언트 인증서 발급, 양방향 인증서 검증.
2. **API Key**: Agent 서버마다 고유 API Key 발급. 헤더로 전달.
3. **Provider 토큰**: Provider(Agent 운영자)가 Auth Service에서 토큰 발급. JWT 기반.

## 결정

**Provider 토큰** 방식을 채택한다.

## 이유

- mTLS는 가장 안전하지만 CA 구축, 인증서 발급/갱신/배포의 운영 부담이 크다
- API Key는 단순하지만 탈취 시 만료 없이 무한 사용 가능, 교체가 수동
- Provider 토큰은 JWT 기반으로:
  - 만료 시간 설정 가능 (30~90일)
  - 즉시 비활성화 가능 (Auth Service에서)
  - Provider ID로 누가 뭘 했는지 추적 가능
  - 나중에 Provider별 권한/요금제 분리에 유리
  - 인증서 관리 불필요, 어디서든 토큰만 있으면 연동
- mTLS는 인프라 자동화가 갖춰진 이후 추가 도입 고려

## 결과

- Auth Service에 Provider 가입/토큰 관리 기능 추가
- MongoDB에 Provider 컬렉션 추가
- Nginx 통합 Gateway(`/api`)에서 Provider 토큰 검증
- Agent 서버 개발자는 Provider 토큰 하나만 관리하면 됨
