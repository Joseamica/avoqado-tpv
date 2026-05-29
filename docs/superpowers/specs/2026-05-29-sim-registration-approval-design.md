# Aprobación de alta de SIMs + gate de venta serializada

**Fecha:** 2026-05-29
**Repos afectados:** avoqado-server (hub), avoqado-web-dashboard, avoqado-tpv
**Org piloto:** PlayTelecom — `cmietitbn000zpr2d8213qkzq`
**Estado:** Diseño aprobado, pendiente de plan de implementación

---

## 1. Problema y objetivo

Hoy, en el módulo de **inventario serializado** (SIMs), un promotor puede dar de alta
una SIM desde la TPV y **venderla al instante** — incluso una SIM que el sistema nunca
aprobó. El flujo de venta (`SerializedSaleViewModel`) sólo valida `length >= 20`; el guard
estricto de formato mexicano (`^8952\d{15,16}F?$` + Luhn) sólo vive en el flujo de **Alta**
(`SerializedInventoryViewModel`), no en venta. Además, una SIM con `status=not_registered`
hoy ofrece "elegir categoría y vender" en el acto (`registerAndSell`).

**Objetivos:**

1. **Sólo se puede vender una SIM dada de alta y aprobada**, y que esté en custodia del
   promotor (`PROMOTER_HELD`). Más el guard de formato `8952` + 20 dígitos también en venta.
2. Cuando el promotor da de alta SIMs desde la TPV, se genera una **solicitud** que el
   **OWNER (Isaac)** aprueba o rechaza desde el dashboard organizacional.
3. Al aprobar, la SIM entra a la **cadena de custodia existente** y, una vez asignada y
   aceptada, el promotor puede venderla.
4. La ruta alternativa **"Cargar SIM"** (bulk del Owner/Supervisor) ya funciona y debe
   seguir funcionando sin solicitud: carga directa → asignación → venta.

**Restricción dura:** NO romper las variantes actuales del sistema de inventario
serializado (custody chain, Mis SIMs, asignaciones, recolecciones, las 5 tabs de Control
de Stock, bulk upload, $0 / portabilidad, proof-of-sale).

---

## 2. Estado actual (lo que ya existe — NO se modifica salvo lo indicado)

### avoqado-server

- **`SerializedItem`** con máquina de custodia:
  `ADMIN_HELD → SUPERVISOR_HELD → PROMOTER_PENDING → PROMOTER_HELD → PROMOTER_REJECTED → SOLD`
  (`prisma/schema.prisma:7382`, enum `SerializedItemCustodyState:7499`).
- **`SimCustodyService`** (`src/services/serialized-inventory/custody.service.ts`):
  state machine única, eventos append-only (`SerializedItemCustodyEvent`), idempotencia
  (`IdempotencyRequest`), transiciones: `assignToSupervisor`, `assignToPromoter`,
  `assignToPromoterDirect` (bypass OWNER), `accept`, `reject`, `collectFromPromoter`,
  `collectFromSupervisor`, `listMySims`.
- **Gate de venta ya implementado** (`serializedInventory.service.ts:353` `applyCustodyPrecheck`):
  gobernado por `Organization.simCustodyEnforcementMode` (`OFF` | `WARN` | `ENFORCE`,
  default `OFF`, `schema.prisma:75`). En `ENFORCE` lanza `SIM_NOT_ACCEPTED` si la SIM no
  está `PROMOTER_HELD` del promotor. Se invoca desde `ensureSellable` (precheck en
  scan/sell) y `markAsSold` (post-pago, defensa en profundidad). Tiene **floor-version
  safeguard**: TPVs por debajo de `minimumVersionWithMisSims` corren en OFF.
- **Auto-custodia al escanear** (`custodyAssignment.helper.ts` `buildCustodyDataForScanner`):
  hoy un `WAITER` que registra obtiene la SIM directamente en `PROMOTER_HELD` (auto-accept);
  un `MANAGER` la obtiene en `SUPERVISOR_HELD`. **Este es el comportamiento que cambia
  cuando la feature está activa.**
- Rutas TPV: `src/controllers/tpv/simCustody.tpv.controller.ts`,
  `serializedInventory.routes.ts`. Rutas dashboard:
  `simCustody.dashboard.routes.ts`, `stockDashboard.routes.ts`.

