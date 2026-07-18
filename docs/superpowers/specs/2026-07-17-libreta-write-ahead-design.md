# Spec v2: La Libreta (write-ahead ledger de cobros) + Política de Fuentes de Verdad

**Fecha:** 2026-07-17 · **v2:** 2026-07-18 · **Estado:** 🟡 **BORRADOR — P0 confirmables abiertos** (no implementar hasta cerrarlos, §10)
**Validaciones:** workflow fuentes-de-verdad (APROBADO CON FIXES) · workflow blast-radius (SEGURO CON GUARDRAILS) · **revisión estática externa 2026-07-18 (13 hallazgos INCORPORADOS en esta v2)**
**Alcance:** avoqado-tpv (PAX/Blumon + Nexgo/AngelPay) y avoqado-server

---

## 1. Motivación — el caso que lo justifica

**Mindform, 2026-07-16 14:48 UTC** (terminal `AVQD-2841653092`, v2.5.3): el banco aprobó $1,400 (op 21372460, auth 256125), la TPV se congeló después de la aprobación ("la terminal se quedó pensando muchísimo tiempo" — testigo presencial) y **jamás intentó registrar el pago**. El webhook llegó 1s después, no encontró Payment, y 24h después se marcó ORPHANED. El cargo **sí capturó** (portal element + cliente + posteo Inbursa). Resuelto manualmente (Payment `cmrqrwq7akqrhoxcm5lmns107`, 2026-07-17). **Auditoría histórica completa (2026-07-18): es el ÚNICO caso real de dinero-capturado-sin-registro en toda la plataforma** (el otro ORPHANED de $100 fue falsa alarma de matching — el Payment existe; 4 restantes = pruebas de $0.50–$3).

**El hueco de código (documentado en el propio archivo):** el registro solo se dispara en el callback de aprobación del SDK (`PaymentViewModel.kt:5837` production); la cola offline solo se activa si `recordPaymentUseCase` corrió y falló (`:5915`); el `finally` reconoce la ventana: *"money moved, no record, no queue"* (`:5930-5942`). Independiente de la versión — ni 2.6.4 ni el código unreleased lo cierran.

## 2. Alcance

- **SÍ:** cobros con tarjeta por Blumon TPV (PAX) y AngelPay (Nexgo). **REEMBOLSOS: EN ALCANCE (decisión 2026-07-18)** — hoy no tienen ninguna red ("we don't queue refunds", `:8329-8330`); mismo schema desde el día 1 (`kind=REFUND`), implementación en fase posterior a ventas.
- **NO:** pagos en EFECTIVO (nunca generan webhook ni ventana post-auth — fuera por construcción). Crypto. Cambios al flujo del cajero.

## 3. Principio rector — Fuentes de verdad (validado contra código)

> **El webhook es un TESTIGO/correlador, no una autoridad. Nunca se registra un pago a ciegas.**
> Autorización ≠ liquidación. Y "no encontrado" ≠ "no ocurrió" (§4.6).

| Pregunta | Autoridad | Quién NO puede serlo |
|---|---|---|
| ¿Se autorizó dinero? | Doble testigo del mismo evento: SDK en el cobro + webhook APROBADA (`codeResponse=='00'`) | La TPV sola (se congela sin enterarse) |
| ¿El dinero se quedó (capturó/liquidó)? | Registro de captura del procesador: portal element / `TransactionDetailsUseCase(operationID)` del SDK Blumon (existe en el binario, verificado por javap; **el adaptador actual NO lo expone** — `BlumonPostOperationsAdapter.kt:15` lanza UnsupportedOperation → tarea de cableado) / `getTransactionHistory` AngelPay (semántica nube-vs-local SIN confirmar, §10) | **El webhook NO** (ciego a reversos). La TPV NO. El Payment NO |
| ¿Qué se vendió (orden/productos/staff/split)? | **La libreta de la TPV** (snapshot inmutable del comando, §4.3) + Order/OrderItem/PaymentAllocation | **El webhook JAMÁS** (datos de tarjeta, no de negocio) |
| ¿Registro contable final? | El Payment del backend | El webhook nunca lo crea (match-only verificado) |

