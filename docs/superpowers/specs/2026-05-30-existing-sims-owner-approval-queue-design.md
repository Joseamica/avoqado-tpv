# Cola de aprobación de SIMs existentes (OWNER 1×1 → almacén)

**Fecha:** 2026-05-30
**Repos:** avoqado-server (backend + migración) · avoqado-web-dashboard (UI)
**Org:** PlayTelecom — `cmietitbn000zpr2d8213qkzq`
**Estado:** ✅ APROBADO por Isaac ("Go!") 2026-05-30 — construyendo

## Decisiones finales (confirmadas por Isaac)
1. **Regla por ORIGEN:** solo lo cargado en **Virtual** (`cmnv_virtual_playtelecom`) es confiable/vendible.
   Todo lo **no-Virtual** (sucursales físicas) requiere aprobación de Isaac — **incluso los 1,501 que ya
   están con promotor vendiéndose hoy** (se frenan).
2. Aprobar = **1×1 + también bulk/por-sucursal** (7,861 a mano uno por uno es inviable).
3. **Solo botón Aprobar** por ahora (Rechazar = fase 2).

## Números reales prod (2026-05-30)
- 🟢 Virtual confiable: **1,113** (335 vendiendo + 778 en cadena) — NO requieren aprobación.
- 🟠 No-Virtual a la cola: **7,861** (1,501 vendiendo→se frenan + 6,360 ya bloqueados).
- Venue Virtual id: `cmnv_virtual_playtelecom`. Gate actual: `serializedInventory.service.ts:388`.

---

## 1. Qué pide Isaac

Los **7,138 SIMs que ya existen** y aún no están vendibles deben pasar por su aprobación
**manual, 1×1**. Al **aprobar**, el SIM **se va al almacén** (`ADMIN_HELD`), y de ahí sigue la
cadena de hoy (Owner/Supervisor asigna → Supervisor → Promotor → acepta → vendible).

Esto es **distinto** de la tab "Solicitudes" actual (esa es para altas NUEVAS desde la TPV).
Aquí se trata de inventario que **ya existe** en distintos estados.

## 2. Situación actual (prod, verificado)

De PlayTelecom, `status=AVAILABLE`:

| custodyState | Cantidad | ¿Hoy? |
|---|---:|---|
| PROMOTER_HELD | 1,836 | ✅ Vendible — se respeta (queda "ya aprobado") |
| ADMIN_HELD (almacén) | 4,739 | 🔒 Bloqueado por ENFORCE → **entra a la cola** |
| SUPERVISOR_HELD | 2,360 | 🔒 Bloqueado → **entra a la cola** |
| PROMOTER_PENDING | 38 | 🔒 Bloqueado → **entra a la cola** |
| PROMOTER_REJECTED | 1 | 🔒 Bloqueado → **entra a la cola** |
| **Total a la cola** | **7,138** | |

## 3. Diseño (aditivo, mínimo, reversible)

### 3.1 Esquema — 1 columna nueva en `SerializedItem` (NO toca el enum de custodia)

```prisma
// En model SerializedItem (additive, default no rompe nada existente)
requiresOwnerApproval Boolean   @default(false)
ownerApprovedAt       DateTime?
ownerApprovedById     String?
```

- `default(false)` = comportamiento intacto para todas las orgs y para los items que YA
  son vendibles (no requieren nada). Cero impacto fuera de PlayTelecom.
- La máquina de estados de custodia (`SerializedItemCustodyState`) **no se modifica** — la
  bandera es ortogonal al `custodyState`.

### 3.2 Migración de datos (1 UPDATE, no destructivo)

```sql
-- Marca los 7,138 como "pendientes de aprobación". NO cambia su custodyState:
-- siguen donde están (almacén/supervisor) pero aparecen en la cola y siguen
-- bloqueados por ENFORCE. Reversible (poner requiresOwnerApproval=false).
UPDATE "SerializedItem" si
SET "requiresOwnerApproval" = true
WHERE si."organizationId" = 'cmietitbn000zpr2d8213qkzq'   -- (o vía venue.organizationId)
  AND si.status = 'AVAILABLE'
  AND si."custodyState" <> 'PROMOTER_HELD';
```

