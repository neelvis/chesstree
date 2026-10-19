#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$project_root"
export CHESSTREE_BOT_STAGE1_WORKER_FIXTURES=true
./gradlew :botWire:jvmTest --rerun --tests '*BotStage1WorkerFixtureTest' \
    :botWorker:jsBrowserProductionWebpack --console=plain
export CHESSTREE_BOT_SMOKE_DIR="$project_root/work/bot-improvement-stage1-20261019/browser-smoke"
mkdir -p "$CHESSTREE_BOT_SMOKE_DIR"
cp botWire/build/reports/bot-quality/stage1-worker-fixtures/*.json "$CHESSTREE_BOT_SMOKE_DIR/"
cp botWorker/build/kotlin-webpack/js/productionExecutable/botWorker.js "$CHESSTREE_BOT_SMOKE_DIR/"
"${CHESSTREE_NODE_BIN:-node}" build/js/node_modules/karma/bin/karma start \
    docs/bots/agent-plan-20261019/stage1/worker-smoke/karma.conf.cjs