## 4. Diseño TPV — La Libreta

### 4.1 Modelo de datos
Tabla Room **NUEVA** `payment_attempts` (no extender `pending_payments` — razones en v1, siguen válidas). Campos clave:
- `attempt_id` TEXT PK (= `idempotencyKey` UUID) · **`venue_id` TEXT NOT NULL** (regla de aislamiento multi-tenant: TODA query filtra por venueId — corregido en v2) · `processor` (BLUMON|ANGELPAY) · `kind` (SALE|REFUND)
- `state` TEXT + **`state_version` INTEGER** (transiciones CAS monotónicas, §4.4)
- **`amount_cents` INTEGER NOT NULL, `tip_cents` INTEGER NOT NULL, `currency` TEXT NOT NULL DEFAULT 'MXN'** (corregido en v2: centavos como Long, nunca decimales de texto ni unidades ambiguas por procesador)
- **`payment_context_json` TEXT NOT NULL** — snapshot inmutable y versionado (`context_schema_version`) del `PaymentContext` COMPLETO del intento (venueId, orderId, splitType/equal-parts, terminalPaymentRequestId, seriales/serialized, proof-of-sale refs, staffId, merchantAccountId — todo lo que hoy consume `RecordPaymentUseCase`) + **`recording_route` TEXT (FAST|ORDER|REFUND)** — corregido en v2: el recovery reproduce la ruta ORIGINAL vía los recorders existentes, **jamás todo por /fast** (`RecordPaymentUseCase` rutea `FastPaymentRecorder`/`OrderPaymentRecorder` — registrar una orden por /fast fabricaría una orden sintética = corrupción)
- Desenlace del host: `operation_id?`, `reference_number?`, `auth_code?`, `host_approved?` (BOOLEAN), `masked_pan?`, `card_brand?`, `entry_mode?`
- `created_at`, `updated_at`, `verify_attempts` INTEGER, `lease_until?` (worker lease, §4.5)
Migración: `CREATE TABLE IF NOT EXISTS` 26→27 (patrón 6 precedentes/0 incidentes), DDL verbatim del schema JSON exportado, caso en `AvoqadoDatabaseMigrationTest`, prueba de upgrade con datos preexistentes.

### 4.2 Máquina de estados (v2 — cubre la ventana Mindform)
```
PREPARANDO → AUTORIZANDO → HOST_RESPONDIO → AUTORIZADO → REGISTRADO → CERRADA
                 │               │              │            ↘ REGISTRO_FALLIDO → ENTREGADA_A_COLA
                 │               ↘ (host declinó explícito) → DESCARTADA
                 ↘ (vencida sin respuesta persistida) → INDETERMINADO (cuarentena)
```
- **`HOST_RESPONDIO` (nuevo en v2, cierra el P0 principal):** se escribe **INMEDIATAMENTE al recibir la respuesta del host** en `performOnlineAuthorization` (zona `:3088`, donde llega `authResult`), con operationId/reference/auth y aprobado/declinado, **ANTES del remate EMV (CompleteEmvTrans) y de publicar Success**. El hook v1 (en `recordingInFlight`, `:3162`) llegaba tarde: el proceso puede morir entre la respuesta del host y ese punto — exactamente la ventana Mindform.
- **`AUTORIZADO`:** tras el discriminador de aprobación, antes de publicar Success.
- **`REGISTRO_FALLIDO` / `ENTREGADA_A_COLA` (nuevos en v2):** el fallo de registro queda explícito; al encolar con éxito en `pending_payments`, la cola es dueña (su idempotencia + retry) y la fila queda ENTREGADA_A_COLA (se poda; el recovery NO la toca — anti doble-registro).
- **`CERRADA` solo desde REGISTRADO** (v2: el `finally` libera SOLO estado runtime — banderas — y NUNCA cierra la fila; cerrar en finally borraba la evidencia justo en los fallos, que es cuando más se necesita).
- **`DESCARTADA` SOLO con decline/reversal EXPLÍCITO del procesador.** "No encontrado" NUNCA descarta (§4.6).
- **`INDETERMINADO` (cuarentena):** AUTORIZANDO vencido (el proceso murió sin respuesta persistida — sin operationId no hay correlación automática), o verificación imposible/agotada. Visible en badge + bandeja; nunca invisible, nunca se borra.

