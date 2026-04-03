# ADR-010: GKE/Railway 대신 OCI 무료 티어 VM 선택

- **상태**: 채택
- **일자**: 2026-04-03

## 맥락

인프라 비용을 최소화하면서 전체 스택을 운영할 수 있는 환경을 선택해야 했다.

## 선택지

1. **GKE (Google Kubernetes Engine)**: 매니지드 K8s. 노드 관리 불필요 (Autopilot). 비용 발생.
2. **Railway**: PaaS. 컨테이너 배포만. VM 접근 불가. Nginx 커스텀 설정, VPC 격리 제한적.
3. **OCI 무료 티어 VM + K3s**: ARM64 VM (4 OCPU / 24GB). 무료. 완전한 제어권.

## 결정

**OCI 무료 티어 VM + K3s**를 채택한다.

## 이유

- GKE Autopilot은 운영 부담이 적지만 비용이 발생
- Railway는 Nginx `auth_request`, VPC 격리, Fluent Bit 사이드카 등 현재 설계에 필요한 기능이 제한적
- OCI 무료 티어는 ARM64 4 OCPU / 24GB RAM으로 충분한 스펙을 무료로 제공
- K3s는 경량 K8s로 단일 노드에서도 잘 동작하며, 전체 스택을 올리기에 적합
- 전체 예상 메모리 사용량(~8GB)이 24GB 내에 여유 있게 수용

## 결과

- VM 직접 운영 부담 (K3s 업그레이드, 장애 대응)
- ARM64 아키텍처이므로 모든 Docker 이미지 빌드 시 `--platform linux/arm64` 필요
- 단일 VM이므로 VM 장애 시 전체 서비스 다운 (초기 감수, 이후 이중화 고려)
- Kafka 외부 포트 노출로 OCI VM 실제 IP 노출 (Cloudflare TCP 프록시 불가)
