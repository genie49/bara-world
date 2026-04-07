# CI/CD 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub Actions 기반 CI/CD 파이프라인 구축 — PR 시 빌드/테스트, main 머지 시 GCP Artifact Registry push + OCI VM(K3s) 자동 배포.

**Architecture:** 서비스별 독립 CI 워크플로우(ci-auth.yml, ci-fe.yml) + 통합 배포 워크플로우(deploy.yml). K8s 매니페스트는 Kustomize로 dev/prod 환경 분리. 모든 서드파티 Action은 커밋 SHA 고정.

**Tech Stack:** GitHub Actions, GCP Artifact Registry, GCP Workload Identity Federation, Kustomize, Docker, SSH (appleboy/ssh-action)

---

## 파일 구조

### 새로 생성할 파일

| 파일 | 역할 |
|------|------|
| `.github/workflows/ci-auth.yml` | auth 서비스 PR 빌드/테스트 |
| `.github/workflows/ci-fe.yml` | FE PR 빌드/테스트 |
| `.github/workflows/deploy.yml` | main 머지 시 이미지 빌드 → push → SSH 배포 |
| `.github/CODEOWNERS` | `.github/workflows/` 보호 |
| `.github/dependabot.yml` | Action SHA 자동 업데이트 |
| `infra/k8s/base/kustomization.yaml` | Kustomize base 설정 |
| `infra/k8s/overlays/dev/kustomization.yaml` | dev overlay (imagePullPolicy: Never, 로컬 태그) |
| `infra/k8s/overlays/prod/kustomization.yaml` | prod overlay (imagePullPolicy: Always, Artifact Registry 경로) |

### 수정할 파일

| 파일 | 변경 내용 |
|------|----------|
| `infra/k8s/core/auth.yaml` | `imagePullPolicy`/`image` 제거 (overlay에서 패치) |
| `infra/k8s/core/fe.yaml` | `imagePullPolicy`/`image` 제거 (overlay에서 패치) |
| `scripts/k8s.sh` | `kubectl apply -f` → `kubectl apply -k overlays/dev/` |
| `infra/k8s/gateway/nginx.yaml` | base로 이동, 경로 조정 |
| `infra/k8s/namespaces.yaml` | base로 이동 |

---

### Task 1: Kustomize base 구성

기존 K8s 매니페스트를 `base/`로 이동하고 `kustomization.yaml` 생성.

**Files:**
- Create: `infra/k8s/base/kustomization.yaml`
- Move: `infra/k8s/namespaces.yaml` → `infra/k8s/base/namespaces.yaml`
- Move: `infra/k8s/core/` → `infra/k8s/base/core/`
- Move: `infra/k8s/data/` → `infra/k8s/base/data/`
- Move: `infra/k8s/gateway/` → `infra/k8s/base/gateway/`

- [ ] **Step 1: 파일 이동**

```bash
cd /Users/genie/temp/bara-world
mkdir -p infra/k8s/base
mv infra/k8s/namespaces.yaml infra/k8s/base/
mv infra/k8s/core infra/k8s/base/
mv infra/k8s/data infra/k8s/base/
mv infra/k8s/gateway infra/k8s/base/
```

- [ ] **Step 2: base kustomization.yaml 생성**

`infra/k8s/base/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - namespaces.yaml
  - data/mongodb.yaml
  - data/redis.yaml
  - data/kafka.yaml
  - core/auth.yaml
  - core/fe.yaml
  - gateway/nginx.yaml

configMapGenerator:
  - name: nginx-config
    namespace: gateway
    files:
      - default.conf=gateway/nginx.conf
    options:
      disableNameSuffixHash: true
```

- [ ] **Step 3: base auth.yaml에서 환경별 값 제거**

`infra/k8s/base/core/auth.yaml`에서 `imagePullPolicy: Never` 줄을 제거한다. `image: bara/auth:latest`는 유지 (overlay에서 덮어씀).

