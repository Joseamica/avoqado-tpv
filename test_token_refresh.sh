#!/bin/bash

# ==============================================================================
# Token Refresh Test Script
# ==============================================================================
# Tests automatic token refresh when 401 Unauthorized is received
#
# PREREQUISITES:
# 1. Android device/emulator connected (adb devices)
# 2. App installed (./gradlew installDebug)
# 3. Backend running (npm run dev)
# 4. Backend configured with SHORT token expiration (see instructions below)
#
# HOW TO CONFIGURE SHORT TOKEN EXPIRATION (Backend):
# Edit: avoqado-server/src/services/auth/auth.service.ts
# Change:
#   expiresIn: '24h'  →  expiresIn: '30s'
#
# USAGE:
#   ./test_token_refresh.sh
# ==============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test configuration
PACKAGE_NAME="com.jaac.avoqado_tpv"
TEST_PIN="1234"
TEST_VENUE_ID="cmhtrvsvk00ad9krx8gb9jgbq"
TOKEN_EXPIRATION_SECONDS=35  # Wait 35 seconds (token expires at 30s)

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         Token Refresh Automatic Test Script              ║${NC}"
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo ""

# ==============================================================================
# STEP 1: Check prerequisites
# ==============================================================================
echo -e "${YELLOW}[1/7]${NC} Checking prerequisites..."

# Check if adb is installed
if ! command -v adb &> /dev/null; then
    echo -e "${RED}❌ adb not found. Install Android SDK Platform Tools.${NC}"
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No Android device/emulator connected.${NC}"
    echo "Run: adb devices"
    exit 1
fi

# Check if app is installed
if ! adb shell pm list packages | grep -q "$PACKAGE_NAME"; then
    echo -e "${RED}❌ App not installed.${NC}"
    echo "Run: ./gradlew installDebug"
    exit 1
fi

echo -e "${GREEN}✅ Prerequisites OK${NC}"
echo ""

# ==============================================================================
# STEP 2: Clear app data (fresh start)
# ==============================================================================
echo -e "${YELLOW}[2/7]${NC} Clearing app data..."
adb shell pm clear "$PACKAGE_NAME" > /dev/null 2>&1
echo -e "${GREEN}✅ App data cleared${NC}"
echo ""

# ==============================================================================
# STEP 3: Start app and navigate to login
# ==============================================================================
echo -e "${YELLOW}[3/7]${NC} Starting app..."
adb shell am start -n "$PACKAGE_NAME/.MainActivity" > /dev/null 2>&1
sleep 3
echo -e "${GREEN}✅ App started${NC}"
echo ""

# ==============================================================================
# STEP 4: Login with PIN
# ==============================================================================
echo -e "${YELLOW}[4/7]${NC} Logging in with PIN..."
echo -e "   ${BLUE}→ Venue: $TEST_VENUE_ID${NC}"
echo -e "   ${BLUE}→ PIN: $TEST_PIN${NC}"

# Start monitoring logs in background
LOG_FILE="/tmp/avoqado_token_test_$(date +%s).log"
echo -e "   ${BLUE}→ Saving logs to: $LOG_FILE${NC}"
adb logcat -c  # Clear logcat
adb logcat -v time | grep -E "(Auth|Token|Refresh|Backend Recording|401)" > "$LOG_FILE" &
LOGCAT_PID=$!

# Manual step (automated UI testing would require Espresso)
echo -e "${YELLOW}"
echo "   ⚠️  MANUAL STEP REQUIRED:"
echo "   1. Select venue: $TEST_VENUE_ID"
echo "   2. Enter PIN: $TEST_PIN"
echo "   3. Tap 'Iniciar Sesión'"
echo -e "${NC}"

read -p "Press ENTER when logged in successfully..."
echo ""

# Verify login success
if ! adb logcat -d | grep -q "Login successful"; then
    echo -e "${RED}❌ Login failed. Check credentials.${NC}"
    kill $LOGCAT_PID 2>/dev/null
    exit 1
fi

echo -e "${GREEN}✅ Login successful${NC}"
echo ""

# ==============================================================================
# STEP 5: Wait for token to expire
# ==============================================================================
echo -e "${YELLOW}[5/7]${NC} Waiting for token to expire..."
echo -e "   ${BLUE}→ Token expires in: ${TOKEN_EXPIRATION_SECONDS}s${NC}"
echo -e "   ${BLUE}→ Waiting...${NC}"

for i in $(seq $TOKEN_EXPIRATION_SECONDS -1 1); do
    echo -ne "   ${YELLOW}⏳ ${i}s remaining...${NC}\r"
    sleep 1
done
echo ""
echo -e "${GREEN}✅ Token should be expired now${NC}"
echo ""

# ==============================================================================
# STEP 6: Trigger payment (should get 401 and auto-refresh)
# ==============================================================================
echo -e "${YELLOW}[6/7]${NC} Testing token refresh..."
echo -e "   ${BLUE}→ Attempting payment with expired token${NC}"

