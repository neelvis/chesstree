#!/usr/bin/env bash

set -euo pipefail
source "$(dirname -- "$0")/lib/common.sh"

require_command ssh
validate_remote_root

if [[ $# -ne 1 ]]; then
    printf 'Usage: %s <release-id>\n' "$0" >&2
    exit 1
fi

release_id="$1"
validate_release_id "$release_id"

ssh -p "$CHESSTREE_DEPLOY_SSH_PORT" "$CHESSTREE_DEPLOY_HOST" \
    sudo -n /usr/local/sbin/chesstree-activate-release "$release_id"