### 4.3 Ciclo de vida (responde "¿se elimina la nota?")
Happy path: nace → avanza → REGISTRADO → CERRADA en silencio; invisible para el cajero; se poda a ~7 días (espejo de `deleteOldSyncedPayments`). Solo INDETERMINADO/REGISTRO_FALLIDO/atoradas disparan badge + recovery.

### 4.4 Garantías de escritura (v2 — barrera real, no "síncrono" vago)
- **Barrera de precedencia:** `INSERT PREPARANDO` + **commit confirmado** → `CAS a AUTORIZANDO` + **commit confirmado** → **solo entonces** invocar el SDK. (Room en dispatcher IO dedicado con scope de aplicación SupervisorJob; la llamada al SDK espera el commit — no basta "fuera del hilo UI".)
- **Transiciones CAS monotónicas:** `UPDATE ... SET state=?, state_version=state_version+1 WHERE attempt_id=? AND state=?esperado` — una transición que no casa se ignora y se loguea (anti-carreras worker/callback/manual).
- Escrituras post-SDK (`HOST_RESPONDIO`→…): `withContext(NonCancellable + IO)` — sobreviven al pop de pantalla/clear de la VM.
- Un fallo de la libreta **jamás bloquea el cobro** (runCatching; degrada a "sin fila" = no peor que hoy).
- `attemptId === idempotencyKey` en TODO camino de registro, sin excepciones.

### 4.5 Recovery (arranque + reconexión + manual)
Componente nuevo vía WorkManager (**unique work** por nombre; **lease** `lease_until` + estado transitorio VERIFYING + **relectura CAS antes de cualquier transición terminal** — arranque, reconexión, periódico y botón "Verificar ahora" no pueden pisarse). NO tocar `PaymentSyncWorker`.
- **Filas `HOST_RESPONDIO`/`AUTORIZADO`** (hay operationId): verificar desenlace — Blumon: cablear `TransactionDetailsUseCase(operationID)` (nuevo en el adaptador); AngelPay: `getTransactionHistory`. Capturado ⇒ **reproducir la ruta ORIGINAL** (`recording_route` + `payment_context_json` → recorder correspondiente) contra el endpoint de recovery del backend (§5.3) ⇒ REGISTRADO. **Decline/reversal explícito** ⇒ DESCARTADA. No encontrado/duda/API caída ⇒ reintento con backoff; agotado ⇒ INDETERMINADO (cuarentena) — **nunca descartar por ausencia** (not-found puede ser merchant equivocado, consistencia eventual, sesión expirada).
- **Filas `AUTORIZANDO` vencidas** (> umbral): INDETERMINADO directo (sin correlación automática). Futuro v2+: barrido `CutDetails` por monto+hora como asistencia manual.
- **Filas `REGISTRO_FALLIDO`:** re-entregar a `pending_payments`.
- **`OFFLINE-*` (contactless offline-aprobado / refund offline):** detectar el prefijo ANTES de consultar; registrarlos desde la propia fila (el dinero SÍ se movió) o a cuarentena — jamás auto-descartar.
- **Lock por procesador (nuevo en v2):** un mutex global por SDK que cubre switch de merchant, autorización, TransactionDetails/history y refund — una verificación concurrente con un cobro podría consultar/cobrar con OTRA afiliación (los SDKs mantienen sesión/merchant global; en Blumon la igualdad de serial NO prueba el posId efectivo — usar `isMerchantEffectivelyActive` de `MultiMerchantSDKManager` y restaurar el merchant anterior al terminar).
- Modo SOMBRA: todo lo anterior solo observa/loguea, sin registrar ni descartar.

