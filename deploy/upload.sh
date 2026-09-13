#!/usr/bin/env bash

set -euo pipefail
source "$(dirname -- "$0")/lib/common.sh"

require_command rsync
require_command ssh
validate_remote_root

release_id="${1:-$(new_release_id)}"
validate_release_id "$release_id"

web_source="$CHESSTREE_PROJECT_ROOT/composeApp/build/dist/composeWebCompatibility/productionExecutable/"
server_source="$CHESSTREE_PROJECT_ROOT/server/build/install/server/"
test -d "$web_source"
test -x "${server_source}bin/server"

remote_staging="chesstree-upload/$release_id"
ssh -p "$CHESSTREE_DEPLOY_SSH_PORT" "$CHESSTREE_DEPLOY_HOST" \
    "mkdir -p '$remote_staging/web' '$remote_staging/server' '$remote_staging/infra'"
rsync -e "ssh -p $CHESSTREE_DEPLOY_SSH_PORT" -az --delete "$web_source" "$CHESSTREE_DEPLOY_HOST:$remote_staging/web/"
rsync -e "ssh -p $CHESSTREE_DEPLOY_SSH_PORT" -az --delete "$server_source" "$CHESSTREE_DEPLOY_HOST:$remote_staging/server/"
rsync -e "ssh -p $CHESSTREE_DEPLOY_SSH_PORT" -az --delete "$CHESSTREE_DEPLOY_DIR/nginx/" "$CHESSTREE_DEPLOY_HOST:$remote_staging/infra/nginx/"
rsync -e "ssh -p $CHESSTREE_DEPLOY_SSH_PORT" -az --delete "$CHESSTREE_DEPLOY_DIR/systemd/" "$CHESSTREE_DEPLOY_HOST:$remote_staging/infra/systemd/"

printf 'Uploaded release %s to %s via SSH port %s.\n' \
    "$release_id" "$CHESSTREE_DEPLOY_HOST" "$CHESSTREE_DEPLOY_SSH_PORT"
