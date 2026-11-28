#!/usr/bin/env bash
set -euo pipefail

# Keep the Nova entry point explicit while both launcher gates share the same
# fixture builder, candidate installation and 284-shortcut assertions.
exec bash "$(dirname "$0")/test-large-shortcuts.sh" nova
