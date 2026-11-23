#!/bin/sh
# Never source the private file or expand credentials in the shell.
set +x
set -eu
exec python3 "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/sqlcl.py" "$@"
