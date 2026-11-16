#!/bin/sh
# Compila tunnel/ (wireguard-go con netstack: el acceso remoto dentro de la app) como librería de
# Android en app/libs/wrtcvpn.aar. Solo hace falta al cambiar tunnel/: el .aar va en el repositorio
# para que la app compile sin Go.
#
# La CI (trabajo tunnel-aar) lo compila igual y falla si no coincide con el del repositorio. Por
# eso todo va fijado: Go (go.mod), gomobile y gobind (la versión de golang.org/x/mobile de
# go.mod), el NDK ($NDK_VERSION) y el JDK (25, el de la CI).
#
# Necesita Go y el NDK de Android (Android Studio → SDK Manager → SDK Tools → NDK, "Show Package
# Details" para elegir la versión). gomobile y gobind los instala el script.
#
#   sh tools/build-tunnel.sh              # deja el .aar en app/libs
#   WRTC_AAR_OUT=/tmp/x.aar sh tools/build-tunnel.sh
#
# Si la CI dice que el .aar no coincide, lo más fácil es usar el que compiló ella:
#   sh tools/fetch-tunnel-aar.sh
set -e
cd "$(dirname "$0")/../tunnel"

NDK_VERSION=30.0.16248370
OUT=${WRTC_AAR_OUT:-../app/libs/wrtcvpn.aar}

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}}"
# El NDK fijado manda sobre un ANDROID_NDK_HOME que ya venga puesto (los runners de GitHub traen otro).
if [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
	ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$NDK_VERSION
fi
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/$NDK_VERSION}"
export ANDROID_HOME ANDROID_NDK_HOME
[ -d "$ANDROID_NDK_HOME" ] || { echo "No encuentro el NDK $NDK_VERSION en $ANDROID_HOME/ndk" >&2; exit 1; }
case "$ANDROID_NDK_HOME" in
	*"$NDK_VERSION"*) ;;
	*) echo "Aviso: NDK distinto de $NDK_VERSION ($ANDROID_NDK_HOME): el .aar no coincidirá con el de la CI" >&2 ;;
esac
echo "NDK: $ANDROID_NDK_HOME"

# gomobile y gobind de la versión de go.mod, en una carpeta propia (no los de PATH).
TOOLS=$(go env GOPATH)/wrtc-gomobile
GOBIN=$TOOLS go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
PATH="$TOOLS:$PATH"
export PATH
go version
javac -version

go test ./...
mkdir -p "$(dirname "$OUT")"
# arm64 y arm para teléfonos; amd64 para el emulador. API 26: la mínima de la app.
gomobile bind \
	-target=android/arm64,android/arm,android/amd64 \
	-androidapi 26 \
	-javapkg dev.angel.wrtcontrol.go \
	-trimpath -ldflags="-s -w -buildid=" \
	-o "$OUT" .
ls -l "$OUT"
