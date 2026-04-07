#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env.prod"
PARAM_NAME="bara-env-prod"
LOCATION="global"

usage() {
    cat <<EOF
Usage: $0 <command>

Commands:
  push      .env.prod → GCP Parameter Manager 업로드
  pull      GCP Parameter Manager → .env.prod 다운로드
  list      저장된 버전 목록 확인

Examples:
  $0 push      # .env.prod를 GCP에 업로드
  $0 pull      # GCP에서 .env.prod 다운로드
  $0 list      # 버전 목록 확인
EOF
    exit 1
}

ensure_parameter() {
    if ! gcloud parametermanager parameters describe "$PARAM_NAME" \
        --location="$LOCATION" &>/dev/null; then
        echo "  파라미터 생성 중..."
        gcloud parametermanager parameters create "$PARAM_NAME" \
            --location="$LOCATION"
    fi
}

push_secrets() {
    if [[ ! -f "$ENV_FILE" ]]; then
        echo "Error: .env.prod 파일이 없습니다"
        exit 1
    fi

    echo "──── .env.prod → GCP Parameter Manager ────"
    ensure_parameter

    local version="v$(date +%Y%m%d-%H%M%S)"
    gcloud parametermanager parameters versions create "$version" \
        --parameter="$PARAM_NAME" \
        --location="$LOCATION" \
        --payload-data-from-file="$ENV_FILE"
    echo "✓ 업로드 완료 ($version)"
}

pull_secrets() {
    echo "──── GCP Parameter Manager → .env.prod ────"

    local latest
    latest=$(gcloud parametermanager parameters versions list \
        --parameter="$PARAM_NAME" \
        --location="$LOCATION" \
        --sort-by="createTime" \
        --format="value(name)" 2>/dev/null | tail -1)

    if [[ -z "$latest" ]]; then
        echo "Error: 저장된 버전이 없습니다. 먼저 push를 실행하세요"
        exit 1
    fi

    local version_id
    version_id=$(basename "$latest")

    gcloud parametermanager parameters versions describe "$version_id" \
        --parameter="$PARAM_NAME" \
        --location="$LOCATION" \
        --format="value(payload.data)" | base64 -d > "$ENV_FILE"
    echo "✓ .env.prod 다운로드 완료 ($version_id)"
}

list_secrets() {
    echo "──── .env.prod 키 목록 ────"
    if [[ -f "$ENV_FILE" ]]; then
        grep -v '^#' "$ENV_FILE" | grep -v '^$' | cut -d= -f1
    else
        echo "  (.env.prod 없음 — pull 먼저 실행)"
    fi

    echo ""
    echo "──── Parameter Manager 버전 목록 ────"
    gcloud parametermanager parameters versions list \
        --parameter="$PARAM_NAME" \
        --location="$LOCATION" \
        --format="table(name.basename(), createTime, disabled)"
}

[[ $# -lt 1 ]] && usage

case "$1" in
    push) push_secrets ;;
    pull) pull_secrets ;;
    list) list_secrets ;;
    *)    usage ;;
esac
