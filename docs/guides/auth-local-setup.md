# Auth Service 로컬 개발 세팅

Google OAuth 로그인과 자체 JWT 발급이 동작하는 Auth Service를 로컬에서 처음 실행할 때까지의 단계.

프론트엔드(`apps/fe`)까지 포함하여 end-to-end 로그인 플로우를 확인할 수 있다.

## 전제 조건

- JDK 21 (Homebrew: `brew install openjdk@21`)
- Docker & Docker Compose (로컬 인프라용)
- k3d (통합 테스트용, `brew install k3d`)
- Node.js 20+ / pnpm
- Google 계정 (OAuth Client 생성용)
- `openssl` (RSA 키 생성용, macOS/Linux 기본 포함)

## 1. RSA 키쌍 생성

Auth Service가 발급하는 자체 JWT는 RS256으로 서명한다. 개발용 키쌍을 1회 생성한다.

```bash
openssl genrsa -out /tmp/jwt-priv.pem 2048
openssl rsa -in /tmp/jwt-priv.pem -pubout -out /tmp/jwt-pub.pem
echo "BARA_AUTH_JWT_PRIVATE_KEY=$(base64 < /tmp/jwt-priv.pem | tr -d '\n')"
echo "BARA_AUTH_JWT_PUBLIC_KEY=$(base64 < /tmp/jwt-pub.pem | tr -d '\n')"
rm /tmp/jwt-priv.pem /tmp/jwt-pub.pem
```

마지막 두 `echo` 출력을 복사해둔다. `.env` 파일에 붙여넣을 것이다.

키는 파일로 저장하지 않고 base64 한 줄로 환경변수에 담는다. 로컬/Docker/K8s에서 동일한 주입 방식을 쓰기 위함.

## 2. Google OAuth 2.0 Client 생성

1. [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials) 접속
2. 프로젝트 선택 또는 생성
3. **Create Credentials** → **OAuth client ID**
4. 애플리케이션 유형: **Web application**
5. 이름: `bara-web` (임의)
6. **Authorized redirect URIs**: `http://localhost/auth/google/callback`
   - 승인된 JavaScript 원본은 비워둔다.
   - 대소문자/경로 정확히 일치해야 한다. 끝에 슬래시 금지.
7. **Create**
8. 생성 직후 대화상자에서 **Client ID**와 **Client Secret**을 즉시 복사한다. Secret은 다시 볼 수 없다.

첫 실행 시 Google이 "이 앱은 확인되지 않음" 경고를 보여줄 수 있다. 개발자 본인 계정이면 **Advanced → Go to bara-web (unsafe)**로 진입. 테스트 사용자에 추가하면 경고 없이 진행된다.

## 3. `.env` 파일 작성

```bash
cp .env.example .env
```

편집기로 `.env`를 열고 다음 값을 채운다.

| 변수                             | 출처                                                       |
| -------------------------------- | ---------------------------------------------------------- |
| `BARA_AUTH_JWT_PRIVATE_KEY`      | 1단계 출력                                                 |
| `BARA_AUTH_JWT_PUBLIC_KEY`       | 1단계 출력                                                 |
| `BARA_AUTH_GOOGLE_CLIENT_ID`     | 2단계 Google Console                                       |
| `BARA_AUTH_GOOGLE_CLIENT_SECRET` | 2단계 Google Console                                       |
| `BARA_AUTH_GOOGLE_REDIRECT_URI`  | 기본값 `http://localhost/auth/google/callback` 그대로 |

`.env`는 `.gitignore`에 포함되어 커밋되지 않는다. Secret을 절대 PR/스크린샷에 노출하지 말 것. 노출되면 Google Console에서 즉시 Secret을 rotate한다.

## 4. 인프라 기동

MongoDB, Redis, Kafka 컨테이너를 띄운다.

```bash
./scripts/infra.sh up dev
```

`docker ps`로 `infra-mongodb-1`, `infra-redis-1`, `infra-kafka-1` 3개가 running 상태인지 확인.

## 5. Auth Service 실행

```bash
./gradlew :apps:auth:bootRun
```

로그 확인 포인트:

- `Started BaraAuthApplication in X.XXX seconds`
- `Tomcat started on port 8081`
- MongoDB 연결 성공 (`Monitor thread successfully connected to server`)
- Redis 관련 에러 없음

`bootRun` task는 루트의 `.env` 파일을 읽어 환경변수로 주입한다(`apps/auth/build.gradle.kts`에 구현). 별도로 셸에서 export할 필요 없다.

헬스체크 확인:

