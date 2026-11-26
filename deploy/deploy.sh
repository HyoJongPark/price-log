#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ! $1 =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
    echo "사용법: deploy.sh <이미지@sha256:digest>" >&2
    exit 2
fi

export PRICELOG_IMAGE="$1"
export PRICELOG_DEPLOY_ROOT="${PRICELOG_DEPLOY_ROOT:-$HOME/price-log}"
release_dir=$(cd "$(dirname "$0")" && pwd -P)
compose=(docker compose --env-file "$PRICELOG_DEPLOY_ROOT/.env" -f "$release_dir/compose.yaml")

# pull에 실패하면 현재 실행 중인 컨테이너를 건드리지 않는다.
"${compose[@]}" pull backend
if ! "${compose[@]}" up -d --no-deps --pull never --wait --wait-timeout 120 backend; then
    echo "앱 기동 실패. 회차와 로그를 확인한 후 호환되는 릴리스로 복구하세요." >&2
    exit 1
fi

# 같은 릴리스로 재시도할 때도 직전 정상 버전을 보존한다.
current="$PRICELOG_DEPLOY_ROOT/current"
if [[ -L "$current" && $(readlink "$current") != "$release_dir" ]]; then
    ln -sfn "$(readlink "$current")" "$PRICELOG_DEPLOY_ROOT/previous"
fi
printf '%s\n' "$PRICELOG_IMAGE" > "$release_dir/image"
ln -sfn "$release_dir" "$current"
echo "배포 완료: $PRICELOG_IMAGE"
