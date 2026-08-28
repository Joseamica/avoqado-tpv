# AGENTS.md - Avoqado TPV Agent Roles

## 🔴 Verificación pesada: por `avq-verify`, nunca a mano

**Van SIEMPRE por el script, sin importar cuánto tarden:** `./gradlew` (cualquier tarea que
compile), `xcodebuild`, `tsc` / `npm run build`, y cualquier corrida de jest/vitest de más de un
archivo. **Van a pelo:** lint, formato, UN archivo de test, y lo que no reserve memoria en serio.

Esto NO contradice "un compile de un solo proyecto se corre siempre, aunque la máquina esté
saturada": aquello decide **si** verificas (siempre sí), esto decide **cómo** lo lanzas —
haciendo fila en vez de encimarte. Se lanza desde el root del workspace:

```bash
cd /Users/amieva/Documents/Programming/Avoqado
./scripts/avq-verify.sh avoqado-tpv <comando>
```

Hace fila: un trabajo pesado a la vez en esta Mac, que corre con ~20 sesiones de IA encima y vive
con el swap al límite. En ESTE repo el remoto no aplica (el Alienware no tiene Java ni Xcode): el script hace la **fila local**, que es justo lo que evita dos builds de Gradle/Xcode al mismo tiempo.

Detalle completo: `Avoqado/CLAUDE.md`, sección "Verificación repartida".


Specialized agent roles for the Avoqado TPV Android POS app. Each agent loads different context.

## 🔴 Antes de construir: tier + activación (dos decisiones, no una)

Este archivo NO reemplaza al `CLAUDE.md` de este repo — léelo. Lo que más se rompe si lo saltas:

- **Tier** ("¿lo pagó?") y **activación** ("¿lo quiere prendido?") son ejes DISTINTOS: se componen con AND.
- Un switch se justifica **solo** si puedes nombrar dos clientes reales que quieran lo contrario. Si no, es
  comportamiento core y va **sin** toggle — la app no se construye por toggles.
- El switch canónico vive en `avoqado-web-dashboard`. 🔴 **Nunca solo un `UPDATE` en Postgres.**
- El default ON/OFF lo decides tú midiendo el riesgo; pregunta al founder solo si toca dinero, fiscal,
  permisos, stock o algo irreversible (ahí el default es OFF).
- 🔴 **Apagado se VE y se EXPLICA** — nunca desaparecer en silencio.

Regla completa: `avoqado-server/.claude/rules/feature-gating.md` · cross-repo: `CLAUDE.md` del workspace.

## Entorno: varias sesiones de IA trabajan en paralelo (contexto, no un bloqueo)

Casi siempre hay 2+ agentes editando este workspace al mismo tiempo. Es lo normal: **no es una
anomalía, no es motivo para detenerte, preguntar ni "arreglar" nada.** Solo cambia cómo interpretas
lo que ves:

- **Archivos modificados que tú no tocaste** en `git status` / `git diff` = WIP de otra sesión. Normal.
- 🔴 **Nunca** `git reset --hard`, `git checkout .`, `git clean`, `git stash` ni cambies de rama "para
  dejar limpio": el árbol de trabajo es compartido y eso sí destruye trabajo ajeno irrecuperable.
  Es la única regla dura de esta sección.
- **Commitea por rutas explícitas** (`git add <ruta>`), nunca `git add -A` / `git add .`. Si aun así
  se cuela WIP ajeno en tu commit, **no es grave**: no lo reviertas ni lo reescribas — dilo en el reporte.
- **Ruido que no viene de tu cambio**: el dev server hace hot-reload o se reinicia solo, un test/build
  truena en un archivo que no tocaste, un puerto ocupado. Verifica con `git diff <archivo>`: si ese
  cambio no es tuyo, **no lo debuggees ni lo corrijas** — reintenta una vez y, si sigue, anótalo en el
  reporte y continúa con lo tuyo.
- **No mates procesos, servidores, emuladores ni daemons de build que no arrancaste tú**, ni reinicies
  o borres bases de datos locales: otras sesiones están usándolas.
- Si un `Edit` falla porque el archivo cambió debajo de ti, relee y reaplica. Sin drama.
- ¿Quién más está adentro? MCP **Huella**: `quien_trabaja(repo)` y `actividad_reciente(repo)`.

**Asume concurrencia, no conflicto. Sigue programando.**

## Verificar sí; cuánto verificar lo decide la máquina

Esta Mac (10 núcleos / 32 GB) está compartida con las demás sesiones y vive cerca del límite.

**Pasan por el chequeo de capacidad, y SOLO estas:** `./gradlew assemble*` / `bundle*`, `xcodebuild`,
la suite de tests completa, el typecheck de todo el monorepo.
**No pasan nunca — se corren siempre, aunque la máquina esté saturada:** typecheck o build de UN
proyecto, UN archivo de test, lint. Cuestan segundos: la carga NO es excusa para saltárselos.

```bash
sysctl -n hw.ncpu vm.loadavg   # núcleos y { 1min 5min 15min }
sysctl -n vm.swapusage         # 'free' es la señal que más importa
pgrep -fl "GradleDaemon|KotlinCompileDaemon|xcodebuild|jest|vitest|tsc" | head
```

- **Si swap `free` < 2 GB, o load de 1 min > 2× núcleos, o ya hay un build ajeno corriendo: no arranques.**
  Adelanta lo que no dependa de eso y reintenta (cada ~2 min, tope ~10 min). Si sigue saturado, corre la
  verificación corta y reporta la larga como pendiente — no te quedes esperando indefinidamente.
