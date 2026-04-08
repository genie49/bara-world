# Git Convention 가이드

Bara World 모노레포의 Git 컨벤션 규칙입니다.
Husky + commitlint로 자동 검증되므로 규칙에 맞지 않으면 커밋/push가 거부됩니다.

## 커밋 메시지

### 형식

```
type(scope): subject

[optional body]

[optional footer]
```

### 타입

| 타입       | 용도                       |
| ---------- | -------------------------- |
| `feat`     | 새 기능                    |
| `fix`      | 버그 수정                  |
| `docs`     | 문서 변경                  |
| `style`    | 코드 포맷 (동작 변경 없음) |
| `refactor` | 리팩토링                   |
| `test`     | 테스트 추가/수정           |
| `chore`    | 빌드, 도구, 설정 변경      |
| `ci`       | CI/CD 설정 변경            |
| `perf`     | 성능 개선                  |

### Scope

반드시 아래 중 하나를 사용해야 합니다:

| Scope       | 대상                           |
| ----------- | ------------------------------ |
| `api`       | API 서비스                     |
| `auth`      | 인증 서비스                    |
| `scheduler` | 스케줄러 서비스                |
| `sdk`       | SDK (Python, TypeScript, Java) |
| `infra`     | 인프라, 빌드, 도구 설정        |
| `fe`        | 웹 FE (apps/fe)                |
| `clients`   | 클라이언트 (clients/)          |
| `docs`      | 문서                           |

새로운 scope가 필요하면 `.commitlintrc.json`의 `scope-enum`에 추가합니다.

### 규칙

- scope는 **필수**입니다
- header는 **100자 이내**
- subject 끝에 **마침표 금지**
- `Co-Authored-By` 트레일러 **사용 금지**

### 예시

```bash
# 올바른 예시
git commit -m "feat(api): Agent 등록 엔드포인트 추가"
git commit -m "fix(auth): JWT 토큰 갱신 로직 수정"
git commit -m "chore(infra): K3s 배포 스크립트 업데이트"

# 잘못된 예시
git commit -m "feat: scope 누락"
git commit -m "feat(wrong): 잘못된 scope"
git commit -m "update: 잘못된 타입"
```

## 브랜치

### 형식

```
type/scope/description
```

### 규칙

- 타입: 커밋 타입과 동일
- scope: 커밋 scope와 동일
- 설명: **영문 소문자 + 하이픈** (kebab-case)
- 특수 브랜치: `main`, `develop`, `release/*`, `hotfix/*`

### 예시

```bash
# 올바른 예시
git checkout -b feat/api/agent-registration
git checkout -b fix/auth/jwt-refresh
git checkout -b chore/infra/k3s-deploy

# 잘못된 예시
git checkout -b feature/add-agent     # 잘못된 형식
git checkout -b feat/api/AgentReg     # 대문자 불가
```

### 보호 브랜치

`main`과 `develop`에는 **직접 push할 수 없습니다**. PR을 통해 머지하세요.

## Pull Request

### 제목

커밋 컨벤션과 동일한 형식을 사용합니다: `type(scope): subject`

```bash
# 올바른 예시
feat(api): Agent 등록 엔드포인트 추가
fix(auth): JWT 토큰 갱신 로직 수정

# 잘못된 예시
Agent 등록 기능 추가          # 타입/scope 없음
```

### 본문

`.github/PULL_REQUEST_TEMPLATE.md` 템플릿이 자동 적용됩니다.
요약, 변경 사항, 체크리스트, 기타 섹션을 작성해주세요.

### 머지 전략

**Merge Commit**을 사용합니다. 브랜치별 커밋 히스토리가 유지됩니다.

## 설정

프로젝트를 클론한 후 `npm install`을 실행하면 Husky가 자동으로 Git hooks를 설정합니다.

```bash
git clone <repo-url>
cd bara-world
npm install    # Husky 자동 설정 (prepare 스크립트)
```
