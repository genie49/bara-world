# Husky + Git Convention 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bara World 모노레포에 Husky 기반 Git hooks와 커밋/브랜치 컨벤션을 적용하여 일관된 커밋 메시지, 브랜치 네이밍, 코드 포맷팅을 강제한다.

**Architecture:** 루트 `package.json`에 devDependencies로 Git 도구만 관리. Husky가 3개의 Git hooks(commit-msg, pre-commit, pre-push)를 등록하고, commitlint/lint-staged/shell 스크립트가 각 hook에서 검증을 수행한다.

**Tech Stack:** Node.js, Husky v9, commitlint v19, lint-staged v15, Prettier v3

---

## 파일 구조

| 파일 | 역할 |
|------|------|
| `package.json` | devDependencies + prepare 스크립트 |
| `.commitlintrc.json` | 커밋 메시지 규칙 (타입, scope, 길이) |
| `.lintstagedrc.json` | 스테이징 파일 포맷팅 규칙 |
| `.prettierrc` | Prettier 설정 |
| `.husky/commit-msg` | Co-Authored-By 차단 → commitlint 실행 |
| `.husky/pre-commit` | lint-staged 실행 |
| `.husky/pre-push` | main/develop 직접 push 차단 + 브랜치 네이밍 검증 |
| `.gitignore` | node_modules 추가 |
| `docs/guides/git-convention.md` | 개발자용 컨벤션 가이드 |

---

### Task 1: package.json 초기화 및 의존성 설치

**Files:**
- Create: `package.json`

- [ ] **Step 1: package.json 생성**

```bash
cd /Users/genie/workspace/bara-world
npm init -y
```

- [ ] **Step 2: package.json을 Git 도구 전용으로 정리**

`package.json`을 다음 내용으로 교체:

```json
{
  "name": "bara-world",
  "private": true,
  "description": "Google A2A 기반 멀티 에이전트 플랫폼",
  "scripts": {
    "prepare": "husky"
  }
}
```

- [ ] **Step 3: devDependencies 설치**

```bash
npm install -D husky @commitlint/cli @commitlint/config-conventional lint-staged prettier
```

Expected: `node_modules/` 생성, `package-lock.json` 생성, `package.json`에 devDependencies 추가됨

- [ ] **Step 4: .gitignore에 node_modules 추가**

`.gitignore`에 `node_modules/` 추가:

```
.DS_Store
node_modules/
```

- [ ] **Step 5: Husky 초기화**

```bash
npx husky init
```

Expected: `.husky/` 디렉토리 생성, `.husky/pre-commit` 기본 파일 생성

- [ ] **Step 6: 커밋**

```bash
git add package.json package-lock.json .gitignore .husky/
git commit -m "chore(infra): Husky 및 Git 도구 초기화"
```

---

### Task 2: commitlint 설정

**Files:**
- Create: `.commitlintrc.json`

- [ ] **Step 1: .commitlintrc.json 생성**

```json
{
  "extends": ["@commitlint/config-conventional"],
  "rules": {
    "type-enum": [
      2,
      "always",
      ["feat", "fix", "docs", "style", "refactor", "test", "chore", "ci", "perf"]
    ],
    "scope-enum": [
      2,
      "always",
      ["api", "auth", "scheduler", "sdk", "infra", "docs"]
    ],
    "scope-empty": [2, "never"],
    "header-max-length": [2, "always", 100],
    "subject-full-stop": [2, "never", "."]
  }
}
```

- [ ] **Step 2: 유효한 커밋 메시지 테스트**

```bash
echo "feat(api): 테스트 메시지" | npx commitlint
```

Expected: 에러 없이 통과

- [ ] **Step 3: 잘못된 타입 테스트**

```bash
echo "invalid(api): 잘못된 타입" | npx commitlint
```

Expected: `type must be one of [feat, fix, ...]` 에러

- [ ] **Step 4: 잘못된 scope 테스트**

