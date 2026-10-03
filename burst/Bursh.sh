#!/usr/bin/env bash
# One-command on-sale stampede against a seat-reservation deployment.
# Usage: ./burst/burst.sh https://your-app.up.railway.app [extra args, e.g. --concurrency 300]
set -euo pipefail
cd "$(dirname "$0")"
python3 -m pip install -q -r requirements.txt
python3 burst.py "$@"