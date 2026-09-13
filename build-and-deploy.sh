#!/bin/zsh

exec "$(dirname "$0")/deploy/deploy.sh" "$@"
