#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/smoke-test.sh [jwt]
# Requires: k3d cluster running (./scripts/k8s.sh create)

JWT="${1:-}"
BASE="http://localhost"
PASS=0
FAIL=0

green() { printf "\033[32m✓ %s\033[0m\n" "$1"; }
red()   { printf "\033[31m✗ %s\033[0m\n" "$1"; }

check() {
    local name="$1" url="$2" expected="$3"
    shift 3
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "$@" "$url")
    if [ "$code" = "$expected" ]; then
        green "$name (HTTP $code)"
        PASS=$((PASS + 1))
    else
        red "$name (expected $expected, got $code)"
        FAIL=$((FAIL + 1))
    fi
}

check_header() {
    local name="$1" url="$2" header="$3"
    shift 3
    local headers
    headers=$(curl -s -D- -o /dev/null "$@" "$url")
    if echo "$headers" | grep -qi "$header"; then
        green "$name (header $header found)"
        PASS=$((PASS + 1))
    else
        red "$name (header $header not found)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Gateway Smoke Test ==="
echo ""

# 1. FE static files
check "FE static files" "$BASE/" "200"

# 2. Public endpoint (Google login redirect)
check "Public: /api/auth/google/login" "$BASE/api/auth/google/login" "302"

# 3. Protected endpoint without auth
check "Protected without auth: /api/auth/provider" "$BASE/api/auth/provider" "401"

# 4. Protected endpoint with valid JWT (if provided)
if [ -n "$JWT" ]; then
    check "Protected with JWT: /api/auth/provider" "$BASE/api/auth/provider" "200" \
        -H "Authorization: Bearer $JWT"
else
    echo "  ⏭  Skipping JWT test (no token provided)"
fi

# 5. CORS preflight
check_header "CORS preflight" "$BASE/api/auth/validate" "Access-Control-Allow-Origin" \
    -X OPTIONS \
    -H "Origin: http://localhost" \
    -H "Access-Control-Request-Method: GET"

# 6. Health check
check "Health check" "$BASE/api/auth/actuator/health" "200"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
exit $FAIL
