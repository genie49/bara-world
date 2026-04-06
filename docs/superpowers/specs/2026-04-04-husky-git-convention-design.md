# Husky + Git Convention 설계

## 개요

Bara World 모노레포에 Git hooks(Husky)와 커밋/브랜치 컨벤션을 도입한다.
Agent를 제외한 모든 서비스(API, Auth, Scheduler, SDK, Infra)를 이 레포에서 관리하며,
일관된 커밋 메시지와 브랜치 네이밍을 강제한다.

## 프로젝트 구조 변경

```
bara-world/
├── .husky/
│   ├── commit-msg          # Co-Authored-By 차단 → commitlint 실행
│   ├── pre-commit          # lint-staged 실행
│   └── pre-push            # main/develop 직접 push 차단 + 브랜치 네이밍 검증
├── package.json            # Git 도구 전용 (devDependencies만)
├── .commitlintrc.json      # 커밋 메시지 규칙
├── .lintstagedrc.json      # 스테이징 파일 린트 규칙
├── .prettierrc             # Prettier 설정
├── .gitignore              # node_modules 추가
├── docs/
│   ├── guides/
│   │   └── git-convention.md   # 컨벤션 가이드 문서
│   └── spec/
└── README.md
```

## 도구 및 버전

| 패키지 | 버전 | 역할 |
|--------|------|------|
| husky | ^9 | Git hooks 관리 |
| @commitlint/cli | ^19 | 커밋 메시지 검증 |
| @commitlint/config-conventional | ^19 | Conventional Commits 규칙 |
| lint-staged | ^15 | 스테이징 파일 린트 |
| prettier | ^3 | 코드 포맷팅 |

## 커밋 메시지 컨벤션

### 형식

```
type(scope): subject

[optional body]

[optional footer]
```

### 허용 타입

| 타입 | 용도 |
|------|------|
| feat | 새 기능 |
| fix | 버그 수정 |
| docs | 문서 변경 |
| style | 코드 포맷 (동작 변경 없음) |
| refactor | 리팩토링 |
| test | 테스트 추가/수정 |
| chore | 빌드, 도구, 설정 변경 |
| ci | CI/CD 설정 변경 |
| perf | 성능 개선 |

### 허용 scope (엄격 제한)

`api`, `auth`, `scheduler`, `sdk`, `infra`, `docs`

목록에 없는 scope는 commitlint가 reject한다.
새 scope 추가 시 `.commitlintrc.json`을 수정해야 한다.

### 규칙

- scope 필수
- header 최대 100자
- subject는 마침표로 끝나지 않음

### 예시

```
feat(api): Agent 등록 엔드포인트 추가
fix(auth): JWT 토큰 갱신 로직 수정
chore(infra): K3s 배포 스크립트 업데이트
docs(docs): 인증 설계 문서 보완
```

## Co-Authored-By 차단

commit-msg hook에서 커밋 메시지에 `Co-Authored-By:` 패턴(대소문자 무관)이 포함되어 있으면 reject한다.

## 브랜치 네이밍 컨벤션

### 형식

```
type/scope/설명
```

### 규칙

- 타입: 커밋 컨벤션과 동일한 목록
- scope: `api`, `auth`, `scheduler`, `sdk`, `infra`, `docs`
- 설명: 영문 소문자 + 하이픈 (kebab-case)
- 특수 브랜치: `main`, `develop`, `release/*`, `hotfix/*`

### 예시

```
feat/api/agent-registration
fix/auth/jwt-refresh
docs/docs/auth-spec-update
chore/infra/k3s-deploy-script
```

### 검증

pre-push hook에서 브랜치 이름이 허용된 패턴과 일치하지 않으면 push를 거부한다.

## 브랜치 보호

pre-push hook에서 `main` 또는 `develop` 브랜치에 직접 push하면 reject한다.
이 브랜치들은 PR을 통해서만 변경 가능하다.

## lint-staged 설정

현재 코드가 없으므로 기본 포맷팅만 적용:

```json
{
  "*.{json,md,yml,yaml}": ["prettier --write"]
}
```

서비스 코드 추가 시 확장:
- Kotlin: ktlint
- TypeScript: eslint + prettier
- Python: ruff

## 가이드 문서

`docs/guides/git-convention.md`에 개발자용 컨벤션 가이드를 작성한다.
추후 온보딩 가이드 등 추가 문서도 `docs/guides/`에 배치한다.
