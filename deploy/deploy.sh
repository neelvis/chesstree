#!/usr/bin/env bash

set -euo pipefail
source "$(dirname -- "$0")/lib/common.sh"

release_id="$(new_release_id)"

"$CHESSTREE_DEPLOY_DIR/build.sh"
"$CHESSTREE_DEPLOY_DIR/upload.sh" "$release_id"
"$CHESSTREE_DEPLOY_DIR/restart.sh" "$release_id"

printf 'Deployment %s completed on %s via SSH port %s.\n' \
    "$release_id" "$CHESSTREE_DEPLOY_HOST" "$CHESSTREE_DEPLOY_SSH_PORT"
