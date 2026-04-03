# ADR-005: Nginx 2개 분리 대신 1개로 통합

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

User Gateway와 Provider Gateway를 별도 Nginx 인스턴스로 운영할지, 하나의 Nginx에서 처리할지 결정이 필요했다.

## 선택지

1. **Nginx 2개**: User Gateway, Provider Gateway 각각 독립 Pod. 트래픽 성격별 분리 스케일링 가능.
2. **Nginx 1개**: 단일 Pod에서 경로(`location`) 기반으로 구분. 설정 한 곳에서 관리.

## 결정

**Nginx 1개로 통합**한다.

## 이유

- 초기 트래픽 규모에서 분리 스케일링이 필요하지 않음
- Pod 수를 줄여 리소스 절약 (OCI 무료 티어 제약)
- 설정 파일을 한 곳에서 관리하는 것이 운영상 단순
- 경로 기반 분리(`/api`, `/api`)로 User/Provider 트래픽 구분은 유지
- 나중에 트래픽이 많아져서 분리 스케일링이 필요해지면 그때 분리

## 결과

- 단일 Nginx Pod에서 경로(`location`) 기반 라우팅
- `/api`에서 User JWT / Provider 토큰을 자동 판별하여 통합 처리
- 경로: `/`, `/api`, `/grafana`
