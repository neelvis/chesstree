#!/usr/bin/env bash

set -euo pipefail
source "$(dirname -- "$0")/lib/common.sh"

cd "$CHESSTREE_PROJECT_ROOT"
./gradlew \
    :composeApp:composeCompatibilityBrowserDistribution \
    :server:check \
    :server:installDist

test -d "$CHESSTREE_PROJECT_ROOT/composeApp/build/dist/composeWebCompatibility/productionExecutable"
test -x "$CHESSTREE_PROJECT_ROOT/server/build/install/server/bin/server"

printf 'Build artifacts are ready.\n'