### avoqado-web-dashboard

- **`OrgStockControlPage.tsx`** (`src/pages/playtelecom/Organization/`): 5 tabs
  (Resumen, Cargas, Detalle SIMs, Por Sucursal, Por Categoría) + botones "Cargar Items" y
  "Asignar SIMs". Diálogos: `AssignToSupervisorDialog`, `AssignToPromoterDialog`,
  `OrgBulkUploadDialog`, `CollectSimDialog`, `SimTimelineDrawer`.
- Hook de datos: `useOrgStockControl.ts`. Ruta: `/organizations/:orgId/stock-control`
  (`router.tsx:567`).
- Stock por sucursal (sucursal virtual de Isaac): `playtelecom/Stock/StockControl.tsx` +
  `BulkUploadDialog` ("Cargar SIM").

### avoqado-tpv

- **Alta**: `features/serialized_inventory/SerializedInventoryViewModel.kt` — guard estricto
  `MX_ICCID_REGEX = ^8952\d{15,16}F?$` (`:441`) + Luhn (`:460`), validación contra backend
  vía `scanItem` antes de añadir al lote, `registerBatch`.
- **Venta**: `features/serialized_sale/SerializedSaleViewModel.kt` — sólo valida
  `length < 20` (`:131`); `handleScanResult` (`:168`) trata `not_registered` ofreciendo
  selector de categoría + venta on-the-fly; `canProceedToSell` (SerializedSaleState.kt:149)
  habilita vender `NotRegistered` con categoría. Ya maneja `SIM_NOT_ACCEPTED`
  (`:307`, deep-link a Mis SIMs).
- **Mis SIMs / aceptar**: ya existe (no se toca).

---

## 3. Diseño de la solución

### 3.1 Flujo objetivo (las 2 rutas a venta)

**Ruta A — Promotor da de alta desde la TPV (la que cambia):**

```
Promotor escanea SIM en TPV (Alta)
  → guard formato 8952 + 20 díg + Luhn (cliente) + dedup
  → crea SimRegistrationRequest (PENDING)  [NO crea SerializedItem aún]
OWNER (Isaac) ve "Solicitudes (N)" en Control de Stock org
  → aprueba (lote o SIMs seleccionadas) / rechaza con motivo
  → al aprobar: se crea SerializedItem en ADMIN_HELD
OWNER asigna a Supervisor (SUPERVISOR_HELD)        [flujo existente]
Supervisor asigna a Promotor (PROMOTER_PENDING)    [flujo existente]
Promotor acepta en Mis SIMs (PROMOTER_HELD)        [flujo existente]
  → VENDIBLE
```

**Ruta B — Owner/Supervisor carga desde el dashboard (ya funciona, sin cambios):**

```
Isaac "Cargar SIM" en sucursal virtual / "Cargar Items" en org
  → crea SerializedItem en ADMIN_HELD                [flujo existente, ruta confiable]
OWNER asigna desde org → Supervisor → Promotor → acepta → VENDIBLE
```

> Nota: el promotor ya tiene físicamente la SIM cuando la da de alta en la Ruta A. Aun así,
> por decisión de negocio, al aprobar **NO** se le asigna directo: entra en `ADMIN_HELD` y
> recorre la cadena Owner→Supervisor→Promotor. Esto mantiene una sola fuente de verdad de
> custodia y aprovecha 100% el flujo existente sin un atajo nuevo.

### 3.2 Modelo de datos (avoqado-server) — ADITIVO

Dos modelos + dos enums nuevos. **Cero cambios** a `SerializedItem`, `ItemCategory`, ni a la
máquina de custodia. Esto es lo que garantiza no romper las variantes actuales.