- **Nunca dos builds pesados a la vez**: dos daemons de Kotlin a `-Xmx6g` tumban la máquina.
- Única excepción a "no mates procesos ajenos": si `pgrep` no muestra ningún build activo,
  `./gradlew --stop` libera daemons ociosos (4–6 GB cada uno, viven 2 h sin usarse) — dilo en el reporte.
  Los servidores de dev, emuladores y bases de datos NO se tocan.
- Si el typecheck pelón (`npx tsc --noEmit`) revienta por memoria, usa el script del repo (`npm run build`).

**La carga nunca compra "no lo verifiqué" — compra "lo verifiqué en corto".** Si cambiaste código, se
comprueba antes de decir que está listo. Lo que la máquina decide es el *tamaño*: typecheck solo del
proyecto tocado, el archivo de test en vez de la suite completa, `assembleDebug` en vez de
`assembleRelease`. **Lo que difieras va explícito en el reporte, con el comando exacto para correrlo.**
Un "listo" que esconde lo que no se corrió es un reporte falso.

| Qué tocaste | Mínimo obligatorio |
|---|---|
| Dinero, fechas/timezone, tiers, permisos, stock, pagos/reembolsos, migraciones de datos | **Test primero (TDD)** + suite del módulo. No negociable: esto no se difiere ni con la máquina en llamas. |
| Cualquier otro código | Que compile / typechee el proyecto tocado. Un cambio que no compila no es un cambio. |
| Cambio amplio, o antes de commitear/lanzar | Suite completa + build completo. Aquí sí se espera capacidad. |
| Markdown, docs, comentarios, copy sin lógica | Nada. |

"No era importante" es una conclusión que se justifica en el reporte, no un default. Si dudas, córrelo.

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
- **ALWAYS query Firebase Crashlytics MCP FIRST** when investigating payment bugs — before asking for screenshots. Use `crashlytics_get_report(appId="1:219752736783:android:d09cd5eb6162e7ee52db7a", report="topIssues")` to start. Check FATAL + NON_FATAL events.
- For Blumon TPV SDK failures, after Crashlytics/logcat, verify the transaction in the Blumon TPV portal with Playwright/browser automation when credentials are available. Production: `https://element.blumonpay.net/transacciones`. Sandbox: `https://sandbox-atom.blumonpay.net/transacciones`. Use secure/session credentials only (for example `BLUMON_PORTAL_USER` / `BLUMON_PORTAL_PASSWORD`); never store portal credentials, JWTs, PANs, or sensitive screenshots in the repo. If the rejection appears in the portal, treat it as processor/issuer/Blumon TPV-side unless TPV logs contradict it; if it does not appear, treat it as high probability TPV/app integration bug.

## DevOps / Release Engineer

**Scope**: APK builds, signing, deployment, version management, cross-repo coordination.

**Context to load**: `.claude/rules/release-and-git.md` (auto-loaded), `docs/CROSS_REPO_RELEASE_FLOW.md`, `docs/FORCE_UPDATE_SYSTEM.md`, `docs/PRODUCTION_DEPLOYMENT.md`

**Focus**:
- apksigner v2 (never jarsigner)
- Save APKs to iCloud structure
- Run `./scripts/check-cross-repo.sh` before production APK
- Backend deploys first, TPV takes 3-5 days
- Version bump: "Can user do something new?" -> MINOR, otherwise PATCH

## 🔴 Emoji en nombres de test: NO (rompen la caché de build de Gradle)

En logs, KDoc y comentarios el emoji está bien. Pero un `fun \`🔴 no se confunde con un pago\`()`
hace que Kotlin bautice las clases anónimas de ese test con ese nombre — genera el ARCHIVO
`…Test$🔴 no se confunde con un pago$1.class`, que el packer de la caché no puede leer. La tarea de
transform revienta con «Could not get file mode for …», un fallo que NO es de tu código y que no
menciona la causa. Acentos y em-dash (—) sí funcionan; sólo los emoji rompen.

Para marcar criticidad en el nombre, la convención es **`P1` / `P2` / `P3`** (antes `🔴` / `🟠` /
`🟡`). Lo vigila `:app:checkNoEmojiInTestNames`, que corre solo antes de cualquier tarea de test —
y nombra archivo y línea del culpable. Mismo guardia en `avoqado-android`. Caso que lo originó:
2026-08-20, dos tests de `DeclineTicketTest` aquí y 35 en Android.

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

## 🔴 Cómo hablarle al founder

Regla completa en `~/.claude/CLAUDE.md` (aplica a todos sus proyectos) y en
`Avoqado/.claude/rules/como-hablarle-al-founder.md`.

- **Cuando le pidas una opinión o le hagas una pregunta: explícale FÁCIL.** Analogías antes que
  jerga, y **diagrama** (`mcp__visualize__show_widget`) siempre que sean dos caminos, dos
  mecanismos, un flujo o un antes/después. Una pregunta a la vez, opciones cortas, la consecuencia
  de cada una en una línea.
- **Las respuestas largas están bien** — le sirve que razones y no adivines.
- 🔴 **SIEMPRE cierra con 2-3 líneas en lenguaje llano**: qué pasó, qué significa para él, y qué
  necesitas de él. Sin ese cierre, el contenido puede ser correcto y aun así no llegarle.

