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

    source "$env_file"
    kubectl create secret generic auth-secrets -n core \
        --from-literal=jwt-private-key="$BARA_AUTH_JWT_PRIVATE_KEY" \
        --from-literal=jwt-public-key="$BARA_AUTH_JWT_PUBLIC_KEY" \
        --from-literal=google-client-id="$BARA_AUTH_GOOGLE_CLIENT_ID" \
        --from-literal=google-client-secret="$BARA_AUTH_GOOGLE_CLIENT_SECRET"
    echo "  ✓ auth-secrets 생성 완료"
}

# ── 클러스터 관리 ──────────────────────────────────────────
create_cluster() {
    echo "──── k3d 클러스터 생성 ────"
    k3d cluster create "$CLUSTER_NAME" \
        --port "80:30080@server:0" \
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
}

apply_manifests() {
    echo "──── 매니페스트 적용 ────"
    if ! kubectl cluster-info &>/dev/null; then
        echo "  ⚠ 클러스터에 연결할 수 없음. 먼저 ./scripts/k8s.sh start 실행"
        exit 1
    fi
    kubectl apply -f "$K8S_DIR/namespaces.yaml"
    kubectl apply -f "$K8S_DIR/data/"
    create_secrets
    kubectl apply -f "$K8S_DIR/core/"

    kubectl create configmap nginx-config \
        --from-file=default.conf="$K8S_DIR/gateway/nginx.conf" \
        -n gateway --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -f "$K8S_DIR/gateway/nginx.yaml"
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
