#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA_DIR="$PROJECT_ROOT/infra"
COMPOSE_FILE="$INFRA_DIR/docker-compose.dev.yml"

usage() {
    cat <<EOF
Usage: $0 <up|down>

로컬 개발용 인프라 (MongoDB, Redis, Kafka) 관리.
K8s 환경은 ./scripts/k8s.sh 사용.

Commands:
  up    인프라 컨테이너 시작
  down  인프라 컨테이너 중지/삭제
EOF
    exit 1
}

[[ $# -ne 1 ]] && usage

ENV_FILE="$PROJECT_ROOT/.env"
COMPOSE_CMD="docker compose -f $COMPOSE_FILE"
[[ -f "$ENV_FILE" ]] && COMPOSE_CMD="$COMPOSE_CMD --env-file $ENV_FILE"

case "$1" in
    up)   $COMPOSE_CMD up -d ;;
    down) $COMPOSE_CMD down ;;
    *)    usage ;;
esac
