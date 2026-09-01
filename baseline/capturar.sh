#!/usr/bin/env bash
# Captura artefactos de los variants de terminal para comparar antes/después.
# Uso: ./baseline/capturar.sh <etiqueta>
set -euo pipefail
ETIQUETA="${1:?falta la etiqueta, p.ej: antes}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/baseline/artefactos/$ETIQUETA"
mkdir -p "$OUT"
cd "$REPO"

# Bash 3.2 de macOS no tiene ${V^}: la capitalización va explícita.
for PAR in "production:Production" "sandbox:Sandbox" "nexgo:Nexgo" "nexgoProd:NexgoProd"; do
  V="${PAR%%:*}"; C="${PAR##*:}"
  echo "== $V =="

  # 1) Grafo de dependencias de RELEASE (la condición del founder habla de los releases)
  ./gradlew -q ":app:dependencies" --configuration "${V}ReleaseRuntimeClasspath" > "$OUT/deps-$V.txt"

  # 2) Manifiesto fusionado y 3) BuildConfig — tareas distintas, rutas explícitas
  ./gradlew -q ":app:process${C}ReleaseManifest" ":app:generate${C}ReleaseBuildConfig" >/dev/null
  cp "app/build/intermediates/merged_manifests/${V}Release/process${C}ReleaseManifest/AndroidManifest.xml" \
     "$OUT/manifest-$V.xml"
  cp "app/build/generated/source/buildConfig/$V/release/com/jaac/avoqado_tpv/BuildConfig.java" \
     "$OUT/buildconfig-$V.java"
done
echo "capturado en $OUT"
