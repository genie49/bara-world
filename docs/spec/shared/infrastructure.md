# 인프라

## OCI VM 스펙

| 항목     | 값                   |
| -------- | -------------------- |
| 인스턴스 | Ampere A1            |
| 아키텍처 | ARM64 (aarch64)      |
| CPU      | 4 OCPU               |
| 메모리   | 24GB RAM             |
| 비용     | OCI Always Free 티어 |

> ARM64 아키텍처이므로 모든 Docker 이미지 빌드 시 `--platform linux/arm64` 명시 필요.

## K3s 구성

단일 노드 K3s 클러스터로 운영한다.

### Namespace 분리

| Namespace | Pod                                                                       |
| --------- | ------------------------------------------------------------------------- |
| `gateway` | nginx                                                                     |
| `core`    | auth-service, api-service, fe-server, telegram-service, scheduler-service |
| `data`    | mongodb, redis, kafka                                                     |
| `logging` | fluent-bit (DaemonSet), loki, grafana                                     |

### 배포 단위

| 유형        | 대상                                                                             | 이유                       |
| ----------- | -------------------------------------------------------------------------------- | -------------------------- |
| Deployment  | auth-service, api-service, fe-server, telegram-service, scheduler-service, nginx | 무상태, replicas 조절 가능 |
| StatefulSet | mongodb, redis, kafka                                                            | 데이터 영속성 필요         |
| DaemonSet   | fluent-bit                                                                       | 노드당 자동 1개            |
| ConfigMap   | Nginx 설정                                                                       |                            |
| Secret      | JWT 키, Provider 토큰 시크릿, Redis/Kafka 인증 정보                              |                            |

초기에는 모든 Deployment replicas를 1로 시작한다. 부하 발생 시 auth-service, api-service만 우선 스케일링.

## 리소스 예산

| 서비스            | 예상 메모리       |
| ----------------- | ----------------- |
| K3s 자체          | 512MB             |
| Nginx             | 200MB             |
| Auth Service      | 512MB             |
| API Service       | 512MB             |
| Redis             | 512MB             |
| MongoDB           | 1,024MB           |
| Kafka (KRaft)     | 1,024MB           |
| FE Server         | 256MB             |
| Telegram Service  | 256MB             |
| Scheduler Service | 256MB             |
| Fluent Bit        | 100MB             |
| Loki              | 512MB             |
| Grafana           | 512MB             |
| OS + 오버헤드     | ~1,000MB          |
| **합계**          | **~6.7GB / 24GB** |

> 실제 운영 시 JVM 힙, Kafka 페이지 캐시 등으로 추가 메모리가 사용될 수 있으므로 ~8GB 정도로 여유를 잡는다.

## Cloudflare 연동

### DNS 설정

- 도메인의 A 레코드를 OCI VM 공인 IP로 설정
- Proxy 활성화 (주황 구름 아이콘) — VM 실제 IP 은닉

### SSL/TLS

- **모드: Full Strict**
- Cloudflare ↔ 사용자: Cloudflare 인증서 (자동)
- Cloudflare ↔ OCI VM: Origin 인증서 (Cloudflare 콘솔에서 발급, 무료, 최대 15년)
- Nginx에 Origin 인증서 설치

### 비용

Free 플랜으로 충분하다.

- DNS 관리, TLS 자동 발급, DDoS 기본 방어, WAF 기본 규칙
- Origin 인증서 발급, 대역폭 무제한

### OCI 방화벽

- 443 포트: Cloudflare IP 대역만 인바운드 허용
- 9093 포트 (Kafka): 외부 Agent 서버용 오픈 (SASL + TLS 보호)
- 나머지 포트: 외부 차단

## Nginx 구성

단일 Nginx Pod에서 경로 기반으로 라우팅한다. `/api`에서 User JWT와 Provider 토큰을 모두 처리한다.

### 경로 기반 라우팅

| 경로       | 인증 방식                                                         | 업스트림                  |
| ---------- | ----------------------------------------------------------------- | ------------------------- |
| `/`        | 없음 (정적 자산)                                                  | fe-server                 |
| `/api`     | `auth_request` → `/validate` (User JWT / Provider 토큰 자동 판별) | api-service, auth-service |
| `/grafana` | Cloudflare Access 또는 IP 제한                                    | grafana:3000              |

### `auth_request` 흐름

1. 클라이언트 요청 수신
2. Nginx가 `/_auth` 내부 subrequest 발행 → Auth Service로 토큰 검증
3. Auth Service가 `200 OK` + 검증된 헤더 반환
4. Nginx가 **클라이언트 헤더를 초기화한 후** Auth Service 응답 헤더로 덮어씌움
5. 업스트림으로 포워딩

> 인증 흐름 상세는 [인증 문서](../auth/authentication.md) 참고, 보안 상세는 [보안 문서](../shared/security.md) 참고.

## 네트워크 구성

| 포트  | 용도               | 접근 범위       |
| ----- | ------------------ | --------------- |
| 443   | HTTPS (Nginx)      | Cloudflare IP만 |
| 9093  | Kafka (TLS + SASL) | 외부 Agent 서버 |
| 9092  | Kafka (내부, TLS)  | VM1 내부만      |
| 27017 | MongoDB            | VM1 내부만      |
| 6379  | Redis              | VM1 내부만      |
| 3000  | Grafana            | Nginx 경유만    |

> Kafka 포트(9093)는 Cloudflare를 경유하지 않고 직접 연결되므로 OCI VM 실제 IP가 노출된다. 이는 Kafka TCP 프록시의 한계로 인한 트레이드오프이다.
