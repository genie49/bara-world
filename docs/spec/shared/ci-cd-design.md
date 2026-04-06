# CI/CD 파이프라인 설계

## 개요

GitHub Actions 기반 CI/CD 파이프라인. PR 시 빌드/테스트, main 머지 시 OCI VM(K3s)에 자동 배포.

## 인프라 구성

| 구성 요소 | 선택 | 비고 |
|-----------|------|------|
| CI/CD 플랫폼 | GitHub Actions | |
| 이미지 레지스트리 | GCP Artifact Registry | `asia-northeast3`, 리포지토리명 `bara` |
| 배포 대상 | OCI Free Tier VM + K3s | 단일 노드 |
| 배포 방식 | SSH 직접 접속 | GitHub Actions → SSH → kubectl |
| K8s 환경 분리 | Kustomize | base + overlays (dev/prod) |

## 워크플로우 구조

```
.github/workflows/
  ci-auth.yml     # auth 서비스 CI
  ci-fe.yml       # FE CI
  deploy.yml      # main 머지 시 배포
```

서비스별 독립 워크플로우 방식. 서비스가 3-4개 이상 늘어나면 reusable workflow로 리팩터링.

## CI 워크플로우 (PR 빌드/테스트)

### ci-auth.yml

- **트리거**: PR to `develop` or `main`
- **Path filter**: `apps/auth/**`, `libs/**`, `build-logic/**`, `gradle/**`
- **Steps**:
  1. Checkout
  2. JDK 21 setup (temurin)
  3. Gradle cache
  4. `./gradlew :apps:auth:test`
  5. `./gradlew :apps:auth:bootJar`

### ci-fe.yml

- **트리거**: PR to `develop` or `main`
- **Path filter**: `apps/fe/**`
- **Steps**:
  1. Checkout
  2. Node 22 + pnpm setup
  3. pnpm cache
  4. `pnpm install --frozen-lockfile`
  5. `pnpm test`
  6. `pnpm build`

Husky hooks가 커버하는 포맷팅(lint-staged)은 CI에서 중복하지 않음.

## CD 워크플로우 (main 머지 → 배포)

### deploy.yml

- **트리거**: push to `main` (PR 머지 시 발동)

### Job 구성

**Job 1 — detect-changes**:
- `dorny/paths-filter`로 변경 서비스 감지
- Output: `auth: true/false`, `fe: true/false`

**Job 2 — build-push-auth** (if auth changed):
1. Checkout
2. GCP 인증 (Workload Identity Federation)
3. Artifact Registry 로그인
4. Docker build (`apps/auth/Dockerfile`)
5. Tag: `asia-northeast3-docker.pkg.dev/bara-world/bara/auth:<sha>` + `:latest`
6. Docker push

**Job 3 — build-push-fe** (if fe changed):
- 동일 구조, `bara/fe` 이미지

**Job 4 — deploy** (build-push 완료 후):
1. Checkout (매니페스트 파일 접근용)
2. `scp`로 `infra/k8s/` 매니페스트를 VM에 전송
3. SSH로 OCI VM 접속
4. K8s imagePullSecret 갱신 (`gcloud auth print-access-token` → `docker-registry` Secret)
5. `kubectl apply -k overlays/prod/` (레포의 prod overlay 그대로 적용, 초기 배포 + 매니페스트 변경 모두 대응)
6. GCP Parameter Manager에서 `.env.prod` pull → K8s Secret 갱신
7. **변경된 서비스만** `kubectl set image`로 커밋 SHA 태그 적용
8. **변경된 서비스만** `kubectl rollout status`로 배포 완료 확인
9. 임시 파일 정리

## Artifact Registry 관리

- **이미지 태그 전략**: `latest` + commit sha
- **보존 정책**: 이미지당 최신 2개 버전 유지 (`latest` + 직전)
- **Cleanup policy**: 최신 2개 외 자동 삭제
- **비용**: 0.5GB 무료 티어 내 유지 목표, 초과 시 $0.10/GB/월

## Kustomize 디렉토리 구조

```
infra/k8s/
  base/                    # 공통 매니페스트
    kustomization.yaml
    namespaces.yaml
    core/
      auth.yaml
      fe.yaml
    data/
      mongodb.yaml
      redis.yaml
      kafka.yaml
    gateway/
      nginx.yaml
      nginx.conf
  overlays/
    dev/                   # k3d 로컬용
      kustomization.yaml   # imagePullPolicy: Never, 로컬 이미지 태그
    prod/                  # OCI K3s용
      kustomization.yaml   # imagePullPolicy: Always, Artifact Registry 이미지 경로, imagePullSecrets
```

- 기존 `scripts/k8s.sh`는 `kubectl apply -k overlays/dev/`로 변경
- 프로덕션 배포는 `kubectl apply -k overlays/prod/`

## 시크릿 & 사전 준비

### GitHub Repository Secrets

