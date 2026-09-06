#!/bin/bash
# Finder opens this launcher in Terminal. Keep the result visible on failure too.
DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
if [[ $# == 0 ]]; then set -- setup; fi
/bin/bash "$DIR/pepper.sh" "$@"
RESULT=$?
if [[ -t 0 ]]; then read -r -p 'Appuyez sur Entrée pour fermer… ' _; fi
exit "$RESULT"