```bash
echo "feat(wrong): 잘못된 스코프" | npx commitlint
```

Expected: `scope must be one of [api, auth, ...]` 에러

- [ ] **Step 5: scope 누락 테스트**

```bash
echo "feat: 스코프 없음" | npx commitlint
```

Expected: `scope may not be empty` 에러

- [ ] **Step 6: 긴 header 테스트**

```bash
python3 -c "print('feat(api): ' + 'a' * 100)" | npx commitlint
```

Expected: `header must not be longer than 100 characters` 에러

- [ ] **Step 7: 커밋**

```bash
git add .commitlintrc.json
git commit -m "chore(infra): commitlint 규칙 설정"
```

---

### Task 3: commit-msg hook 설정 (Co-Authored-By 차단 + commitlint)

**Files:**
- Create: `.husky/commit-msg`

- [ ] **Step 1: .husky/commit-msg 작성**

```bash
#!/bin/sh

# Co-Authored-By 차단 (대소문자 무관)
if grep -qi "Co-Authored-By:" "$1"; then
  echo ""
  echo "❌ Co-Authored-By 트레일러는 허용되지 않습니다."
  echo "   커밋 메시지에서 Co-Authored-By 라인을 제거해주세요."
  echo ""
  exit 1
fi

# commitlint 실행
npx --no -- commitlint --edit "$1"
```

- [ ] **Step 2: 실행 권한 부여**

```bash
chmod +x .husky/commit-msg
```

- [ ] **Step 3: Co-Authored-By 차단 테스트**

```bash
git commit --allow-empty -m "$(cat <<'EOF'
feat(api): 테스트

Co-Authored-By: Someone <test@test.com>
EOF
)"
```

Expected: `❌ Co-Authored-By 트레일러는 허용되지 않습니다.` 에러로 커밋 실패

- [ ] **Step 4: 정상 커밋 테스트**

```bash
git commit --allow-empty -m "feat(api): commit-msg hook 동작 확인"
```

Expected: 커밋 성공

- [ ] **Step 5: 테스트 커밋 제거 후 실제 커밋**

```bash
git reset HEAD~1
git add .husky/commit-msg
git commit -m "chore(infra): commit-msg hook 설정 (Co-Authored-By 차단 + commitlint)"
```

---

### Task 4: Prettier + lint-staged 설정

**Files:**
- Create: `.prettierrc`
- Create: `.lintstagedrc.json`
- Modify: `.husky/pre-commit`

- [ ] **Step 1: .prettierrc 생성**

```json
{
  "semi": true,
  "singleQuote": true,
  "trailingComma": "all",
  "printWidth": 100,
  "tabWidth": 2
}
```

- [ ] **Step 2: .lintstagedrc.json 생성**

```json
{
  "*.{json,md,yml,yaml}": ["prettier --write"]
}
```

- [ ] **Step 3: .husky/pre-commit 수정**

```bash
#!/bin/sh

npx lint-staged
```

- [ ] **Step 4: 실행 권한 확인**

```bash
chmod +x .husky/pre-commit
```

- [ ] **Step 5: Prettier 동작 테스트**

포맷이 잘못된 JSON 파일을 만들어 테스트:

```bash
echo '{"test":   "value",   "foo":    "bar"}' > /tmp/test-format.json
npx prettier --check /tmp/test-format.json
```

Expected: 포맷 오류 감지

```bash
npx prettier --write /tmp/test-format.json && cat /tmp/test-format.json
```

Expected: 포맷 정리된 JSON 출력

```bash
rm /tmp/test-format.json
```

- [ ] **Step 6: 커밋**

```bash
git add .prettierrc .lintstagedrc.json .husky/pre-commit
git commit -m "chore(infra): Prettier + lint-staged 설정"
```

---

### Task 5: pre-push hook 설정 (브랜치 보호 + 네이밍 검증)

**Files:**
- Create: `.husky/pre-push`

- [ ] **Step 1: .husky/pre-push 작성**

