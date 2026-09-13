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

ssh "$CHESSTREE_DEPLOY_HOST" bash -s -- "$release_id" "$CHESSTREE_REMOTE_ROOT" <<'REMOTE_SCRIPT'
set -euo pipefail

release_id="$1"
remote_root="$2"
staging="$HOME/chesstree-upload/$release_id"
release_dir="$remote_root/releases/$release_id"
current_link="$remote_root/current"

test -d "$staging/web"
test -x "$staging/server/bin/server"

sudo install -d -m 755 "$remote_root/releases" "$release_dir/web" "$release_dir/server"
sudo rsync -a --delete "$staging/web/" "$release_dir/web/"
sudo rsync -a --delete "$staging/server/" "$release_dir/server/"
sudo chown -R root:root "$release_dir"

previous_release="$(readlink -f "$current_link" 2>/dev/null || true)"
sudo ln -sfn "$release_dir" "$remote_root/current.next"
sudo mv -Tf "$remote_root/current.next" "$current_link"

rollback() {
    if [[ -n "$previous_release" && -d "$previous_release" ]]; then
        sudo ln -sfn "$previous_release" "$remote_root/current.rollback"
        sudo mv -Tf "$remote_root/current.rollback" "$current_link"
        sudo systemctl restart chesstree-server || true
    else
        sudo systemctl stop chesstree-server || true
    fi
}

if ! sudo nginx -t; then
    rollback
    exit 1
fi
if ! sudo systemctl restart chesstree-server; then
    rollback
    exit 1
fi

healthy=false
for _ in {1..20}; do
    if curl --fail --silent --show-error http://127.0.0.1:8081/health >/dev/null; then
        healthy=true
        break
    fi
    sleep 1
done

if [[ "$healthy" != true ]]; then
    sudo journalctl -u chesstree-server -n 100 --no-pager || true
    rollback
    exit 1
fi

sudo systemctl reload nginx
printf 'Activated release %s.\n' "$release_id"
REMOTE_SCRIPT
