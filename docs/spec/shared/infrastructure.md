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
| `gateway` | Traefik (K3s 내장, Gateway API CRD)                                       |
| `core`    | auth-service, api-service, fe-server, telegram-service, scheduler-service |
| `data`    | mongodb, redis, kafka                                                     |
| `logging` | fluent-bit (DaemonSet), loki, grafana                                     |

### 배포 단위

| 유형        | 대상                                                                          | 이유                       |
| ----------- | ----------------------------------------------------------------------------- | -------------------------- |
| Deployment  | auth-service, api-service, fe-server, telegram-service, scheduler-service     | 무상태, replicas 조절 가능 |
| StatefulSet | mongodb, redis, kafka                                                         | 데이터 영속성 필요         |
| DaemonSet   | fluent-bit                                                                    | 노드당 자동 1개            |
| Gateway     | K8s Gateway API (`gateway.yaml`)                                              | Traefik이 구현체           |
| HTTPRoute   | 경로 라우팅 규칙 (`routes.yaml`)                                              | `/auth/**`, `/**` 분기     |
| Secret      | `auth-secrets`(core), `data-secrets`(data): JWT 키, OAuth, DB credential      |                            |

초기에는 모든 Deployment replicas를 1로 시작한다. 부하 발생 시 auth-service, api-service만 우선 스케일링.

## 리소스 예산

| 서비스            | 예상 메모리       |
| ----------------- | ----------------- |
| K3s 자체 + Traefik | 512MB            |
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
| **합계**          | **~6.5GB / 24GB** |

> 실제 운영 시 JVM 힙, Kafka 페이지 캐시 등으로 추가 메모리가 사용될 수 있으므로 ~8GB 정도로 여유를 잡는다.

## Cloudflare 연동

### DNS 설정

- 도메인의 A 레코드를 OCI VM 공인 IP로 설정
- Proxy 활성화 (주황 구름 아이콘) — VM 실제 IP 은닉

### SSL/TLS

- **모드: Full Strict**
- Cloudflare ↔ 사용자: Cloudflare 인증서 (자동)
- Cloudflare ↔ OCI VM: Origin 인증서 (Cloudflare 콘솔에서 발급, 무료, 최대 15년)
- Traefik에 Origin 인증서 설치

### 비용

Free 플랜으로 충분하다.

- DNS 관리, TLS 자동 발급, DDoS 기본 방어, WAF 기본 규칙
- Origin 인증서 발급, 대역폭 무제한

### OCI 방화벽

- 443 포트: Cloudflare IP 대역만 인바운드 허용
- 9093 포트 (Kafka): 외부 Agent 서버용 오픈 (SASL + TLS 보호)
- 나머지 포트: 외부 차단

## Traefik + Gateway API 구성

K3s에 내장된 Traefik이 게이트웨이 역할을 한다. 별도 Nginx Pod 없이 K8s Gateway API 리소스로 라우팅 규칙을 선언한다.

### 경로 기반 라우팅

| 경로                   | 매칭 방식   | 업스트림       |
| ---------------------- | ----------- | -------------- |
| `/auth/callback`       | Exact       | fe-server:5173 |
| `/auth/**`             | PathPrefix  | auth:8081      |
| `/**`                  | PathPrefix  | fe-server:5173 |

라우팅 규칙은 `infra/k8s/base/gateway/routes.yaml`(HTTPRoute)에 정의되며, `infra/k8s/base/gateway/gateway.yaml`(Gateway 리소스)이 Traefik을 진입점으로 선언한다.

### 포트 바인딩

ServiceLB(Klipper)가 LoadBalancer 타입 서비스를 처리하여 `localhost:80`에 직접 바인딩된다. (NodePort 불필요)

> 인증 흐름 상세는 [인증 문서](../auth/authentication.md) 참고, 보안 상세는 [보안 문서](../shared/security.md) 참고.

## 네트워크 구성

| 포트  | 용도               | 접근 범위       | 인증              |
| ----- | ------------------ | --------------- | ----------------- |
| 443   | HTTPS (Traefik)    | Cloudflare IP만 | TLS               |
| 9093  | Kafka (TLS + SASL) | 외부 Agent 서버 | SASL OAUTHBEARER  |
| 9092  | Kafka (내부, TLS)  | VM1 내부만      | TLS               |
| 30017 | MongoDB (NodePort) | 외부 접속 가능  | root 계정 인증    |
| 30379 | Redis (NodePort)   | 외부 접속 가능  | requirepass       |
| 3000  | Grafana            | Nginx 경유만    | -                 |

> Kafka 포트(9093)는 Cloudflare를 경유하지 않고 직접 연결되므로 OCI VM 실제 IP가 노출된다. 이는 Kafka TCP 프록시의 한계로 인한 트레이드오프이다.