변경 전:
```yaml
          image: bara/auth:latest
          imagePullPolicy: Never
```

변경 후:
```yaml
          image: bara/auth:latest
```

- [ ] **Step 4: base fe.yaml에서 환경별 값 제거**

`infra/k8s/base/core/fe.yaml`에서 `imagePullPolicy: Never` 줄을 제거한다.

변경 전:
```yaml
          image: bara/fe:latest
          imagePullPolicy: Never
```

변경 후:
```yaml
          image: bara/fe:latest
```

- [ ] **Step 5: kustomize build로 검증**

```bash
kubectl kustomize infra/k8s/base/
```

Expected: YAML이 정상 출력됨. 에러 없음.

- [ ] **Step 6: Commit**

```bash
git add infra/k8s/
git commit -m "refactor(infra): K8s 매니페스트를 Kustomize base로 이동"
```

---

### Task 2: Kustomize dev overlay

k3d 로컬 개발용 overlay. `imagePullPolicy: Never` + 로컬 이미지 태그.

**Files:**
- Create: `infra/k8s/overlays/dev/kustomization.yaml`

- [ ] **Step 1: dev overlay 생성**

`infra/k8s/overlays/dev/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../base

patches:
  - target:
      kind: Deployment
      name: auth
      namespace: core
    patch: |
      - op: add
        path: /spec/template/spec/containers/0/imagePullPolicy
        value: Never
  - target:
      kind: Deployment
      name: fe
      namespace: core
    patch: |
      - op: add
        path: /spec/template/spec/containers/0/imagePullPolicy
        value: Never
```

- [ ] **Step 2: kustomize build로 검증**

```bash
kubectl kustomize infra/k8s/overlays/dev/
```

Expected: auth와 fe Deployment에 `imagePullPolicy: Never`가 포함된 YAML 출력.

- [ ] **Step 3: Commit**

```bash
git add infra/k8s/overlays/
git commit -m "ci(infra): Kustomize dev overlay 추가"
```

---

### Task 3: Kustomize prod overlay

OCI VM K3s 프로덕션용 overlay. `imagePullPolicy: Always` + Artifact Registry 이미지 경로.

**Files:**
- Create: `infra/k8s/overlays/prod/kustomization.yaml`

- [ ] **Step 1: prod overlay 생성**

`infra/k8s/overlays/prod/kustomization.yaml`:
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../base

images:
  - name: bara/auth
    newName: asia-northeast3-docker.pkg.dev/bara-world/bara/auth
    newTag: latest
  - name: bara/fe
    newName: asia-northeast3-docker.pkg.dev/bara-world/bara/fe
    newTag: latest

patches:
  - target:
      kind: Deployment
      name: auth
      namespace: core
    patch: |
      - op: add
        path: /spec/template/spec/containers/0/imagePullPolicy
        value: Always
  - target:
      kind: Deployment
      name: fe
      namespace: core
    patch: |
      - op: add
        path: /spec/template/spec/containers/0/imagePullPolicy
        value: Always
  - target:
      kind: ConfigMap
      name: auth-config
      namespace: core
    patch: |
      - op: replace
        path: /data/google-redirect-uri
        value: "https://bara.world/auth/google/callback"