### 4.6 UX (v2 — corregida a la navegación real)
El tile "Pagos" de la Home abre **PaymentsScreen** (no `PaymentTransactions`, que es herramienta SuperAdmin). Entonces: badge contador sobre el tile de Pagos (patrón `quickPayBadge`) → **sección "Por verificar" dentro de PaymentsScreen** (estado + "Verificar ahora"). Diálogo único no bloqueante al arrancar tras un intento interrumpido. Nada de esto toca reportes/sumas.

## 5. Diseño Backend

### 5.1 Política de webhook (árbol — igual v1, con endurecimientos v2)
Paso 0 clasificar por `operationType`+`codeResponse` (DEVOLUCION/CANCELACION jamás alerta de venta) → Paso 1 match inline → Paso 2 PENDING+cron 24h → Paso 3 **verificar captura ANTES de alertar** → Paso 4 bandeja "pagos sin atribuir" (cuarentena, nunca Payment normal, nunca se borra).
**Endurecimientos v2:**
- **ACK solo tras persistencia durable:** hoy una falla al crear `ProviderEventLog` puede terminar en HTTP 200 (`blumon-webhook.tpv.controller.ts:132`) → el 200 se responde únicamente con el evento persistido; si no, 5xx para que Blumon reintente.
- **Matching es P0 (era P2):** scope de venue OBLIGATORIO (sin scope ⇒ cuarentena, no findFirst global), `findMany(take:2)` con monto+fecha+proveedor+`type:{not:'REFUND'}`; **exactamente 1 candidato o cuarentena** (0 o ≥2 jamás se auto-liga). El patrón `type:{not:'REFUND'}` ya existe (`payment.tpv.service.ts:1413,2191`) — falta en `baseWhere` del webhook (`blumon-webhook.service.ts:699-703`).
- El caso `matchConditions` vacío deja de morir en ERROR silencioso (hoy el cron nunca lo reintenta): PENDING + `NO_MATCH_FIELDS`.

### 5.2 Fixes al código actual (v1, siguen)
REFUND-filter (arriba) · auditoría simétrica Payment→webhook · sweep incluye ERROR (cerrar cuarentenas con registros tardíos) · **job de conciliación AngelPay** (receptor construido pero INACTIVO: 0 webhooks jamás; ninguno de los 6 comercios tiene `angelpayWebhookSecret` — activación = config keys `whsec_` + registrar endpoint, sin código; **degradación sin webhook: la libreta sigue cubriendo al comercio [testigo único]; el webhook agrega el segundo testigo server-side**) · llave muerta `processorId` (persistir `blumonOperationNumber` en columna indexable) · bandeja multi-venue con selección explícita + lock.

### 5.3 `recordRecoveredPayment` (nuevo en v2 — P0)
El endpoint normal de orden **re-valida inventario** (`validatePreFlightInventory`, `payment.tpv.service.ts:1505`) y **busca el turno ABIERTO ACTUAL** (`:1514`, y `/fast` ni acepta shiftId) — inaceptable para un cobro que el banco YA capturó horas antes. Se necesita una ruta de registro de recuperación dedicada que: acepta `recordingRoute` + contexto completo + `shiftId` explícito (o null real), **no** re-valida inventario, **no** adivina turno, deduplica por `idempotencyKey` **y valida el fingerprint** (§5.4), y marca `processorData.recovered=true`.

### 5.4 Fingerprint canónico del intento (nuevo en v2)
`attemptId` viaja acompañado de un fingerprint (hash canónico de: venueId, orderId?, amount_cents, tip_cents, merchantAccountId, kind, splitType). Hoy el backend, al ver un `idempotencyKey` repetido, **devuelve el Payment existente sin comparar nada** (`payment.tpv.service.ts:1385`) — un reuso accidental del attemptId (patrón get-or-create de `ensurePaymentAttemptId`, riesgo señalado en split/kiosk) registraría silenciosamente el pago equivocado. Regla: key repetida + fingerprint igual ⇒ dedup normal; key repetida + fingerprint distinto ⇒ **rechazo + alerta** (jamás silencio).

