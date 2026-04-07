# MongoDB/Redis 프로덕션 인증 설계

## 목표

프로덕션 환경에서 MongoDB와 Redis에 비밀번호 인증을 추가하여 보안을 강화한다. 개발 환경(k3d)은 기존대로 인증 없이 유지한다.

## 범위

- MongoDB: root 계정 인증 (단일 계정)
- Redis: requirepass 인증
- prod 환경만 적용 (dev는 인증 없음 유지)
- Kafka는 이번 범위에서 제외 (Agent 서비스 도입 시 별도 진행)

## 아키텍처

### Secret 분리

| Secret | 네임스페이스 | 용도 |
|--------|------------|------|
| `auth-secrets` | core | JWT 키, Google OAuth 등 기존 |
| `data-secrets` | data | MongoDB/Redis 비밀번호 (신규) |

`data-secrets`에 포함되는 키:
- `MONGO_ROOT_USERNAME`
- `MONGO_ROOT_PASSWORD`
- `REDIS_PASSWORD`

credential은 `.env.prod`에 추가하고, 기존 GCP Parameter Manager 흐름으로 관리한다.

### MongoDB 인증

**방식**: `MONGO_INITDB_ROOT_USERNAME` / `MONGO_INITDB_ROOT_PASSWORD` 환경변수를 StatefulSet에 주입. MongoDB 7은 이 환경변수가 있으면 자동으로 인증을 활성화한다.

**base 설정** (prod 기준):
- `mongodb.yaml`: Secret에서 `MONGO_INITDB_ROOT_USERNAME`, `MONGO_INITDB_ROOT_PASSWORD` 환경변수 주입
- `auth.yaml`: `SPRING_DATA_MONGODB_URI`를 Secret에서 조합한 인증 URI로 변경

**연결 문자열**:
```
mongodb://USER:PASS@mongodb.data.svc.cluster.local:27017/bara-auth?authSource=admin
```

**dev overlay**: 환경변수 제거 패치 + URI를 인증 없는 버전으로 교체

### Redis 인증

**방식**: 컨테이너 args에 `--requirepass` 플래그 추가, Secret에서 비밀번호 주입.

**base 설정** (prod 기준):
- `redis.yaml`: args로 `--requirepass` 추가, Secret에서 `REDIS_PASSWORD` 환경변수 주입
- `auth.yaml`: `SPRING_DATA_REDIS_PASSWORD` 환경변수 추가 (Secret 참조)

**Spring Boot 설정**:
```yaml
spring:
  data:
    redis:
      password: ${SPRING_DATA_REDIS_PASSWORD:}  # 빈 값이면 인증 안 함
```

**dev overlay**: args 제거 패치 + password 환경변수 제거

### 환경별 비교

| 항목 | base (prod 기준) | dev overlay |
|------|-----------------|-------------|
| MongoDB | `MONGO_INITDB_ROOT_*` + 인증 URI | 환경변수 제거 + 기존 URI |
| Redis | `--requirepass` + password env | args/env 제거 |
| Secret | `data-secrets` (data ns) | 생성 안 함 |
| Spring Boot | Secret에서 URI/password 주입 | 기본값 (인증 없음) |

### 배포 흐름 변경

`deploy.yml`에 `data-secrets` 생성 단계 추가:
```bash
kubectl create secret generic data-secrets -n data \
  --from-env-file=/tmp/.env.prod \
  --dry-run=client -o yaml | kubectl apply -f -
```

`k8s.sh` (dev)에서는 `data-secrets` 생성 불필요.

### 기존 prod 데이터 마이그레이션

`MONGO_INITDB_*` 환경변수는 빈 DB에서만 동작한다. 기존 prod MongoDB에 데이터가 있는 경우:

- **방법 1: PVC 삭제 후 재생성** — StatefulSet 삭제 → PVC 삭제 → 재배포. 데이터 손실되지만 깔끔.
- **방법 2: mongosh로 수동 계정 생성** — `kubectl exec`으로 mongosh 접속 후 `db.createUser()` 실행. 데이터 보존.

배포 시점에 데이터 상태를 확인하고 결정한다.

Redis는 인메모리이므로 재시작 시 마이그레이션 이슈 없음.

### 비밀번호 관리

사용자가 직접 지정하여 `.env.prod`에 추가하고 GCP Parameter Manager에 업로드한다.