> Decisión tomada: **todos** los no-vendibles entran a la cola, incluidos los 2,360 que ya
> están con un supervisor. ⚠️ Consecuencia: al aprobarlos, se mueven a `ADMIN_HELD` y se
> **limpia** su `assignedSupervisorId` (vuelven al almacén). Es lo que Isaac pidió
> ("se van a almacén y ya después se asignan a supervisor").

### 3.3 Backend — endpoints (OWNER, permiso `sim-custody:approve-registration`)

Bajo `/api/v1/dashboard/organizations/:orgId/`:
- `GET  /pending-stock-approvals?cursor=&limit=&search=&venueId=` — lista paginada de
  `SerializedItem` con `requiresOwnerApproval = true` (ICCID, categoría, sucursal de
  registro, custodia actual). Paginado (son miles).
- `GET  /pending-stock-approvals/count` — para el badge.
- `POST /pending-stock-approvals/approve` body `{ serializedItemIds: string[] }` —
  por cada item: `requiresOwnerApproval=false`, `custodyState='ADMIN_HELD'`, limpia
  `assignedSupervisorId/assignedPromoterId/...At`, set `ownerApprovedAt/ById`, escribe
  `SerializedItemCustodyEvent` (traza). Soporta 1 (1×1) o varios (bulk).
- `POST /pending-stock-approvals/reject` body `{ serializedItemIds, reason }` (opcional,
  fase 2) — marca como `DAMAGED`/`RETURNED` o deja flag; a definir si se necesita.

### 3.4 Gate de venta (refuerzo, ya cubierto por ENFORCE)

`applyCustodyPrecheck` ya bloquea todo lo no-`PROMOTER_HELD`. Añadir además: si
`requiresOwnerApproval = true` → `SIM_NOT_ACCEPTED` (defensa en profundidad, por si algún
item quedara PROMOTER_HELD + pendiente por una asignación rara).

### 3.5 Dashboard — UI

En `OrgStockControlPage`, la tab **"Solicitudes"** muestra DOS secciones (o una tab nueva
"Pendientes de aprobación", a decidir en UI):
1. **Inventario pendiente de aprobación** (lo nuevo) — tabla paginada de los 7,138:
   `ICCID · Categoría · Sucursal · Custodia actual · [Aprobar]`. Checkbox por fila +
   "Aprobar seleccionados" (bulk) y "Aprobar todos los de esta sucursal/categoría"
   (atajos, porque 7,138 a uno por uno a mano no es realista — pero el botón 1×1 existe).
2. **Altas nuevas desde TPV** (lo que ya existe) — `SimRegistrationRequest`.

Badge = count de pendientes (existentes + altas nuevas).

## 4. Rollout (orden seguro)

1. Backend: migración aditiva (columna) + endpoints + refuerzo de gate. Deploy.
2. **Migración de datos**: el UPDATE de §3.2 (marca los 7,138). Reversible.
3. Dashboard: UI de la cola. Deploy.
4. Verificar: la cola muestra 7,138; aprobar 1 mueve a almacén y baja el contador.

ENFORCE ya está activo, así que durante todo el proceso **nadie puede vender** los 7,138.

## 5. Riesgos

| Riesgo | Mitigación |
|---|---|
| Mover 7,138 filas en prod | UPDATE acotado por org + status + custodyState; reversible (flag→false) |
| Aprobar saca 2,360 de sus supervisores | Es lo pedido; queda en `SerializedItemCustodyEvent` para auditoría |
| 7,138 a mano es inviable | UI incluye bulk + filtros por sucursal/categoría además del 1×1 |
| Romper otras orgs | Columna `default(false)`; migración solo PlayTelecom; gate solo en ENFORCE |

## 6. Decisiones tomadas
- Alcance: **todos los no-vendibles (7,138)**, incluidos los que ya tienen supervisor.
- Aprobar → `ADMIN_HELD` (almacén), limpiando asignaciones previas.
- Los 1,836 `PROMOTER_HELD` se respetan (no entran, siguen vendibles).
- Modelo: bandera aditiva en `SerializedItem`, NO nuevo estado de custodia.

## 7. Pendiente de confirmar contigo
- ¿La cola va como **sección dentro de "Solicitudes"** o como **tab nueva** "Pendientes"?
- ¿Necesitas botón **Rechazar** en esta cola, o solo Aprobar? (puede ser fase 2)
- ¿El 1×1 es estricto, o está bien ofrecer también **bulk/por-sucursal** para no morir
  aprobando 7,138 a mano?