## 6. Guardrails (v1, siguen todos)
NonCancellable post-SDK · verificación instrumentada de `ensurePaymentAttemptId` en split/kiosk ANTES de habilitar esos flujos · **AngelPay prerequisito: leer `result.integratorReference`** (`AngelPayPaymentViewModel:2443-2461`) · la tabla jamás alimenta reportes · sandbox/production byte-idénticos (helper compartido en main/) · migración con test + upgrade real · rollout: flag por venue (`paymentLedgerMode`: OFF|SHADOW|ACTIVE, default OFF, patrón `cellularFailoverMode`) → sombra ≥1 semana → piloto → flota.

## 7. Limitaciones conocidas (aceptadas y explícitas)
1. "100% seguro" no existe; el diseño cierra la ventana conocida y hace visible el resto.
2. Offline-aprobados no son verificables contra el host (por diseño bancario) — ruta especial.
3. Registro tardío a turno YA cerrado no recalcula ese turno (sin `reopenShift`) — el dinero cae correcto por fecha; el corte histórico queda como estaba. Documentado a ops.

## 8. Preguntas abiertas a vendors (bloquean §10, no el resto)
**Edgardo/Blumon:** ¿webhook de reversos (operationType/correlación)? · ¿API server-to-server de captura/liquidación? · ¿`codeResponse='00'` = auth o captura, y ventana de reverso? · ¿momento del disparo del webhook? · ¿llave única estable de una tx LIQUIDADA? · **v2: ¿`TransactionDetails(operationID)` refleja captura/settlement o estado transitorio? ¿not-found puede ser eventual?**
**Rafael/AngelPay:** ¿`getTransactionHistory` = nube o estado local? · semántica de `integratorReference` · keys `whsec_` por comercio (pedidas 2026-07-18, pendientes).

## 9. Verificación (v2 — ampliada)
Suite completa + nuevos: DAO/CAS/estados, recovery (capturado/declinado/no-encontrado-eventual/OFFLINE), dedup+fingerprint, migración. **Drills de hardware: kill del proceso EN CADA FRONTERA durable** (antes de PREPARANDO-commit, entre commit y SDK, durante SDK, entre respuesta del host y HOST_RESPONDIO, tras HOST_RESPONDIO, durante registro, durante enqueue) · carreras worker↔callback↔"Verificar ahora" · cambio de venue/merchant a media verificación · los 8 flujos · las 4 variantes (sandbox/production/nexgo/nexgoProd) · backend multi-instancia (matching concurrente). Sombra ≥1 semana.

## 10. 🔒 P0 ABIERTOS — qué falta para pasar de BORRADOR a LISTO
1. Respuestas de vendors (§8) que fijan las semánticas de verificación (sin ellas, el recovery solo puede prometer cuarentena, no auto-resolución).
2. Verificación instrumentada en dispositivo: `ensurePaymentAttemptId` en split/kiosk (¿reusa ID?) y lectura real de `integratorReference` en Nexgo.
3. Contrato detallado de `recordRecoveredPayment` (backend) + del fingerprint (§5.3–5.4) — diseño fino en el plan del backend.
4. Re-aprobación del founder de esta v2.

---
*v1: workflows `wf_302e3f41-f92` + `wf_15d50026-647` (2026-07-17). v2: incorpora la revisión estática externa del 2026-07-18 (13 hallazgos: ventana HOST_RESPONDIO, snapshot+recordingRoute, estados REGISTRO_FALLIDO/ENTREGADA_A_COLA/INDETERMINADO, no-descartar-por-ausencia, barrera CAS, venueId, lock multi-merchant, matching P0 + ACK durable, recordRecoveredPayment, fingerprint, centavos, UX-nav, QA ampliado). Incidente de referencia: memoria `mindform-orphaned-1400-post-auth-gap`.*
