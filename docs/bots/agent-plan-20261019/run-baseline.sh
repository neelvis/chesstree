#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$project_root"

# Force the measurement task to execute: Gradle does not track probe environment
# variables as test inputs. No production difficulty setting is changed.
export CHESSTREE_BOT_QUALITY_PROBE=true
export CHESSTREE_BOT_QUALITY_WORK="${CHESSTREE_BOT_QUALITY_WORK:-2000}"
./gradlew :botWire:jvmTest --tests '*ReportedBotInitialStateTest' \
    :gameDomain:jvmTest --rerun \
    --tests '*ReportedBotFixturesTest' --tests '*BotQuality*Test' \
    --tests '*BotSearchDiagnosticsTest' --console=plain
