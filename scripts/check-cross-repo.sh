#!/bin/bash
# Cross-repo consistency check before TPV release
#
# This script helps verify that all three Avoqado repos are in sync
# before generating a production APK.
#
# Usage: ./scripts/check-cross-repo.sh
#
# Why this matters:
# - Backend/Dashboard deploy in minutes
# - TPV takes 3-5 days (requires Blumon/PAX signature)
# - Desynced releases cause production issues

echo "🔍 Verificando consistencia cross-repo..."
echo ""

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ERRORS=0
WARNINGS=0

# Paths to sibling repos
SERVER_PATH="../avoqado-server"
DASHBOARD_PATH="../avoqado-web-dashboard"

# ═══════════════════════════════════════════════════════════════
# 1. Verify sibling repos exist
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📂 Verificando repositorios...${NC}"

if [ ! -d "$SERVER_PATH" ]; then
    echo -e "${RED}❌ avoqado-server no encontrado en $SERVER_PATH${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}✓ avoqado-server encontrado${NC}"
fi

if [ ! -d "$DASHBOARD_PATH" ]; then
    echo -e "${RED}❌ avoqado-web-dashboard no encontrado en $DASHBOARD_PATH${NC}"
    ERRORS=$((ERRORS+1))
else
    echo -e "${GREEN}✓ avoqado-web-dashboard encontrado${NC}"
fi

echo ""

# ═══════════════════════════════════════════════════════════════
# 2. Show recent commits in each repo
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}📦 Últimos commits por repo:${NC}"
echo ""

echo -e "${YELLOW}avoqado-tpv (este repo):${NC}"
git --no-pager log --oneline -3 2>/dev/null || echo "  (error leyendo commits)"
echo ""

echo -e "${YELLOW}avoqado-server:${NC}"
git --no-pager -C "$SERVER_PATH" log --oneline -3 2>/dev/null || echo "  (no disponible)"
echo ""

echo -e "${YELLOW}avoqado-web-dashboard:${NC}"
git --no-pager -C "$DASHBOARD_PATH" log --oneline -3 2>/dev/null || echo "  (no disponible)"
echo ""

# ═══════════════════════════════════════════════════════════════
# 3. Check for uncommitted changes in all repos
# ═══════════════════════════════════════════════════════════════
echo -e "${BLUE}⚠️  Cambios no commiteados:${NC}"
echo ""

TPV_CHANGES=$(git status --short 2>/dev/null)
SERVER_CHANGES=$(git -C "$SERVER_PATH" status --short 2>/dev/null)
DASHBOARD_CHANGES=$(git -C "$DASHBOARD_PATH" status --short 2>/dev/null)

echo -e "${YELLOW}avoqado-tpv:${NC}"
if [ -z "$TPV_CHANGES" ]; then
    echo -e "  ${GREEN}(limpio)${NC}"
else
    echo "$TPV_CHANGES" | head -10
    WARNINGS=$((WARNINGS+1))
fi
echo ""

echo -e "${YELLOW}avoqado-server:${NC}"
if [ -z "$SERVER_CHANGES" ]; then
    echo -e "  ${GREEN}(limpio)${NC}"
else
    echo "$SERVER_CHANGES" | head -10
    WARNINGS=$((WARNINGS+1))
fi
echo ""

echo -e "${YELLOW}avoqado-web-dashboard:${NC}"
if [ -z "$DASHBOARD_CHANGES" ]; then
    echo -e "  ${GREEN}(limpio)${NC}"
else
    echo "$DASHBOARD_CHANGES" | head -10
    WARNINGS=$((WARNINGS+1))
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 4. Show current TPV version
# ═══════════════════════════════════════════════════════════════
VERSION=$(grep "versionName" app/build.gradle.kts 2>/dev/null | head -1 | sed 's/.*"\(.*\)".*/\1/')
VERSION_CODE=$(grep "versionCode" app/build.gradle.kts 2>/dev/null | head -1 | sed 's/[^0-9]*//g')

if [ -n "$VERSION" ]; then
    echo -e "${GREEN}📱 TPV Version: $VERSION (code: $VERSION_CODE)${NC}"
else
    echo -e "${YELLOW}📱 TPV Version: (no encontrada)${NC}"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# 5. Important reminders
# ═══════════════════════════════════════════════════════════════
echo -e "${YELLOW}═══════════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}⚠️  RECORDATORIOS ANTES DE RELEASE:${NC}"
echo -e "${YELLOW}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo "   1. ¿El backend soporta TODAS las versiones del TPV en producción?"
echo "      → Backend SIEMPRE debe ser backwards-compatible"
echo ""
echo "   2. ¿Hay endpoints nuevos que requieran deploy del server PRIMERO?"
echo "      → Si el TPV usa un endpoint nuevo, el server debe estar en prod antes"
echo ""
echo "   3. ¿Hay cambios en el TPV que requieran backend actualizado?"
echo "      → Coordinar: deploy server → esperar → enviar APK a Blumon"
echo ""
echo "   4. ¿Están los 3 repos en la misma feature branch (si aplica)?"
echo "      → Para features cross-repo, todos deben estar listos"
echo ""
echo "   5. ¿Se actualizó la documentación en avoqado-server/docs/?"
echo "      → Features cross-repo deben documentarse en el hub central"
echo ""

# ═══════════════════════════════════════════════════════════════
# 6. Final result
# ═══════════════════════════════════════════════════════════════
echo ""
if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}❌ Verificación fallida con $ERRORS error(es)${NC}"
    exit 1
elif [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}⚠️  Verificación completada con $WARNINGS advertencia(s)${NC}"
    echo -e "${YELLOW}   Revisa los cambios no commiteados arriba.${NC}"
    exit 0
else
    echo -e "${GREEN}✅ Verificación completada. Todos los repos están limpios.${NC}"
    exit 0
fi
