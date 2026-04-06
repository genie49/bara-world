#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE_PREFIX="bara"

# ── 서비스 레지스트리 ──────────────────────────────────────
# 형식: "name|dockerfile_path"
SERVICES=(
    "auth|apps/auth/Dockerfile"
    "fe|apps/fe/Dockerfile"
)

# ── 유틸 ───────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: $0 <command> [service]

Commands:
  build   Docker 이미지 빌드 (multi-stage: 소스 빌드 포함)
  clean   Docker 이미지 삭제 및 빌드 캐시 정리

Services:
$(for s in "${SERVICES[@]}"; do echo "  ${s%%|*}"; done)

서비스를 지정하지 않으면 전체 서비스를 대상으로 실행합니다.

Examples:
  $0 build           # 전체 빌드
  $0 build auth      # auth 서비스만 빌드
  $0 clean fe        # fe 이미지 삭제
  $0 clean           # 전체 이미지 삭제
EOF
    exit 1
}

get_dockerfile() {
    local target="$1"
    for entry in "${SERVICES[@]}"; do
        local name="${entry%%|*}"
        local dockerfile="${entry#*|}"
        if [[ "$name" == "$target" ]]; then
            echo "$dockerfile"
            return 0
        fi
    done
    return 1
}

list_service_names() {
    for entry in "${SERVICES[@]}"; do
        echo "${entry%%|*}"
    done
}

# ── 빌드 ───────────────────────────────────────────────────
build_service() {
    local name="$1"
    local dockerfile
    dockerfile="$(get_dockerfile "$name")" || { echo "Error: 알 수 없는 서비스 '$name'"; exit 1; }

    local tag="${IMAGE_PREFIX}/${name}:latest"
    echo "──── Building ${tag} ────"
    docker build \
        -f "${PROJECT_ROOT}/${dockerfile}" \
        -t "$tag" \
        "$PROJECT_ROOT"
    echo "✓ ${tag} 빌드 완료"
}

# ── 클린 ───────────────────────────────────────────────────
clean_service() {
    local name="$1"
    local tag="${IMAGE_PREFIX}/${name}:latest"
    echo "──── Cleaning ${tag} ────"

    if docker image inspect "$tag" &>/dev/null; then
        docker rmi "$tag"
        echo "✓ ${tag} 삭제 완료"
    else
        echo "  (이미지 없음, 스킵)"
    fi
}

# ── 메인 ───────────────────────────────────────────────────
[[ $# -lt 1 ]] && usage

COMMAND="$1"
SERVICE="${2:-}"

case "$COMMAND" in
    build)
        if [[ -n "$SERVICE" ]]; then
            build_service "$SERVICE"
        else
            for name in $(list_service_names); do
                build_service "$name"
            done
        fi
        ;;
    clean)
        if [[ -n "$SERVICE" ]]; then
            clean_service "$SERVICE"
        else
            for name in $(list_service_names); do
                clean_service "$name"
            done
            echo "──── Pruning dangling images ────"
            docker image prune -f
        fi
        ;;
    *)
        usage
        ;;
esac