```prisma
model SimRegistrationRequest {
  id                    String   @id @default(cuid())
  organizationId        String
  organization          Organization @relation(fields: [organizationId], references: [id], onDelete: Cascade)
  registeredFromVenueId String?  // sucursal desde la que el promotor dio de alta
  registeredFromVenue   Venue?   @relation(fields: [registeredFromVenueId], references: [id])
  requestedByStaffId    String   // promotor (WAITER) que solicitó
  requestedBy           Staff    @relation(fields: [requestedByStaffId], references: [id])
  proposedCategoryId    String?  // categoría sugerida por el promotor
  proposedCategory      ItemCategory? @relation(fields: [proposedCategoryId], references: [id])
  status                SimRegistrationRequestStatus @default(PENDING)
  reviewedByStaffId     String?  // OWNER que revisó
  reviewedAt            DateTime?
  createdAt             DateTime @default(now())
  updatedAt             DateTime @updatedAt
  items                 SimRegistrationRequestItem[]

  @@index([organizationId, status])
  @@index([requestedByStaffId])
}

model SimRegistrationRequestItem {
  id                      String  @id @default(cuid())
  requestId               String
  request                 SimRegistrationRequest @relation(fields: [requestId], references: [id], onDelete: Cascade)
  serialNumber            String  // ICCID canonicalizado (uppercase, trim)
  status                  SimRegistrationItemStatus @default(PENDING)
  rejectionReason         String?
  createdSerializedItemId String? // FK al SerializedItem creado al aprobar (traza)
  createdAt               DateTime @default(now())

  @@unique([requestId, serialNumber])
  @@index([serialNumber])
}

enum SimRegistrationRequestStatus {
  PENDING
  APPROVED   // todos los items aprobados
  REJECTED   // todos los items rechazados
  PARTIAL    // mezcla aprobados/rechazados
}

enum SimRegistrationItemStatus {
  PENDING
  APPROVED
  REJECTED
  DUPLICATE  // ya existía en SerializedItem o en otra solicitud pendiente
}
```

Relaciones inversas a agregar en `Organization`, `Venue`, `Staff`, `ItemCategory`
(arrays opcionales — aditivo).

**Badge de "Solicitudes":** `count(SimRegistrationRequest where organizationId=? and status=PENDING)`.

### 3.3 Backend — endpoints y lógica

**Feature flag de la solicitud.** Se reusa `Organization.simCustodyEnforcementMode`:
- `OFF` → comportamiento legacy (alta crea item vendible; sin solicitud; sin gate).
- `ENFORCE` → feature activa (alta crea solicitud; gate de venta activo).
- (`WARN` se mantiene como hoy: gate sólo loguea; la creación de solicitud se ata a
  `ENFORCE` para no introducir un estado intermedio confuso.)

> Una sola palanca para "feature de solicitud" + "gate de venta" evita estados
> inconsistentes (no tendría sentido exigir aprobación pero permitir vender no-aprobadas).

**(a) Alta — `POST tpv/serialized-inventory/register-batch` (modificado, retrocompatible):**
- Si la org NO está en `ENFORCE` → comportamiento actual intacto.
- Si la org está en `ENFORCE`:
  - Valida formato `^8952\d{15,16}F?$` por cada ICCID (defensa en profundidad).
  - Dedup contra `SerializedItem` (org-unique, case-insensitive) y contra solicitudes
    `PENDING` → marca esos items como `DUPLICATE`.
  - Crea `SimRegistrationRequest` (PENDING) + N `SimRegistrationRequestItem`.
  - **Respuesta retrocompatible** (regla cross-repo: nunca quitar campos): mantiene
    `{ success, data: { created: 0, duplicates: [...], assignedToYou: 0 } }` y **agrega**
    campos opcionales `{ requestId, submitted, pending, mode: "approval" }`. Una TPV vieja
    ignora los nuevos campos y ve `created:0` (no añade stock fantasma).

**(b) Aprobación — dashboard (OWNER/SUPERADMIN), nuevo controlador/servicio:**
- `GET dashboard/organizations/:orgId/sim-registration-requests?status=pending`
  → lista solicitudes con items, promotor, sucursal, categoría, conteo.
- `POST dashboard/organizations/:orgId/sim-registration-requests/:id/approve`
  body opcional `{ serialNumbers?: string[], categoryId: string }`:
  - En una transacción, por cada item a aprobar: crea `SerializedItem` en `ADMIN_HELD`
    (`createdBy = requestedByStaffId`, `registeredFromVenueId`, `categoryId`,
    `organizationId`), marca item `APPROVED` + `createdSerializedItemId`.
  - Re-dedup dentro de la transacción (otra aprobación/carga pudo crear la SIM).
  - Recalcula `request.status` (APPROVED/PARTIAL).
  - Reusa el patrón de idempotencia existente (`IdempotencyRequest`) para evitar doble alta.
