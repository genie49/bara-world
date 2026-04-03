# ADR-007: 관계형 DB 대신 MongoDB 단일 저장소 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

User, Provider 정보를 저장할 데이터베이스를 선택해야 했다. 초기에는 Cloud SQL(관계형 DB)이 검토되었다.

## 선택지

1. **관계형 DB (PostgreSQL/MySQL)**: 스키마 강제, 트랜잭션 보장. Cloud SQL 또는 VM 직접 설치.
2. **MongoDB**: 유연한 스키마, 도큐먼트 기반. VM 직접 설치.

## 결정

**MongoDB**를 단일 저장소로 사용한다.

## 이유

- Google OAuth가 인증을 담당하므로 비밀번호 저장이 불필요하고, 복잡한 인증 테이블이 필요 없음
- 저장할 데이터(User, Provider)가 단순한 도큐먼트 형태로 충분
- OCI VM에 직접 설치하므로 Cloud SQL(GCP) 사용 시 외부 통신 비용과 레이턴시 발생
- 관계형 DB를 제거하면 구조가 단순해지고 관리 포인트가 줄어듦
- MongoDB 하나로 User + Provider 정보를 통합 관리

## 결과

- Cloud SQL 의존성 제거
- MongoDB에 User, Provider 컬렉션 저장
- VM1 내부에서 직접 운영 (StatefulSet)