echo -e "${YELLOW}"
echo "   ⚠️  MANUAL STEP REQUIRED:"
echo "   1. Tap 'Cobrar Rápido' or navigate to payment screen"
echo "   2. Enter amount: $50.00"
echo "   3. Skip rating (or rate 5 stars)"
echo "   4. Skip tip (or add tip)"
echo "   5. Select 'Pagar en Efectivo' (cash payment - no card needed)"
echo "   6. Observe if payment succeeds (QR code appears)"
echo -e "${NC}"

read -p "Press ENTER when payment is complete (or failed)..."
echo ""

# ==============================================================================
# STEP 7: Verify token refresh in logs
# ==============================================================================
echo -e "${YELLOW}[7/7]${NC} Analyzing logs..."
sleep 2  # Let logs buffer

# Stop logcat monitoring
kill $LOGCAT_PID 2>/dev/null

echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}                     TEST RESULTS                          ${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo ""

# Check for expected log patterns
HAS_401=$(grep -c "401 Unauthorized" "$LOG_FILE" || echo "0")
HAS_REFRESH_ATTEMPT=$(grep -c "Token expired, attempting refresh" "$LOG_FILE" || echo "0")
HAS_REFRESH_SUCCESS=$(grep -c "Token refreshed successfully" "$LOG_FILE" || echo "0")
HAS_RETRY=$(grep -c "retrying original request" "$LOG_FILE" || echo "0")
HAS_BACKEND_SUCCESS=$(grep -c "Payment recorded successfully" "$LOG_FILE" || echo "0")

echo -e "${BLUE}📊 Log Analysis:${NC}"
echo -e "   401 Unauthorized detected: ${HAS_401}"
echo -e "   Token refresh attempted: ${HAS_REFRESH_ATTEMPT}"
echo -e "   Token refresh succeeded: ${HAS_REFRESH_SUCCESS}"
echo -e "   Request retried: ${HAS_RETRY}"
echo -e "   Backend recording succeeded: ${HAS_BACKEND_SUCCESS}"
echo ""

# Determine test result
if [[ $HAS_401 -gt 0 ]] && [[ $HAS_REFRESH_SUCCESS -gt 0 ]] && [[ $HAS_BACKEND_SUCCESS -gt 0 ]]; then
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║                    ✅ TEST PASSED                         ║${NC}"
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo ""
    echo -e "${GREEN}✅ Token expired (401 received)${NC}"
    echo -e "${GREEN}✅ Token refresh triggered${NC}"
    echo -e "${GREEN}✅ Token refreshed successfully${NC}"
    echo -e "${GREEN}✅ Original request retried${NC}"
    echo -e "${GREEN}✅ Payment recorded to backend${NC}"
    echo -e "${GREEN}✅ QR code should be displayed${NC}"
    echo ""
    RESULT=0
elif [[ $HAS_401 -eq 0 ]]; then
    echo -e "${YELLOW}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${YELLOW}║                   ⚠️  TEST INCONCLUSIVE                   ║${NC}"
    echo -e "${YELLOW}╔════════════════════════════════════════════════════════════╗${NC}"
    echo ""
    echo -e "${YELLOW}⚠️  No 401 error detected${NC}"
    echo -e "${YELLOW}   Possible reasons:${NC}"
    echo -e "   1. Backend token expiration not configured to 30s"
    echo -e "   2. Waited too long, token already refreshed proactively"
    echo -e "   3. Backend not running"
    echo ""
    echo -e "${YELLOW}   Configure backend with short token:${NC}"
    echo -e "   Edit: avoqado-server/src/services/auth/auth.service.ts"
    echo -e "   Change: expiresIn: '24h' → expiresIn: '30s'"
    echo ""
    RESULT=1
else
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║                    ❌ TEST FAILED                         ║${NC}"
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo ""
    echo -e "${RED}❌ Token refresh failed${NC}"
    echo -e "   Possible issues:${NC}"
    echo -e "   - Refresh token also expired"
    echo -e "   - Backend refresh endpoint not working"
    echo -e "   - Network connectivity issue"
    echo ""
    RESULT=1
fi

# Show relevant log excerpts
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}                    RELEVANT LOGS                          ${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo ""

if [[ -s "$LOG_FILE" ]]; then
    # Show 401 errors
    if grep -q "401" "$LOG_FILE"; then
        echo -e "${YELLOW}🔍 401 Unauthorized:${NC}"
        grep "401" "$LOG_FILE" | head -5
        echo ""
    fi

    # Show token refresh
    if grep -q "Token refresh" "$LOG_FILE"; then
        echo -e "${YELLOW}🔍 Token Refresh:${NC}"
        grep -i "token refresh" "$LOG_FILE" | head -5
        echo ""
    fi

    # Show backend recording
    if grep -q "Backend Recording" "$LOG_FILE"; then
        echo -e "${YELLOW}🔍 Backend Recording:${NC}"
        grep "Backend Recording" "$LOG_FILE" | tail -5
        echo ""
    fi
else
    echo -e "${RED}⚠️  No logs captured${NC}"
fi

echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${BLUE}📄 Full logs saved to: $LOG_FILE${NC}"
echo -e "${BLUE}   View with: cat $LOG_FILE${NC}"
echo ""

exit $RESULT