- `POST .../:id/reject` body `{ serialNumbers?: string[], reason: string }`:
  - Marca items `REJECTED` + `rejectionReason`; no crea `SerializedItem`.
  - Recalcula `request.status`.
- **Permiso nuevo:** `sim-custody:approve-registration` (OWNER + SUPERADMIN) en
  `avoqado-server/src/lib/permissions.ts` (`PERMISSION_CATEGORIES` + `DEFAULT_PERMISSIONS`)
  y espejo exacto en el dashboard.

**(c) Venta — gate (ya implementado, sólo se activa):**
- `Organization.simCustodyEnforcementMode = ENFORCE` para las orgs objetivo.
- `applyCustodyPrecheck` bloquea con `SIM_NOT_ACCEPTED` si la SIM no es `PROMOTER_HELD` del
  promotor → cubre el requisito "sólo si está dada de alta (y aceptada)".
- **Bloquear `registerAndSell`** (venta de no-registradas on-the-fly) cuando la org está en
  `ENFORCE`: el endpoint de quick-sell devuelve error claro en vez de crear+vender.

### 3.4 TPV (avoqado-tpv)

- **Util compartido de validación ICCID:** extraer `MX_ICCID_REGEX`, `canonicalizeIccid`,
  `isLuhnValid` desde el `companion object` de `SerializedInventoryViewModel` a un util común
  (p.ej. `features/serialized_sale/domain/IccidValidator.kt`) sin cambiar el comportamiento
  del flujo de Alta.
- **Venta — `SerializedSaleViewModel.onBarcodeScanned` (`:123`):** sustituir el guard
  `length < 20` por el guard estricto `8952` + 20 díg + Luhn (warning de confirmación como en
  Alta). Mensaje: "Verifica que el sticker empiece con 8952 (México)…".
- **Venta — `handleScanResult` (`:181`):** con la feature activa, `not_registered` deja de
  ofrecer categoría + venta on-the-fly → estado de error claro: "Esta SIM no está dada de
  alta o no está aprobada para venta." (sin botón de vender).
- **`canProceedToSell` (`SerializedSaleState.kt:149`):** quitar la rama
  `NotRegistered + selectedCategory` cuando la feature está activa (sólo `Available` propio).
- **Alta — `SerializedInventoryViewModel`:** copy de éxito → "Solicitud enviada. Pendiente de
  aprobación." Mantiene el guard de formato actual.
- **Versionado:** bump **MINOR** (el promotor tiene un comportamiento nuevo: solicitud +
  bloqueo de venta no aprobada). `CHANGELOG.md` bajo `[Unreleased]`.

### 3.5 Dashboard (avoqado-web-dashboard)

- **Nueva tab "Solicitudes"** (6ª) en `OrgStockControlPage.tsx`, con **badge numérico** =
  solicitudes `PENDING`. Visible/accionable sólo para OWNER/SUPERADMIN
  (`can('sim-custody:approve-registration') || isSuperOrOwner`).
- **Tabla:** `Promotor · Sucursal · Categoría propuesta · # SIMs · Fecha · Acciones`.
  Fila expandible → lista de ICCIDs (`IccidBadge`), checkbox por SIM.
  - Botón **Aprobar** (todo el lote o sólo seleccionadas).
  - Botón **Rechazar** → modal con motivo (texto requerido).
  - Estados visibles: PENDING/APPROVED/REJECTED/PARTIAL/DUPLICATE.
- **Hook** `useSimRegistrationRequests(orgId)` + mutations approve/reject con invalidación de
  query (la tabla y el badge se refrescan; "Detalle SIMs" muestra los nuevos `ADMIN_HELD`).

---

## 4. Rollout, migración y orden de deploy

1. **Migración Prisma:** sólo agrega 2 tablas + 2 enums + relaciones inversas opcionales.
   Sin backfill obligatorio, sin riesgo a datos existentes.
