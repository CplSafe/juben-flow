#!/usr/bin/env bash
set -euo pipefail
# Update checkout only; never overwrite private runtime configuration or volumes.
cd /data/juben-flow/source
git pull --ff-only origin main
printf 'Source commit: '
git rev-parse HEAD
printf 'Source updated. Build and verify artifacts before replacing /data/juben-flow/frontend or app.jar.\n'
