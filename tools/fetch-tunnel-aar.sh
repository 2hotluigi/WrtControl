#!/bin/sh
# Trae a app/libs el .aar del túnel que compiló la CI (trabajo tunnel-aar) para la rama actual.
# Para cuando la CI dice que el del repositorio no coincide: así el que se sube es el suyo.
# Necesita gh (GitHub CLI) con sesión iniciada.
#
#   sh tools/fetch-tunnel-aar.sh            # el último run de CI de esta rama
#   sh tools/fetch-tunnel-aar.sh <run-id>
set -e
cd "$(dirname "$0")/.."

run=${1:-$(gh run list --branch "$(git branch --show-current)" --workflow ci.yml --limit 1 --json databaseId -q '.[0].databaseId')}
[ -n "$run" ] || { echo "No hay runs de CI para esta rama" >&2; exit 1; }
tmp=$(mktemp -d)
gh run download "$run" --name wrtcvpn-aar --dir "$tmp"
cp "$tmp/wrtcvpn.aar" "$tmp/wrtcvpn-sources.jar" app/libs/
rm -rf "$tmp"
echo "app/libs actualizado con el .aar del run $run. Revísalo con git status y súbelo."
