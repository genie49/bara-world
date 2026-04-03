# ADR-009: 서브도메인 대신 경로 기반 라우팅 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

여러 서비스(FE, API, Provider Gateway, Grafana)를 하나의 도메인으로 제공할 때, 서브도메인 방식과 경로 기반 방식 중 선택이 필요했다.

## 선택지

1. **서브도메인 방식**: `api.yourdomain.com`, `providers.yourdomain.com`, `grafana.yourdomain.com`. DNS 와일드카드로 처리 가능.
2. **경로 기반 방식**: `yourdomain.com/api`, `yourdomain.com/api`, `yourdomain.com/grafana`. Nginx `location`으로 라우팅.

## 결정

**경로 기반 라우팅**을 채택한다.

## 이유

- 도메인 하나로 확실히 정리되어 직관적
- Nginx `location` 설정으로 라우팅이 단순
- 서브도메인도 도메인 하나로 가능하긴 하지만 DNS 관리 포인트가 늘어남
- Cloudflare 설정도 도메인 하나만 관리하면 됨

## 결과

- `yourdomain.com/` → FE
- `yourdomain.com/api` → API (User + Provider 통합)
- `yourdomain.com/grafana` → Grafana
- 단, Kafka 연결은 TCP 프로토콜이라 경로 기반 불가 → 별도 포트(9093)로 직접 연결
