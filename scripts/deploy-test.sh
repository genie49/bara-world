#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="asia-northeast3-docker.pkg.dev"
REPOSITORY="bara-world/bara"
IMAGE_TAG="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD)"

SERVICES=(
    "auth|apps/auth/Dockerfile"
    "fe|apps/fe/Dockerfile"
)

usage() {
    cat <<EOF
Usage: $0 <command> [service]

CI/CD 파이프라인과 동일한 흐름을 로컬에서 테스트합니다.
  Docker build → Artifact Registry push → (선택) 이미지 pull 검증

Commands:
  push    이미지 빌드 + Artifact Registry push
  verify  push된 이미지를 pull하여 정상 확인
  list    Artifact Registry에 저장된 이미지 목록

Services:
$(for s in "${SERVICES[@]}"; do echo "  ${s%%|*}"; done)

서비스를 지정하지 않으면 전체 서비스를 대상으로 실행합니다.

Examples:
  $0 push            # 전체 빌드 + push
  $0 push auth       # auth만 빌드 + push
  $0 verify          # push된 이미지 pull 검증
  $0 list            # 저장된 이미지 목록
EOF
    exit 1
}

# ── 인증 ───────────────────────────────────────────────────
ensure_auth() {
    if ! gcloud auth configure-docker "$REGISTRY" --quiet 2>/dev/null; then
        echo "Error: gcloud 인증 실패. 'gcloud auth login' 먼저 실행하세요"
        exit 1
    fi
}

# ── 빌드 + Push ───────────────────────────────────────────
push_service() {
    local name="$1"
    local dockerfile="${2}"
    local remote_tag="$REGISTRY/$REPOSITORY/$name"

    echo "──── Build & Push: $name (tag: $IMAGE_TAG) ────"

    docker build \
        -f "$PROJECT_ROOT/$dockerfile" \
        -t "$remote_tag:$IMAGE_TAG" \
        -t "$remote_tag:latest" \
        "$PROJECT_ROOT"

    docker push "$remote_tag:$IMAGE_TAG"
    docker push "$remote_tag:latest"

    echo "✓ $name push 완료 ($IMAGE_TAG + latest)"
}

# ── Pull 검증 ─────────────────────────────────────────────
verify_service() {
    local name="$1"
    local remote_tag="$REGISTRY/$REPOSITORY/$name:$IMAGE_TAG"

    echo "──── Verify: $name ────"
    docker pull "$remote_tag"
    echo "✓ $name pull 성공 ($IMAGE_TAG)"
}

# ── 이미지 목록 ───────────────────────────────────────────
list_images() {
    for entry in "${SERVICES[@]}"; do
        local name="${entry%%|*}"
        echo "──── $name ────"
        gcloud artifacts docker tags list \
            "$REGISTRY/$REPOSITORY/$name" \
            --format="table(tag.basename():label=TAG, version.basename():label=DIGEST)" \
            2>/dev/null || echo "  (이미지 없음)"
        echo ""
    done
}

# ── 서비스 헬퍼 ───────────────────────────────────────────
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
    echo "Error: 알 수 없는 서비스 '$target'"
    exit 1
}

run_for_services() {
    local action="$1"
    local target="${2:-}"

    if [[ -n "$target" ]]; then
        local dockerfile
        dockerfile="$(get_dockerfile "$target")"
        "$action" "$target" "$dockerfile"
    else
        for entry in "${SERVICES[@]}"; do
            local name="${entry%%|*}"
            local dockerfile="${entry#*|}"
            "$action" "$name" "$dockerfile"
        done
    fi
}

# ── 메인 ───────────────────────────────────────────────────
[[ $# -lt 1 ]] && usage

COMMAND="$1"
SERVICE="${2:-}"

case "$COMMAND" in
    push)
        ensure_auth
        echo "Image tag: $IMAGE_TAG"
        run_for_services push_service "$SERVICE"
        echo ""
        echo "══════════════════════════════════════"
        echo "push 완료. 검증하려면: $0 verify"
        echo "══════════════════════════════════════"
        ;;
    verify)
        ensure_auth
        if [[ -n "$SERVICE" ]]; then
            verify_service "$SERVICE"
        else
            for entry in "${SERVICES[@]}"; do
                verify_service "${entry%%|*}"
            done
        fi
        ;;
    list)
        list_images
        ;;
    *)
        usage
        ;;
esac
