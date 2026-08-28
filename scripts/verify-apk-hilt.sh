#!/usr/bin/env bash
#
# verify-apk-hilt.sh — comprueba que el grafo de Hilt REALMENTE quedó dentro del APK.
#
# Por qué existe
# --------------
# Hilt genera `Hilt_AvoqadoTPVApplication`, `AvoqadoTPVApplication_GeneratedInjector` y
# `DaggerAvoqadoTPVApplication_HiltComponents_SingletonC` con KSP. Un build incremental
# sucio (KSP/javac/dex a medias tras un build interrumpido, dos builds en paralelo, o la
# maquina quedandose sin memoria) puede empaquetar SOLO UNA PARTE de esas clases: el
# build sale VERDE y el APK crashea al arrancar con
#
#   java.lang.NoClassDefFoundError: Failed resolution of:
#     Lcom/jaac/avoqado_tpv/AvoqadoTPVApplication_GeneratedInjector;
#     at com.jaac.avoqado_tpv.Hilt_AvoqadoTPVApplication.<init>
#
# Esto lo convierte en un fallo ANTES de instalar en la terminal.
#
# Uso
# ---
#   ./scripts/verify-apk-hilt.sh                      # sandboxDebug (default)
#   ./scripts/verify-apk-hilt.sh <ruta-al-apk>
#   ./scripts/verify-apk-hilt.sh --variant nexgoDebug
#
# Salida: 0 = APK sano · 1 = faltan clases (NO instalar) · 2 = error de uso
#
# Nota: en builds `release` R8 ofusca los nombres generados, asi que este check solo
# aplica a APKs debug. Con un APK release el script avisa y sale 0.

set -euo pipefail

# Descriptores de tipo tal y como viven en el string pool del .dex.
REQUIRED_SYMBOLS=(
  "Lcom/jaac/avoqado_tpv/AvoqadoTPVApplication_GeneratedInjector;"
  "Lcom/jaac/avoqado_tpv/Hilt_AvoqadoTPVApplication;"
  "Lcom/jaac/avoqado_tpv/DaggerAvoqadoTPVApplication_HiltComponents_SingletonC;"
  "Lcom/jaac/avoqado_tpv/Hilt_MainActivity;"
)

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

apk_for_variant() {
  # nexgoProdDebug -> flavor=nexgoProd, buildType=debug
  local variant="$1" flavor buildType
  case "$variant" in
    *Debug)   buildType="debug";   flavor="${variant%Debug}" ;;
    *Release) buildType="release"; flavor="${variant%Release}" ;;
    *) echo "Variante no reconocida: $variant (usa p.ej. sandboxDebug)" >&2; exit 2 ;;
  esac
  local base="app/build/outputs/apk/${flavor}/${buildType}/app-${flavor}-${buildType}"
  # Los release sin firmar salen como app-<flavor>-release-unsigned.apk
  if [ ! -f "${base}.apk" ] && [ -f "${base}-unsigned.apk" ]; then
    echo "${base}-unsigned.apk"
  else
    echo "${base}.apk"
  fi
}

APK=""
VARIANT="sandboxDebug"
case "${1:-}" in
  -h|--help) usage ;;
  --variant)
    [ $# -ge 2 ] || usage
    VARIANT="$2"
    APK="$(apk_for_variant "$VARIANT")"
    ;;
  "") APK="$(apk_for_variant "$VARIANT")" ;;
  -*) usage ;;
  *)  APK="$1"; VARIANT="" ;;
esac

if [ ! -f "$APK" ]; then
  echo "❌ No existe el APK: $APK" >&2
  if [ -n "$VARIANT" ]; then
    echo "   Genéralo primero: ./gradlew assemble${VARIANT^}" >&2
  else
    echo "   Genéralo primero, p.ej.: ./gradlew assembleSandboxDebug" >&2
  fi
  exit 1
fi

echo "🔍 Verificando grafo Hilt en: $APK"

# `unzip -p` con comodín concatena TODOS los classes*.dex (multidex) a stdout.
DEX_BLOB="$(mktemp)"
# shellcheck disable=SC2064
trap "rm -f '$DEX_BLOB'" EXIT

if ! unzip -p "$APK" 'classes*.dex' > "$DEX_BLOB" 2>/dev/null || [ ! -s "$DEX_BLOB" ]; then
  echo "❌ El APK no contiene ningún classes*.dex — archivo corrupto o incompleto." >&2
  exit 1
fi

DEX_COUNT="$(unzip -l "$APK" 'classes*.dex' 2>/dev/null | grep -c 'classes.*\.dex' || true)"
echo "   dex encontrados: $DEX_COUNT · $(wc -c < "$DEX_BLOB") bytes"

# En release R8 renombra lo generado: el check no aplica.
if [[ "$APK" == *release* ]] || [[ "$APK" == *unsigned* ]]; then
  echo "ℹ️  APK release: R8 ofusca los nombres generados, este check no aplica. Saltando."
  exit 0
fi

MISSING=0
for symbol in "${REQUIRED_SYMBOLS[@]}"; do
  if grep -a -q -F -- "$symbol" "$DEX_BLOB"; then
    echo "   ✅ $symbol"
  else
    echo "   ❌ FALTA  $symbol"
    MISSING=$((MISSING + 1))
  fi
done

if [ "$MISSING" -gt 0 ]; then
  cat >&2 <<'REMEDY'

❌ APK INCOMPLETO — NO lo instales: crashea al arrancar con NoClassDefFoundError.

Faltan clases generadas por Hilt/KSP. El build salió verde porque Gradle consideró
las tareas UP-TO-DATE con salidas intermedias sucias. Reconstruye en limpio:

    ./gradlew --stop
    ./gradlew clean
    ./gradlew installSandboxDebug
    ./scripts/verify-apk-hilt.sh

Si vuelve a pasar, borra también el estado incremental de KSP:

    rm -rf app/build/generated/ksp app/build/kspCaches app/build/intermediates
REMEDY
  exit 1
fi

echo "✅ Grafo Hilt completo — APK listo para instalar."
