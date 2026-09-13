#!/usr/bin/env bash

set -euo pipefail

CHESSTREE_DEPLOY_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHESSTREE_PROJECT_ROOT="$(CDPATH= cd -- "$CHESSTREE_DEPLOY_DIR/.." && pwd)"
CHESSTREE_DEPLOY_HOST="${CHESSTREE_DEPLOY_HOST:-root@51.250.31.56}"
CHESSTREE_REMOTE_ROOT="/opt/chesstree"

require_command() {
    command -v "$1" >/dev/null 2>&1 || {
        printf 'Required command is missing: %s\n' "$1" >&2
        exit 1
    }
}

validate_remote_root() {
    case "$CHESSTREE_REMOTE_ROOT" in
        /|""|*[!A-Za-z0-9_./-]*)
            printf 'Unsafe CHESSTREE_REMOTE_ROOT: %s\n' "$CHESSTREE_REMOTE_ROOT" >&2
            exit 1
            ;;
    esac
}

new_release_id() {
    local revision
    revision="$(git -C "$CHESSTREE_PROJECT_ROOT" rev-parse --short=12 HEAD)"
    printf '%s-%s\n' "$(date -u +%Y%m%dT%H%M%SZ)" "$revision"
}

validate_release_id() {
    if [[ ! "$1" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{7,40}$ ]]; then
        printf 'Invalid release id: %s\n' "$1" >&2
        exit 1
    fi
}
