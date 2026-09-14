#!/usr/bin/env bash

set -euo pipefail

for entrypoint in \
    composeApp/src/jsMain/resources/index.html \
    composeApp/src/wasmJsMain/resources/index.html
do
    grep -F '<base href="/">' "$entrypoint" >/dev/null
    grep -F '<script src="/composeApp.js"></script>' "$entrypoint" >/dev/null
    grep -F '<link href="/styles.css" rel="stylesheet">' "$entrypoint" >/dev/null

    if grep -Eq '(src|href)="(composeApp\.js|styles\.css)"' "$entrypoint"; then
        printf 'Web entrypoint uses a route-relative application asset: %s\n' "$entrypoint" >&2
        exit 1
    fi
done
