#!/usr/bin/env bash

set -euo pipefail

test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM

capture_file="$test_dir/ssh-arguments"
expected_file="$test_dir/expected-arguments"
export CHESSTREE_TEST_CAPTURE="$capture_file"

printf '%s\n' \
    '#!/bin/sh' \
    'printf '\''%s\n'\'' "$@" > "$CHESSTREE_TEST_CAPTURE"' \
    > "$test_dir/ssh"
chmod 755 "$test_dir/ssh"

PATH="$test_dir:$PATH" \
    CHESSTREE_DEPLOY_HOST=elvis@51.250.31.56 \
    CHESSTREE_DEPLOY_SSH_PORT=2222 \
    ./deploy/restart.sh 20260913T125427Z-6beb7a8d0f0e

printf '%s\n' \
    -p \
    2222 \
    elvis@51.250.31.56 \
    sudo \
    -n \
    /usr/local/sbin/chesstree-activate-release \
    20260913T125427Z-6beb7a8d0f0e \
    > "$expected_file"

diff -u "$expected_file" "$capture_file"

grep -F 'readlink -e "$current_link"' deploy/remote/chesstree-activate-release >/dev/null
if grep -F 'readlink -f "$current_link"' deploy/remote/chesstree-activate-release >/dev/null; then
    printf 'The activation helper must not treat a missing current link as a previous release.\n' >&2
    exit 1
fi