```

- [ ] **Step 2: kustomize build로 검증**

```bash
kubectl kustomize infra/k8s/overlays/prod/
```

Expected: auth 이미지가 `asia-northeast3-docker.pkg.dev/bara-world/bara/auth:latest`로 변환됨. `imagePullPolicy: Always`.

- [ ] **Step 3: Commit**

```bash
git add infra/k8s/overlays/
git commit -m "ci(infra): Kustomize prod overlay 추가"
```

---

### Task 4: k8s.sh 스크립트 Kustomize 적용

`scripts/k8s.sh`를 Kustomize 기반으로 수정.

**Files:**
- Modify: `scripts/k8s.sh`

- [ ] **Step 1: apply_manifests() 함수 수정**

`scripts/k8s.sh`의 `apply_manifests()` 함수를 아래로 교체:

```bash
apply_manifests() {
    echo "──── 매니페스트 적용 ────"
    if ! kubectl cluster-info &>/dev/null; then
        echo "  ⚠ 클러스터에 연결할 수 없음. 먼저 ./scripts/k8s.sh start 실행"
        exit 1
    fi
    kubectl apply -k "$K8S_DIR/overlays/dev/"
    create_secrets
    echo "✓ 매니페스트 적용 완료"
}
```

- [ ] **Step 2: K8S_DIR 확인**

`K8S_DIR="$PROJECT_ROOT/infra/k8s"` — 기존 값 그대로. 변경 불필요.

- [ ] **Step 3: Commit**

```bash
git add scripts/k8s.sh
git commit -m "ci(infra): k8s.sh를 Kustomize 기반으로 전환"
```

---

### Task 5: CI 워크플로우 — ci-auth.yml

auth 서비스 PR 빌드/테스트 워크플로우.

**Files:**
- Create: `.github/workflows/ci-auth.yml`

- [ ] **Step 1: ci-auth.yml 생성**

`.github/workflows/ci-auth.yml`:
```yaml
name: CI — Auth

on:
  pull_request:
    branches: [develop, main]
    paths:
      - "apps/auth/**"
      - "libs/**"
      - "build-logic/**"
      - "gradle/**"
      - "settings.gradle.kts"
      - "build.gradle.kts"

permissions:
  contents: read

jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Setup JDK 21
        uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9 # v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Test
        run: ./gradlew :apps:auth:test

      - name: Build
        run: ./gradlew :apps:auth:bootJar
```

- [ ] **Step 2: YAML 문법 검증**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci-auth.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci-auth.yml
git commit -m "ci(auth): PR 빌드/테스트 워크플로우 추가"
```

---

### Task 6: CI 워크플로우 — ci-fe.yml

FE PR 빌드/테스트 워크플로우.

**Files:**
- Create: `.github/workflows/ci-fe.yml`

- [ ] **Step 1: ci-fe.yml 생성**

`.github/workflows/ci-fe.yml`:
```yaml
name: CI — FE

on:
  pull_request:
    branches: [develop, main]
    paths:
      - "apps/fe/**"

permissions:
  contents: read

jobs:
  build-test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: apps/fe
    steps:
      - name: Checkout
        uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Setup pnpm
        uses: pnpm/action-setup@b906affcce14559ad1aafd4ab0e942779e9f58b1 # v4
        with:
          version: 10

      - name: Setup Node.js
        uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4
        with:
          node-version: "22"
          cache: pnpm
          cache-dependency-path: apps/fe/pnpm-lock.yaml

      - name: Install dependencies
        run: pnpm install --frozen-lockfile

      - name: Test
        run: pnpm test

      - name: Build
        run: pnpm build
```

- [ ] **Step 2: YAML 문법 검증**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci-fe.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci-fe.yml
git commit -m "ci(infra): FE PR 빌드/테스트 워크플로우 추가"
```

---

### Task 7: CD 워크플로우 — deploy.yml

main 머지 시 변경 감지 → Docker 빌드 → Artifact Registry push → SSH 배포.

**Files:**
- Create: `.github/workflows/deploy.yml`

- [ ] **Step 1: deploy.yml 생성**

`.github/workflows/deploy.yml`:
```yaml
name: Deploy

on:
  push:
    branches: [main]

permissions:
  contents: read
  id-token: write

env:
  REGISTRY: asia-northeast3-docker.pkg.dev
  REPOSITORY: bara-world/bara

