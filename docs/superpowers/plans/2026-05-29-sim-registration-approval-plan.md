# Plan de implementación — Aprobación de alta de SIMs + gate de venta

**Spec:** `docs/superpowers/specs/2026-05-29-sim-registration-approval-design.md`
**Fecha:** 2026-05-29
**Repos:** avoqado-server → avoqado-web-dashboard → avoqado-tpv (en ese orden de deploy)

> Cada fase es independiente y verificable. Marca cada checkbox al completarlo.
> Las fases del backend (1-4) deben terminar y desplegarse antes de tocar el APK (fase 6).

---

## Fase 0 — Audit de seguridad previo (BLOQUEANTE para `ENFORCE` global)

**Objetivo:** confirmar que activar `simCustodyEnforcementMode=ENFORCE` no rompe orgs
no-PlayTelecom. Sin esto NO se activa el gate.

- [ ] **0.1** Query de inventario en riesgo: SIMs `status=AVAILABLE` cuyo `custodyState != PROMOTER_HELD`
      agrupadas por `organizationId`, para todas las orgs con módulo serializado activo.
- [ ] **0.2** Para cada org con inventario en riesgo, decidir: (a) backfill de custodia, o
      (b) excluir esa org del `ENFORCE` (activar por-org en lugar de global).
- [ ] **0.3** Registrar la decisión en el spec (§4.2). Si hay riesgo no resuelto → activar
      `ENFORCE` **solo** en `cmietitbn000zpr2d8213qkzq` (PlayTelecom) en la fase 5.

**Verificación:** documento con la lista de orgs y la decisión por cada una.

---

## Fase 1 — Modelo de datos (avoqado-server)

**Archivos:** `prisma/schema.prisma`, nueva migración.

- [ ] **1.1** Agregar enums `SimRegistrationRequestStatus` (PENDING|APPROVED|REJECTED|PARTIAL)
      y `SimRegistrationItemStatus` (PENDING|APPROVED|REJECTED|DUPLICATE).
- [ ] **1.2** Agregar modelo `SimRegistrationRequest` (ver spec §3.2): `organizationId`,
      `registeredFromVenueId?`, `requestedByStaffId`, `proposedCategoryId?`, `status`,
      `reviewedByStaffId?`, `reviewedAt?`, timestamps. Índices `[organizationId, status]`,
      `[requestedByStaffId]`.
- [ ] **1.3** Agregar modelo `SimRegistrationRequestItem`: `requestId`, `serialNumber`,
      `status`, `rejectionReason?`, `createdSerializedItemId?`, `createdAt`.
      `@@unique([requestId, serialNumber])`, índice `[serialNumber]`.
- [ ] **1.4** Agregar relaciones inversas **opcionales** en `Organization`, `Venue`, `Staff`,
      `ItemCategory` (arrays). Nada obligatorio → migración aditiva.
- [ ] **1.5** `npx prisma migrate dev --name sim_registration_requests` y `prisma generate`.

**Verificación:**
- [ ] `npx prisma validate` pasa.
- [ ] `npm run build` (typecheck) pasa.
- [ ] La migración SQL solo contiene `CREATE TABLE` / `CREATE TYPE` (cero `ALTER`/`DROP` sobre
      tablas existentes salvo FKs nuevas opcionales).

---

## Fase 2 — Servicio de solicitudes (avoqado-server)

**Archivo nuevo:** `src/services/serialized-inventory/simRegistration.service.ts`.

- [ ] **2.1** `isApprovalModeEnabled(organizationId)`: lee `Organization.simCustodyEnforcementMode`,
      devuelve `true` cuando `=== 'ENFORCE'`. (Una sola palanca, ver spec §3.3.)
- [ ] **2.2** `createRequest({ organizationId, requestedByStaffId, registeredFromVenueId, proposedCategoryId, serialNumbers })`:
      - Normaliza (`normalizeSerial`) + valida formato `^8952\d{15,16}F?$` por ICCID.
      - Dedup contra `SerializedItem` (org-unique, case-insensitive) y solicitudes `PENDING`
        → marca esos items `DUPLICATE`.
      - Crea `SimRegistrationRequest` (PENDING) + N items en transacción.
      - Devuelve `{ requestId, submitted, pending, duplicates }`.
- [ ] **2.3** `listPending(organizationId)`: solicitudes con items, promotor, sucursal,
      categoría, conteos. Orden por `createdAt` asc.
- [ ] **2.4** `approve({ organizationId, requestId, reviewedByStaffId, serialNumbers?, categoryId })`:
      - Transacción: por cada item a aprobar (todos o subset), re-dedup, crea `SerializedItem`
        en `ADMIN_HELD` (`createdBy=requestedByStaffId`, `registeredFromVenueId`, `categoryId`,
        `organizationId`), marca item `APPROVED` + `createdSerializedItemId`.
      - Recalcula `request.status` (APPROVED/PARTIAL). Setea `reviewedByStaffId`, `reviewedAt`.
      - Reusa patrón `IdempotencyRequest` para no duplicar altas.
