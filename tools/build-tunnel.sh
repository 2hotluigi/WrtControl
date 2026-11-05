#!/bin/sh
# Compila tunnel/ (wireguard-go con netstack: el acceso remoto dentro de la app) como librería de
# Android en app/libs/wrtcvpn.aar. Solo hace falta al cambiar tunnel/: el .aar va en el repositorio
# para que la app compile sin Go.
#
# Necesita Go, gomobile y el NDK de Android:
#   go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
#   (NDK: Android Studio → SDK Manager → SDK Tools → NDK)
#
#   sh tools/build-tunnel.sh
set -e
cd "$(dirname "$0")/../tunnel"

: "${ANDROID_HOME:=${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
: "${ANDROID_NDK_HOME:=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1)}"
export ANDROID_HOME ANDROID_NDK_HOME
[ -d "$ANDROID_NDK_HOME" ] || { echo "No encuentro el NDK en $ANDROID_HOME/ndk" >&2; exit 1; }
echo "NDK: $ANDROID_NDK_HOME"

go test ./...
mkdir -p ../app/libs
# arm64 y arm para teléfonos; amd64 para el emulador. API 26: la mínima de la app.
gomobile bind \
	-target=android/arm64,android/arm,android/amd64 \
	-androidapi 26 \
	-javapkg dev.angel.wrtcontrol.go \
	-trimpath -ldflags="-s -w" \
	-o ../app/libs/wrtcvpn.aar .
ls -l ../app/libs/wrtcvpn.aar