jobs:
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      auth: ${{ steps.filter.outputs.auth }}
      fe: ${{ steps.filter.outputs.fe }}
    steps:
      - name: Checkout
        uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Detect changes
        uses: dorny/paths-filter@de90cc6fb38fc0963ad72b210f1f284cd68cea36 # v3
        id: filter
        with:
          filters: |
            auth:
              - 'apps/auth/**'
              - 'libs/**'
              - 'build-logic/**'
              - 'gradle/**'
              - 'settings.gradle.kts'
              - 'build.gradle.kts'
            fe:
              - 'apps/fe/**'

  build-push-auth:
    needs: detect-changes
    if: needs.detect-changes.outputs.auth == 'true'
    runs-on: ubuntu-latest
    environment: production
    steps:
      - name: Checkout
        uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Authenticate to GCP
        uses: google-github-actions/auth@c200f3691d83b41bf9bbd8638997a462592937ed # v2
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Setup Docker Buildx
        uses: docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f # v3

      - name: Login to Artifact Registry
        uses: docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9 # v3
        with:
          registry: ${{ env.REGISTRY }}

      - name: Build and push auth
        uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8 # v6
        with:
          context: .
          file: apps/auth/Dockerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.REPOSITORY }}/auth:${{ github.sha }}
            ${{ env.REGISTRY }}/${{ env.REPOSITORY }}/auth:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  build-push-fe:
    needs: detect-changes
    if: needs.detect-changes.outputs.fe == 'true'
    runs-on: ubuntu-latest
    environment: production
    steps:
      - name: Checkout
        uses: actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4

      - name: Authenticate to GCP
        uses: google-github-actions/auth@c200f3691d83b41bf9bbd8638997a462592937ed # v2
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Setup Docker Buildx
        uses: docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f # v3

      - name: Login to Artifact Registry
        uses: docker/login-action@c94ce9fb468520275223c153574b00df6fe4bcc9 # v3
        with:
          registry: ${{ env.REGISTRY }}

      - name: Build and push fe
        uses: docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8 # v6
        with:
          context: .
          file: apps/fe/Dockerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.REPOSITORY }}/fe:${{ github.sha }}
            ${{ env.REGISTRY }}/${{ env.REPOSITORY }}/fe:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    needs: [detect-changes, build-push-auth, build-push-fe]
    if: always() && (needs.build-push-auth.result == 'success' || needs.build-push-fe.result == 'success')
    runs-on: ubuntu-latest
    environment: production
    steps:
      - name: Mask sensitive values
        run: |
          echo "::add-mask::${{ secrets.VM_HOST }}"

      - name: Deploy via SSH
        uses: appleboy/ssh-action@0ff4204d59e8e51228ff73bce53f80d53301dee2 # v1
        env:
          AUTH_CHANGED: ${{ needs.detect-changes.outputs.auth }}
          FE_CHANGED: ${{ needs.detect-changes.outputs.fe }}
          IMAGE_TAG: ${{ github.sha }}
          REGISTRY: asia-northeast3-docker.pkg.dev
          REPOSITORY: bara-world/bara
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          envs: AUTH_CHANGED,FE_CHANGED,IMAGE_TAG,REGISTRY,REPOSITORY
          script: |
            set -euo pipefail

            # Artifact Registry 인증
            gcloud auth configure-docker asia-northeast3-docker.pkg.dev --quiet

            # 변경된 이미지 pull
            if [ "$AUTH_CHANGED" = "true" ]; then
              docker pull $REGISTRY/$REPOSITORY/auth:$IMAGE_TAG
            fi
            if [ "$FE_CHANGED" = "true" ]; then
              docker pull $REGISTRY/$REPOSITORY/fe:$IMAGE_TAG
            fi

            # Parameter Manager에서 시크릿 pull + K8s Secret 갱신
            cd ~/bara-world
            ./scripts/secrets.sh pull
            kubectl delete secret auth-secrets -n core --ignore-not-found
            kubectl create secret generic auth-secrets -n core \
              --from-env-file=.env.prod

            # 변경된 서비스 배포
            if [ "$AUTH_CHANGED" = "true" ]; then
              kubectl set image deployment/auth -n core \
                auth=$REGISTRY/$REPOSITORY/auth:$IMAGE_TAG
              kubectl rollout status deployment/auth -n core --timeout=120s
            fi
            if [ "$FE_CHANGED" = "true" ]; then
              kubectl set image deployment/fe -n core \
                fe=$REGISTRY/$REPOSITORY/fe:$IMAGE_TAG
              kubectl rollout status deployment/fe -n core --timeout=120s
            fi

            echo "Deploy complete"