- [ ] **2.5** `reject({ organizationId, requestId, reviewedByStaffId, serialNumbers?, reason })`:
      marca items `REJECTED` + `rejectionReason`; recalcula `request.status`.
- [ ] **2.6** `countPending(organizationId)` para el badge.

**Verificación:** unit tests de Fase 7 verdes para este servicio.

---

## Fase 3 — Wiring de Alta + bloqueo de venta on-the-fly (avoqado-server)

**Archivos:** `src/services/serialized-inventory/serializedInventory.service.ts`,
`src/controllers/tpv/serializedInventory.tpv.controller.ts`.

- [ ] **3.1** En el handler de `POST tpv/serialized-inventory/register-batch`: si
      `isApprovalModeEnabled(orgId)` → delega a `simRegistration.createRequest()` en lugar de
      `registerBatch()`. Si no, comportamiento actual intacto.
- [ ] **3.2** Respuesta retrocompatible (regla cross-repo): mantener
      `{ success, data: { created: 0, duplicates, assignedToYou: 0 } }` y **agregar** opcionales
      `{ requestId, submitted, pending, mode: 'approval' }`. TPV vieja ve `created:0`.
- [ ] **3.3** Bloquear `registerAndSell` (venta de no-registradas) cuando
      `isApprovalModeEnabled`: el endpoint de quick-sell devuelve error claro
      (`SIM_NOT_REGISTERED` / 422) en vez de crear+vender.
- [ ] **3.4** Confirmar que `ensureSellable`/`markAsSold` ya cubren el gate `PROMOTER_HELD`
      (ya implementado — solo verificar, no reescribir).

**Verificación:**
- [ ] Con org en `OFF`: `register-batch` crea items (comportamiento legacy) — sin regresión.
- [ ] Con org en `ENFORCE`: `register-batch` crea solicitud, `data.created==0`, `requestId` presente.

---

## Fase 4 — Endpoints de aprobación + permisos (avoqado-server)

**Archivos nuevos/editados:** `src/controllers/dashboard/simRegistration.dashboard.controller.ts`,
`src/routes/dashboard/simRegistration.dashboard.routes.ts`, `src/lib/permissions/` (índice).

- [ ] **4.1** Permiso nuevo `sim-custody:approve-registration` (OWNER + SUPERADMIN) en
      `PERMISSION_CATEGORIES` + `DEFAULT_PERMISSIONS` (ubicar archivo real bajo
      `src/lib/permissions/` — el `permissions.ts` re-exporta desde `./permissions/index`).
- [ ] **4.2** `GET dashboard/organizations/:orgId/sim-registration-requests?status=pending`
      → `listPending` (gated por permiso).
- [ ] **4.3** `POST .../:id/approve` body `{ serialNumbers?, categoryId }` → `approve`.
- [ ] **4.4** `POST .../:id/reject` body `{ serialNumbers?, reason }` → `reject` (reason requerido).
- [ ] **4.5** `GET .../sim-registration-requests/count` (o incluir count en el dashboard de stock).
- [ ] **4.6** Aplicar `checkPermission('sim-custody:approve-registration')` + tenant isolation
      (org del actor === `:orgId`) en todas las rutas.

**Verificación:**
- [ ] `npm run build` pasa. Tests de Fase 7 (controlador/permisos) verdes.
- [ ] Llamada sin permiso → 403; con OWNER → 200.

---

## Fase 5 — Activar `ENFORCE` (avoqado-server, deploy)

- [ ] **5.1** Según decisión de Fase 0: setear `simCustodyEnforcementMode='ENFORCE'` en las orgs
      objetivo (global si el audit lo permite; si no, solo `cmietitbn000zpr2d8213qkzq`).
- [ ] **5.2** Deploy backend a producción. Verificar floor-version safeguard activo
      (`minimumVersionWithMisSims`) para que TPVs viejas sigan en OFF.

**Verificación:** smoke test con TPV vieja (no rompe) + endpoints de aprobación responden.

---

## Fase 6 — TPV (avoqado-tpv)

**Pre:** backend (1-5) desplegado y estable. Bump **MINOR** + `CHANGELOG.md`.

- [ ] **6.1** Extraer validación ICCID a util compartido
      `features/serialized_sale/domain/IccidValidator.kt`: `MX_ICCID_REGEX`,
      `canonicalizeIccid`, `isLuhnValid` (movidos desde `SerializedInventoryViewModel`
      companion, sin cambiar comportamiento de Alta). Actualizar Alta para usar el util.
- [ ] **6.2** `SerializedSaleViewModel.onBarcodeScanned` (`:123`): reemplazar guard
      `length < 20` por guard estricto `8952` + 20 díg + Luhn (con confirmación de warning
      Luhn como en Alta).
