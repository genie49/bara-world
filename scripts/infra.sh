#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA_DIR="$PROJECT_ROOT/infra"

usage() {
    echo "Usage: $0 <up|down> <dev|test>"
    echo ""
    echo "Commands:"
    echo "  up    Start containers"
    echo "  down  Stop and remove containers"
    echo ""
    echo "Environments:"
    echo "  dev   Infrastructure only (MongoDB, Redis, Kafka)"
    echo "  test  Infrastructure + services (Auth, ...)"
    exit 1
}

[[ $# -ne 2 ]] && usage

ACTION="$1"
ENV="$2"

case "$ENV" in
    dev)  COMPOSE_FILE="$INFRA_DIR/docker-compose.dev.yml" ;;
    test) COMPOSE_FILE="$INFRA_DIR/docker-compose.test.yml" ;;
    *)    usage ;;
esac

case "$ACTION" in
    up)   docker compose -f "$COMPOSE_FILE" up -d ;;
    down) docker compose -f "$COMPOSE_FILE" down ;;
    *)    usage ;;
esac
