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
ssh "$CHESSTREE_DEPLOY_HOST" \
    "mkdir -p '$remote_staging/web' '$remote_staging/server' '$remote_staging/infra'"
rsync -az --delete "$web_source" "$CHESSTREE_DEPLOY_HOST:$remote_staging/web/"
rsync -az --delete "$server_source" "$CHESSTREE_DEPLOY_HOST:$remote_staging/server/"
rsync -az --delete "$CHESSTREE_DEPLOY_DIR/nginx/" "$CHESSTREE_DEPLOY_HOST:$remote_staging/infra/nginx/"
rsync -az --delete "$CHESSTREE_DEPLOY_DIR/systemd/" "$CHESSTREE_DEPLOY_HOST:$remote_staging/infra/systemd/"

printf 'Uploaded release %s to %s.\n' "$release_id" "$CHESSTREE_DEPLOY_HOST"