2. **Audit de seguridad ANTES de prender `ENFORCE` global** (⚠️ punto crítico): el gate y la
   auto-custodia (`WAITER → PROMOTER_HELD`) hoy aplican a cualquier org con módulo
   serializado. Antes de activar `ENFORCE` de forma global hay que verificar que ninguna org
   no-PlayTelecom tenga inventario `AVAILABLE` que se venda fuera de `PROMOTER_HELD`. Si
   aparece, o se hace backfill de custodia o se acota la activación a las orgs objetivo.
   **El audit decide si "global" es seguro; si no, se activa por org.**
3. **Orden de deploy (regla cross-repo):**
   - **Backend primero**: migración + endpoints de aprobación + `register-batch` gated +
     bloqueo de `registerAndSell` + activar `ENFORCE` en orgs objetivo. Soporta TPVs viejas
     (floor-version safeguard ya existente + respuesta retrocompatible).
   - **Dashboard** (deploy en minutos): tab Solicitudes.
   - **TPV APK** (3-5 días por firma PAX): guard de venta + bloqueo `not_registered` + copy.
   - Backend soporta TPV vieja Y nueva ~1 semana.

---

## 5. Estrategia de pruebas

**Backend:**
- `register-batch` en `ENFORCE` crea solicitud (no item); en `OFF` mantiene comportamiento.
- Respuesta retrocompatible (`created:0`, `duplicates`, campos nuevos opcionales).
- Aprobar → `SerializedItem` en `ADMIN_HELD`, item `APPROVED`, `createdSerializedItemId`
  poblado; recálculo de `request.status`.
- Rechazar con motivo → item `REJECTED`, sin item creado.
- Dedup contra `SerializedItem` y solicitudes pendientes (→ `DUPLICATE`).
- Permiso `sim-custody:approve-registration` (sólo OWNER/SUPERADMIN).
- Idempotencia de aprobación (doble request no duplica items).
- `ENFORCE` bloquea venta de SIM no-`PROMOTER_HELD` (`SIM_NOT_ACCEPTED`); `registerAndSell`
  bloqueado en `ENFORCE`.

**TPV (unit, `testSandboxDebugUnitTest`):**
- Guard `8952`/20-díg/Luhn en `SerializedSaleViewModel` (acepta válidos, rechaza no-8952,
  cortos, con basura; warning Luhn).
- `not_registered` con feature activa → estado de error, `canProceedToSell == false`.
- Regresión: el flujo de Alta no cambia su validación.

**Dashboard:**
- Render de la tab + badge con conteo.
- Aprobar lote / aprobar selección / rechazar con motivo → invalidación de query.
- Gating de visibilidad por rol.

---

## 6. Lo que explícitamente NO se hace (YAGNI / anti-romper)

- No se modifica `SerializedItem` ni la máquina de custodia.
- No se cambia "Cargar SIM" / bulk upload (Ruta B) — ya funciona.
- No se cambian Mis SIMs, asignaciones, recolecciones, ni las 5 tabs existentes.
- No hay atajo de "aprobar y asignar directo al promotor" (se respeta la cadena
  Owner→Supervisor→Promotor por decisión de negocio).
- No se introduce notificación nueva fuera del patrón `notifySimCustody` existente (se puede
  reusar para avisar al promotor cuando su solicitud es aprobada/rechazada — opcional, fase 2).

---

## 7. Decisiones tomadas (resumen)

| Tema | Decisión |
|------|----------|
| Granularidad de aprobación | Lote + por-SIM (Isaac aprueba 1 o varias) |
| Ruta al aprobar | SIM → `ADMIN_HELD` → Owner→Supervisor→Promotor→acepta |
| Quién aprueba | Sólo OWNER (+ SUPERADMIN) |
| Rechazo | Sí, con motivo |
| Modelo de datos | Nuevo (aditivo): `SimRegistrationRequest` + `SimRegistrationRequestItem` |
| Gate de venta | `ENFORCE` (global, con audit previo de seguridad) |
| Validación TPV venta | Agregar guard `8952` + 20 díg + Luhn (hoy sólo `length>=20`) |
| Palanca de feature | `Organization.simCustodyEnforcementMode = ENFORCE` |