```bash
#!/bin/sh

BRANCH=$(git symbolic-ref --short HEAD)

# main/develop 직접 push 차단
if [ "$BRANCH" = "main" ] || [ "$BRANCH" = "develop" ]; then
  echo ""
  echo "❌ '$BRANCH' 브랜치에 직접 push할 수 없습니다."
  echo "   PR을 통해 머지해주세요."
  echo ""
  exit 1
fi

# 브랜치 네이밍 검증
# 허용 패턴: type/scope/description 또는 release/*, hotfix/*
TYPES="feat|fix|docs|style|refactor|test|chore|ci|perf"
SCOPES="api|auth|scheduler|sdk|infra|docs"
VALID_PATTERN="^(($TYPES)/($SCOPES)/[a-z][a-z0-9-]*|release/.+|hotfix/.+)$"

if ! echo "$BRANCH" | grep -qE "$VALID_PATTERN"; then
  echo ""
  echo "❌ 브랜치 이름이 컨벤션에 맞지 않습니다: $BRANCH"
  echo ""
  echo "   허용 형식:"
  echo "     type/scope/description  (예: feat/api/agent-registration)"
  echo "     release/*               (예: release/1.0.0)"
  echo "     hotfix/*                (예: hotfix/critical-fix)"
  echo ""
  echo "   허용 type:  $TYPES"
  echo "   허용 scope: $SCOPES"
  echo ""
  exit 1
fi
```

- [ ] **Step 2: 실행 권한 부여**

```bash
chmod +x .husky/pre-push
```

- [ ] **Step 3: 현재 브랜치에서 push 테스트**

현재 브랜치가 `chore/infra/husky-git-convention`이므로 패턴에 맞음. push가 성공해야 한다.

```bash
# 스크립트 로직만 단독 테스트
BRANCH="chore/infra/husky-git-convention"
TYPES="feat|fix|docs|style|refactor|test|chore|ci|perf"
SCOPES="api|auth|scheduler|sdk|infra|docs"
VALID_PATTERN="^(($TYPES)/($SCOPES)/[a-z][a-z0-9-]*|release/.+|hotfix/.+)$"
echo "$BRANCH" | grep -qE "$VALID_PATTERN" && echo "PASS" || echo "FAIL"
```

Expected: `PASS`

- [ ] **Step 4: 잘못된 브랜치 이름 테스트**

```bash
BRANCH="my-feature"
echo "$BRANCH" | grep -qE "$VALID_PATTERN" && echo "PASS" || echo "FAIL"
```

Expected: `FAIL`

- [ ] **Step 5: main 브랜치 차단 테스트**

```bash
BRANCH="main"
if [ "$BRANCH" = "main" ] || [ "$BRANCH" = "develop" ]; then echo "BLOCKED"; else echo "ALLOWED"; fi
```

Expected: `BLOCKED`

- [ ] **Step 6: 커밋**

```bash
git add .husky/pre-push
git commit -m "chore(infra): pre-push hook 설정 (브랜치 보호 + 네이밍 검증)"
```

---

### Task 6: 가이드 문서 작성

**Files:**
- Create: `docs/guides/git-convention.md`

- [ ] **Step 1: docs/guides/ 디렉토리 생성**

```bash
mkdir -p docs/guides
```

- [ ] **Step 2: git-convention.md 작성**

