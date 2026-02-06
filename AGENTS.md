# AGENTS.md - Avoqado TPV Agent Roles

Specialized agent roles for the Avoqado TPV Android POS app. Each agent loads different context.

## Android Developer

**Scope**: Feature implementation, new screens, API integration, Room entities, Hilt modules.

**Context to load**: `CLAUDE.md` (always loaded), `docs/KOTLIN_BEST_PRACTICES.md`, `docs/DOMAIN_RULES.md`, `docs/DEVELOPMENT_WORKFLOW.md`

**Focus**:
- Clean Architecture: Presentation -> Domain -> Data
- 100% Jetpack Compose (no XML)
- Always paginate queries (1GB RAM target)
- Every DB query must filter by `venueId`
- New @Entity fields require Room migrations

## Payment Engineer

**Scope**: PaymentViewModel, PaymentScreen, Blumon SDK, refunds, split payments, BLE payments.

**Context to load**: `.claude/rules/critical-warnings.md` (auto-loaded), `docs/PAYMENT_FLOW_ORIGIN.md`, `docs/PAYMENT_SESSION.md`, `docs/BLE_PAYMENT_IOS_APP.md`, `docs/BLE_PAYMENT_QUEUE.md`

**Focus**:
- 8 features share PaymentViewModel — test ALL flows after any change
- Sync sandbox/ and production/ variants
- Clear ALL state in `resetPayment()`
- Read `avoqado-server/docs/blumon-tpv/BLUMON_MULTI_MERCHANT_ANALYSIS.md` before Blumon work

## DevOps / Release Engineer

**Scope**: APK builds, signing, deployment, version management, cross-repo coordination.

**Context to load**: `.claude/rules/release-and-git.md` (auto-loaded), `docs/CROSS_REPO_RELEASE_FLOW.md`, `docs/FORCE_UPDATE_SYSTEM.md`, `docs/PRODUCTION_DEPLOYMENT.md`

**Focus**:
- apksigner v2 (never jarsigner)
- Save APKs to iCloud structure
- Run `./scripts/check-cross-repo.sh` before production APK
- Backend deploys first, TPV takes 3-5 days
- Version bump: "Can user do something new?" -> MINOR, otherwise PATCH

## QA / Testing Engineer

**Scope**: ADB monitoring, log capture, regression testing, migration testing, permissions verification.

**Context to load**: `.claude/rules/testing-and-adb.md` (auto-loaded), `docs/TESTING_GUIDE.md`, `docs/PAY_LATER_TESTING_CHECKLIST.md`

**Focus**:
- ADB monitoring mandatory after every change
- Use `./scripts/capture-logs.sh` for feature testing
- Test Room migrations: old version -> generate data -> new version
- Verify permissions: exact name match between backend and TPV
- Test with multiple roles (WAITER, CASHIER, MANAGER, ADMIN)

## Code Reviewer

**Scope**: PR reviews, code quality, regression prevention, variant sync verification.

**Context to load**: All `.claude/rules/` (auto-loaded), `docs/KOTLIN_BEST_PRACTICES.md`, `docs/DECISION_MATRIX.md`

**Focus**:
- No regressions — verify all related features still work
- Variant sync — changes in sandbox/ must match production/
- Room migration exists for every @Entity change
- Permission name consistency across repos
- `resetPayment()` clears all new state variables
- BigDecimal for money, pagination for queries, venueId on all DB calls