- [ ] **6.3** `handleScanResult` (`:181`): `not_registered` → estado de error
      ("Esta SIM no está dada de alta o no está aprobada para venta"), sin selector de
      categoría ni venta on-the-fly.
- [ ] **6.4** `SerializedSaleState.canProceedToSell` (`:149`): quitar la rama
      `NotRegistered + selectedCategory` (solo `Available` propio habilita venta).
- [ ] **6.5** `SerializedInventoryViewModel` (Alta): copy de éxito →
      "Solicitud enviada. Pendiente de aprobación." Manejar la nueva respuesta de
      `register-batch` (`mode: 'approval'`, `submitted`, `pending`).
- [ ] **6.6** `@Preview(widthDp=360, heightDp=640)` en pantallas tocadas (PAX A910S).
- [ ] **6.7** Sincronizar variantes si aplica (este flujo es `main/`, verificar que no haya
      copia sandbox/production).

**Verificación:**
- [ ] `./gradlew testSandboxDebugUnitTest` (tests de Fase 7) verde.
- [ ] `./gradlew compileSandboxDebugKotlin` + `lint --continue` pasan.
- [ ] ADB: escanear SIM no aprobada → bloqueo; SIM `PROMOTER_HELD` → vende.

---

## Fase 7 — Dashboard (avoqado-web-dashboard)

**Archivos:** `OrgStockControlPage.tsx`, nueva tab + componentes + hook.

- [ ] **7.1** Hook `useSimRegistrationRequests(orgId)` + mutations `approve`/`reject` con
      invalidación de query (refresca tabla, badge y "Detalle SIMs").
- [ ] **7.2** Tab "Solicitudes" (6ª) en `TABS` con **badge numérico** = solicitudes PENDING.
      Visible/accionable solo OWNER/SUPERADMIN
      (`can('sim-custody:approve-registration') || isSuperOrOwner`).
- [ ] **7.3** Componente `OrgSolicitudesTab`: tabla
      `Promotor · Sucursal · Categoría · # SIMs · Fecha · Acciones`; fila expandible con
      ICCIDs (`IccidBadge`) + checkbox por SIM.
- [ ] **7.4** Acción **Aprobar** (lote o seleccionadas) y **Rechazar** (modal con motivo
      requerido). Estados visibles: PENDING/APPROVED/REJECTED/PARTIAL/DUPLICATE.
- [ ] **7.5** Espejo del permiso `sim-custody:approve-registration` en el dashboard.

**Verificación:** `npm run build` + lint; render de tab + flujos aprobar/rechazar; gating por rol.

---

## Fase 8 — Pruebas (transversal, escribir junto con cada fase)

**Backend (Jest):**
- [ ] `register-batch`: `ENFORCE` crea solicitud (no item); `OFF` legacy; respuesta retrocompat.
- [ ] `approve` → `SerializedItem` en `ADMIN_HELD`, item `APPROVED`, `createdSerializedItemId`;
      recálculo `status`; idempotencia (doble approve no duplica).
- [ ] `reject` con motivo → item `REJECTED`, sin item creado.
- [ ] Dedup → `DUPLICATE`. Permiso solo OWNER/SUPERADMIN. Tenant isolation.
- [ ] `ENFORCE` bloquea venta no-`PROMOTER_HELD`; `registerAndSell` bloqueado en `ENFORCE`.

**TPV (`testSandboxDebugUnitTest`):**
- [ ] `IccidValidator` (acepta válidos, rechaza no-8952/cortos/basura; warning Luhn).
- [ ] Venta: `not_registered` → error, `canProceedToSell == false`.
- [ ] Regresión: validación de Alta sin cambios.

**Dashboard:** render tab + badge; aprobar lote/selección; rechazar con motivo; gating por rol.

---

## Orden de ejecución y deploy

```
Fase 0 (audit) → Fase 1 (schema) → 2 (servicio) → 3 (alta+bloqueo) → 4 (aprobación+permisos)
  → 5 (activar ENFORCE + DEPLOY BACKEND) → [estable] → Fase 7 (DEPLOY DASHBOARD)
  → Fase 6 (APK TPV, 3-5 días firma PAX)
Fase 8 (tests) se escribe junto con cada fase, no al final.
```

Backend soporta TPV vieja Y nueva ~1 semana (floor-version safeguard + respuesta retrocompat).

---

## Riesgos y mitigaciones

| Riesgo | Mitigación |
|--------|-----------|
| `ENFORCE` global rompe org no-PlayTelecom | Fase 0 audit; activar por-org si hay riesgo |
| TPV vieja deja de vender al activar gate | Floor-version safeguard ya existente + respuesta retrocompat |
| Doble alta por aprobación repetida | `IdempotencyRequest` + re-dedup dentro de la transacción |
| Conteos de stock inflados por solicitudes | Modelo separado: solicitudes NO son `SerializedItem` hasta aprobar |
| Promotor confundido (alta ≠ vendible) | Copy claro en TPV; (fase 2 opcional) notificación al aprobar/rechazar |