```markdown
# Git Convention 가이드

Bara World 모노레포의 Git 컨벤션 규칙입니다.
Husky + commitlint로 자동 검증되므로 규칙에 맞지 않으면 커밋/push가 거부됩니다.

## 커밋 메시지

### 형식

\`\`\`
type(scope): subject

[optional body]

[optional footer]
\`\`\`

### 타입

| 타입 | 용도 |
|------|------|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서 변경 |
| `style` | 코드 포맷 (동작 변경 없음) |
| `refactor` | 리팩토링 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 도구, 설정 변경 |
| `ci` | CI/CD 설정 변경 |
| `perf` | 성능 개선 |

### Scope

반드시 아래 중 하나를 사용해야 합니다:

| Scope | 대상 |
|-------|------|
| `api` | API 서비스 |
| `auth` | 인증 서비스 |
| `scheduler` | 스케줄러 서비스 |
| `sdk` | SDK (Python, TypeScript, Java) |
| `infra` | 인프라, 빌드, 도구 설정 |
| `docs` | 문서 |

새로운 scope가 필요하면 `.commitlintrc.json`의 `scope-enum`에 추가합니다.

### 규칙

- scope는 **필수**입니다
- header는 **100자 이내**
- subject 끝에 **마침표 금지**
- `Co-Authored-By` 트레일러 **사용 금지**

### 예시

\`\`\`bash
# ✅ 올바른 예시
git commit -m "feat(api): Agent 등록 엔드포인트 추가"
git commit -m "fix(auth): JWT 토큰 갱신 로직 수정"
git commit -m "chore(infra): K3s 배포 스크립트 업데이트"

# ❌ 잘못된 예시
git commit -m "feat: scope 누락"
git commit -m "feat(wrong): 잘못된 scope"
git commit -m "update: 잘못된 타입"
\`\`\`

## 브랜치

### 형식

\`\`\`
type/scope/설명
\`\`\`

### 규칙

- 타입: 커밋 타입과 동일
- scope: 커밋 scope와 동일
- 설명: **영문 소문자 + 하이픈** (kebab-case)
- 특수 브랜치: `main`, `develop`, `release/*`, `hotfix/*`

### 예시

\`\`\`bash
# ✅ 올바른 예시
git checkout -b feat/api/agent-registration
git checkout -b fix/auth/jwt-refresh
git checkout -b chore/infra/k3s-deploy

# ❌ 잘못된 예시
git checkout -b feature/add-agent     # 잘못된 형식
git checkout -b feat/api/AgentReg     # 대문자 불가
\`\`\`

### 보호 브랜치

`main`과 `develop`에는 **직접 push할 수 없습니다**. PR을 통해 머지하세요.

## 설정

프로젝트를 클론한 후 `npm install`을 실행하면 Husky가 자동으로 Git hooks를 설정합니다.

\`\`\`bash
git clone <repo-url>
cd bara-world
npm install    # Husky 자동 설정 (prepare 스크립트)
\`\`\`
```

- [ ] **Step 3: 커밋**

```bash
git add docs/guides/git-convention.md
git commit -m "docs(docs): Git Convention 가이드 문서 추가"
```

---

### Task 7: 전체 통합 테스트

- [ ] **Step 1: commitlint 정상 케이스 확인**

```bash
echo "feat(api): 정상 커밋" | npx commitlint
```

Expected: 통과

- [ ] **Step 2: commitlint 실패 케이스 확인**

```bash
echo "feat: scope 없음" | npx commitlint
echo "wrong(api): 잘못된 타입" | npx commitlint
echo "feat(bad): 잘못된 scope" | npx commitlint
```

Expected: 모두 에러

- [ ] **Step 3: commit-msg hook 통합 테스트**

```bash
git commit --allow-empty -m "feat(api): hook 통합 테스트"
```

Expected: 커밋 성공

```bash
git reset HEAD~1
```

- [ ] **Step 4: Co-Authored-By 차단 확인**

```bash
git commit --allow-empty -m "$(cat <<'EOF'
feat(api): co-author 테스트

Co-Authored-By: Test <test@test.com>
EOF
)"
```

Expected: 커밋 거부

- [ ] **Step 5: pre-push 브랜치 보호 스크립트 테스트**

```bash
# 현재 브랜치(chore/infra/husky-git-convention)로 push 테스트
git push -u origin chore/infra/husky-git-convention
```

Expected: push 성공 (유효한 브랜치 이름)

- [ ] **Step 6: 테스트 완료 커밋**

테스트 중 변경사항이 있다면 정리 커밋. 없으면 스킵.