| 시크릿 | 용도 |
|--------|------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | GitHub → GCP OIDC 인증 |
| `GCP_SERVICE_ACCOUNT` | Artifact Registry push 권한 SA |
| `VM_HOST` | OCI VM 공인 IP |
| `VM_USER` | SSH 접속 유저 |
| `VM_SSH_KEY` | SSH 개인키 |

### GCP 사전 셋업

1. Artifact Registry 리포지토리 생성 (`asia-northeast3`, Docker, `bara`)
2. Cleanup policy — Keep 최신 2개 + Delete 30일 초과
3. IAM Service Account Credentials API 활성화 (`iamcredentials.googleapis.com`)
4. Workload Identity Federation:
   - Pool: `github` (global)
   - OIDC Provider: `github-provider` (attribute condition: `genie49/bara-world` 레포만)
   - SA: `github-actions@bara-world.iam.gserviceaccount.com` (`artifactregistry.writer`)
   - WIF ↔ SA 바인딩 (principalSet)

### OCI VM 사전 셋업

- **Host**: OCI Free Tier ARM (aarch64), Ubuntu 22.04
- **K3s**: `--disable=traefik --write-kubeconfig-mode=0644` (ServiceLB 활성, Traefik 비활성)
- **Docker**: gcloud auth configure-docker 용 (K3s 이미지 pull은 imagePullSecret 사용)
- **gcloud CLI**: Parameter Manager 접근 + `gcloud auth configure-docker`
- **kubectl**: K3s 심볼릭 링크 (`/usr/local/bin/kubectl` → `k3s`)

초기 매니페스트 적용은 deploy 워크플로우가 자동 처리 (레포 클론 불필요).

### 로컬 배포 테스트

`scripts/deploy-test.sh` — CI/CD와 동일한 흐름을 로컬에서 테스트:

```bash
./scripts/deploy-test.sh push          # Docker build → Artifact Registry push
./scripts/deploy-test.sh push auth     # 특정 서비스만
./scripts/deploy-test.sh verify        # push된 이미지 pull 검증
./scripts/deploy-test.sh list          # 서비스별 이미지/태그 목록
```

## 보안

### Fork PR 시크릿 차단 (CRITICAL)

퍼블릭 레포이므로 Fork PR을 통한 시크릿 탈취가 가장 큰 위협.

- CI 워크플로우에서 `pull_request` 이벤트만 사용 (`pull_request_target` 사용 금지)
- Fork PR에는 빌드/테스트만 실행, 시크릿 접근 완전 차단
- Branch protection: `.github/workflows/` 수정은 CODEOWNERS 승인 필수

### 서드파티 Action 공급망 공격 (HIGH)

- 모든 서드파티 Action을 **커밋 SHA로 고정** (`@v2` 대신 `@<full-sha>`)
- `dorny/paths-filter`, `google-github-actions/auth`, `actions/checkout` 등 모두 해당
- Dependabot으로 SHA 업데이트 자동화

### GitHub 시크릿 보호 (HIGH)

- **Environment protection rules**: `production` Environment 생성 → `main` 브랜치 제한 + 필수 리뷰어 승인 후에만 deploy job 시크릿 접근
- deploy job은 반드시 `environment: production`으로 설정
- GitHub 계정 **2FA 필수**

### SSH 접근 제어 (HIGH)

- VM SSH 포트 비표준 포트로 변경 + `fail2ban` 설정
- 전용 deploy 유저 생성 — `kubectl` 실행 권한만 부여
- `authorized_keys`에 **command restriction** 적용으로 임의 명령 실행 차단
- OCI Security List에서 인바운드 SSH, 443, 80만 허용

### GCP 최소 권한 (HIGH)

- WIF attribute condition에 `repo:genie49/bara-world:ref:refs/heads/main` 명시 — 다른 브랜치/fork에서 GCP 인증 불가
- VM의 GCP SA: `artifactregistry.reader` + `parametermanager.viewer`만 부여
- 파이프라인 SA: `artifactregistry.writer`만 부여, Parameter Manager 접근 불가

### 이미지 무결성 (MEDIUM)

- 배포 시 `latest` 대신 **git SHA 태그** 사용 (`bara/auth:main-<sha>`)
- `latest`는 편의용으로 push하되 실제 배포에는 SHA 태그 참조
- Artifact Registry Vulnerability Scanning 활성화

### 워크플로우 인젝션 방지 (MEDIUM)

- `${{ github.event.pull_request.title }}` 등 사용자 제어 값을 `run:` 블록에 직접 삽입 금지
- 모든 사용자 제어 값은 `env:`로 전달
- `VM_HOST` 등 민감값은 `::add-mask::` 처리

### K3s 보안 (MEDIUM)

- K3s secrets 암호화 활성화 (`--secrets-encryption`)
- 불필요 컴포넌트 비활성화 (`--disable=traefik,servicelb`)
- Pod에 `automountServiceAccountToken: false` 기본 설정
- deploy 유저의 kubeconfig는 특정 namespace의 `deployments/update` 권한만 부여