```bash
curl -s http://localhost:8081/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

OAuth 시작 엔드포인트 확인 (302 리다이렉트):

```bash
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" http://localhost:8081/auth/google/login
# 302 https://accounts.google.com/o/oauth2/v2/auth?client_id=...&state=...
```

## 6. Frontend 실행

로컬 개발과 Docker 테스트, 두 가지 방식이 있다.

### 방법 A: Vite dev server (로컬 개발용)

```bash
cd apps/fe
pnpm install   # 첫 실행 시 1회
pnpm dev
```

Vite dev server가 5173 포트에서 뜬다. `/auth/google/*` 경로는 Vite proxy가 자동으로 `http://localhost:8081`로 포워딩한다 (`vite.config.ts`).

> **주의:** 이 방식은 Google OAuth redirect URI를 `http://localhost:5173/auth/google/callback`으로 설정해야 한다. `.env`의 `BARA_AUTH_GOOGLE_REDIRECT_URI`와 Google Console 모두 변경 필요.

### 방법 B: k3d (통합 테스트용)

Traefik 게이트웨이를 포함한 전체 스택을 k3d 클러스터로 실행한다.

```bash
./scripts/docker.sh build          # Docker 이미지 빌드 (auth, fe)
./scripts/k8s.sh create            # k3d 클러스터 생성 + 매니페스트 적용
```

`localhost:80`으로 접속한다. Traefik(K3s 내장)이 K8s Gateway API 규칙에 따라 `/auth/**` → Auth Service, `/` → FE로 라우팅한다. `.env`는 `k8s.sh`가 자동으로 K8s Secret으로 주입한다.

> **이 방식이 기본이다.** `.env.example`의 redirect URI 기본값(`http://localhost/auth/google/callback`)은 이 구성에 맞춰져 있다.

## 7. End-to-End 테스트

1. 브라우저에서 `http://localhost/` 접속 (k3d) 또는 `http://localhost:5173/` 접속 (Vite dev)
2. **Login with Google** 클릭
3. Google 로그인 페이지에서 계정 선택 및 동의
4. `/me` 페이지로 자동 이동
5. 이메일, Role(`USER`), User ID(UUID), 만료 시각이 표시되는지 확인
6. **Copy token** 버튼으로 JWT 복사 → [jwt.io](https://jwt.io)에서 payload 구조 확인

동일 Google 계정으로 재로그인 시 MongoDB `users` 컬렉션에 row가 중복 생성되지 않는지 확인:

```bash
docker exec -it $(docker ps -qf name=mongo) mongosh bara-auth --eval 'db.users.find().pretty()'
```

`googleId` 필드에 unique index가 걸려있어 중복 저장이 차단된다.

## 트러블슈팅

| 증상                                            | 원인                                                   | 해결                                                                                                    |
| ----------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------- |
| `Missing key encoding` 예외로 앱 시작 실패      | `.env`의 JWT 키가 비었거나 base64가 깨짐               | 1단계 명령을 다시 실행하여 값을 갱신                                                                    |
| `UnsatisfiedDependencyException`                | `kotlin-reflect`가 classpath에 없음                    | 이미 `apps/auth/build.gradle.kts`에 포함. Gradle 캐시 지우고 재빌드: `./gradlew :apps:auth:clean build` |
| Google에서 `redirect_uri_mismatch` 에러         | Console에 등록한 redirect URI와 `.env`의 값이 불일치   | 문자 단위로 동일한지 확인. 끝 슬래시/http-https/대소문자 주의                                           |
| FE에서 로그인 버튼 클릭 시 404                  | Vite dev server가 실행 중이지 않거나 proxy 경로가 틀림 | `apps/fe` 디렉토리에서 `pnpm dev` 실행 확인                                                         |
| `redirect_uri_mismatch` + 로컬만 수정해도 안 됨 | Google Console 변경사항 반영 지연                      | 수 분 대기 후 재시도                                                                                    |
| `MongoSocketOpenException`                      | Docker Compose의 MongoDB가 기동 전                     | `./scripts/infra.sh up dev` 확인, `docker ps`로 컨테이너 상태 점검                                      |
| `Address already in use: 8081`                  | 이전 bootRun이 종료되지 않음                           | `lsof -ti:8081 \| xargs kill`                                                                           |

## 관련 문서

- [git-convention.md](git-convention.md) — 커밋/브랜치 컨벤션
- [../spec/auth/authentication.md](../spec/auth/authentication.md) — Auth Service 전체 설계 (Provider API Key, Kafka 토큰 등 포함)
- [../superpowers/specs/2026-04-05-auth-google-oauth-jwt-design.md](../superpowers/specs/2026-04-05-auth-google-oauth-jwt-design.md) — 이번 구현의 상세 설계 및 범위
