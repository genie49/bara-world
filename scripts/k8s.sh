#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
K8S_DIR="$PROJECT_ROOT/infra/k8s"
CLUSTER_NAME="bara"

# ── 유틸 ───────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <command>

Commands:
  create    k3d 클러스터 생성 + 이미지 로드 + 매니페스트 적용
  apply     매니페스트만 재적용
  load      로컬 Docker 이미지를 클러스터에 로드
  status    전체 Pod 상태 확인
  stop      클러스터 중지 (상태 유지)
  start     중지된 클러스터 재시작
  delete    k3d 클러스터 완전 삭제

Examples:
  $0 create    # 최초 세팅 (클러스터 생성 → 이미지 로드 → 배포)
  $0 apply     # 매니페스트 수정 후 재적용
  $0 load      # 이미지 리빌드 후 재로드
  $0 stop      # 작업 끝나면 중지
  $0 start     # 다시 시작
  $0 delete    # 전부 삭제
EOF
    exit 1
}

# ── Secret ─────────────────────────────────────────────────
create_secrets() {
    local env_file="$PROJECT_ROOT/.env"
    if [[ ! -f "$env_file" ]]; then
        echo "  ⚠ .env 파일 없음 — Secret 생성 스킵"
        return
    fi

    if kubectl get secret auth-secrets -n core &>/dev/null; then
        echo "  auth-secrets 이미 존재 — 스킵"
        return
    fi

    kubectl create secret generic auth-secrets -n core \
        --from-env-file="$env_file"
    echo "  ✓ auth-secrets 생성 완료"
}

# ── 클러스터 관리 ──────────────────────────────────────────
create_cluster() {
    echo "──── k3d 클러스터 생성 ────"
    k3d cluster create "$CLUSTER_NAME" \
        --port "80:80@loadbalancer" \
        --port "27017:30017@server:0" \
        --port "6379:30379@server:0" \
        --volume "$K8S_DIR/base/gateway/traefik-config.yaml:/var/lib/rancher/k3s/server/manifests/traefik-config.yaml@server:0" \
        --wait

    load_images
    apply_manifests
}

stop_cluster() {
    echo "──── k3d 클러스터 중지 ────"
    k3d cluster stop "$CLUSTER_NAME"
    echo "✓ 클러스터 중지 완료"
}

start_cluster() {
    echo "──── k3d 클러스터 재시작 ────"
    k3d cluster start "$CLUSTER_NAME"
    echo "✓ 클러스터 재시작 완료"
}

delete_cluster() {
    echo "──── k3d 클러스터 삭제 ────"
    k3d cluster delete "$CLUSTER_NAME"
    echo "✓ 클러스터 삭제 완료"
}

# ── 배포 ───────────────────────────────────────────────────
load_images() {
    echo "──── Docker 이미지 로드 ────"
    k3d image import bara/auth:latest bara/fe:latest -c "$CLUSTER_NAME"
    echo "✓ 이미지 로드 완료"

    # deployment가 존재하면 자동 재시작
    if kubectl get deployment auth -n core &>/dev/null; then
        echo "──── Pod 재시작 ────"
        kubectl rollout restart deployment/auth -n core
        kubectl rollout restart deployment/fe -n core
        echo "✓ Pod 재시작 요청 완료"
    fi
}

apply_manifests() {
    echo "──── 매니페스트 적용 ────"
    if ! kubectl cluster-info &>/dev/null; then
        echo "  ⚠ 클러스터에 연결할 수 없음. 먼저 ./scripts/k8s.sh start 실행"
        exit 1
    fi
    echo "  Gateway API CRD 대기 중..."
    until kubectl get crd httproutes.gateway.networking.k8s.io &>/dev/null; do
        sleep 2
    done
    kubectl apply -k "$K8S_DIR/overlays/dev/"
    create_secrets
    echo "✓ 매니페스트 적용 완료"
}

# ── 상태 ───────────────────────────────────────────────────
show_status() {
    echo "──── Pod 상태 ────"
    kubectl get pods -A -o 'custom-columns=NAMESPACE:.metadata.namespace,APP:.metadata.labels.app,STATUS:.status.phase,READY:.status.containerStatuses[0].ready' \
        --field-selector metadata.namespace!=kube-system
}

# ── 메인 ───────────────────────────────────────────────────
[[ $# -lt 1 ]] && usage

case "$1" in
    create) create_cluster ;;
    apply)  apply_manifests ;;
    load)   load_images ;;
    status) show_status ;;
    stop)   stop_cluster ;;
    start)  start_cluster ;;
    delete) delete_cluster ;;
    *)      usage ;;
esac