```

- [ ] **Step 2: YAML 문법 검증**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/deploy.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "ci(infra): main 배포 워크플로우 추가"
```

---

### Task 8: CODEOWNERS + Dependabot 설정

보안: `.github/workflows/` 보호 + Action SHA 자동 업데이트.

**Files:**
- Create: `.github/CODEOWNERS`
- Create: `.github/dependabot.yml`

- [ ] **Step 1: CODEOWNERS 생성**

`.github/CODEOWNERS`:
```
# CI/CD 워크플로우 변경은 반드시 소유자 승인 필요
/.github/workflows/ @genie49
```

- [ ] **Step 2: dependabot.yml 생성**

`.github/dependabot.yml`:
```yaml
version: 2
updates:
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
    commit-message:
      prefix: "ci(infra)"
```

- [ ] **Step 3: Commit**

```bash
git add .github/CODEOWNERS .github/dependabot.yml
git commit -m "ci(infra): CODEOWNERS 및 Dependabot 설정 추가"
```

---

### Task 9: 문서 업데이트

CLAUDE.md와 설계 문서에 CI/CD 섹션 반영.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: CLAUDE.md에 CI/CD 섹션 추가**

`CLAUDE.md`의 `### Docker 빌드 & 통합 테스트` 섹션 뒤에 아래 추가:

```markdown
### CI/CD

- **CI**: PR → `develop`/`main` 시 자동 빌드/테스트 (path filter로 변경 서비스만)
  - `ci-auth.yml`: Gradle test + bootJar
  - `ci-fe.yml`: pnpm test + build
- **CD**: `main` 머지 시 자동 배포
  - 변경 감지 → Docker build → GCP Artifact Registry push → SSH → OCI VM(K3s) 배포
  - 상세 설계: `docs/spec/shared/ci-cd-design.md`
- **K8s 환경 분리**: Kustomize (`infra/k8s/overlays/dev/`, `infra/k8s/overlays/prod/`)
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(infra): CLAUDE.md에 CI/CD 섹션 추가"
```

---

### Task 10: 최종 검증 + PR

모든 변경사항 검증 후 PR 생성.

- [ ] **Step 1: Kustomize 빌드 검증**

```bash
kubectl kustomize infra/k8s/overlays/dev/
kubectl kustomize infra/k8s/overlays/prod/
```

Expected: 두 명령 모두 에러 없이 YAML 출력.

- [ ] **Step 2: YAML 문법 일괄 검증**

```bash
for f in .github/workflows/*.yml; do
  python3 -c "import yaml; yaml.safe_load(open('$f'))" && echo "$f OK"
done
```

Expected: 모든 파일 OK.

- [ ] **Step 3: PR 생성**

```bash
git push -u origin ci/infra/github-actions-pipeline
gh pr create --base develop \
  --title "ci(infra): GitHub Actions CI/CD 파이프라인 추가" \
  --body "## Summary
- auth/FE 서비스별 독립 CI 워크플로우 (PR 빌드/테스트)
- main 머지 시 자동 배포 (Artifact Registry → SSH → K3s)
- Kustomize로 dev/prod 환경 분리
- 보안: Action SHA 고정, CODEOWNERS, Dependabot, Environment protection

## Test plan
- [ ] Kustomize build 검증 (dev/prod)
- [ ] YAML 문법 검증
- [ ] GitHub Actions 문법 검증 (actionlint)"
```
