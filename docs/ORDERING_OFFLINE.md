# Ordering Offline Behavior (Quick Order + Table Service)

> Objetivo: dejar claro que partes funcionan offline, que partes requieren red, y como se comporta el sync en conexiones lentas.

---

## 1) Quick Order (Pedido Rapido)

**Funciona offline si hay cache de productos**
- Los productos y categorias se cargan cache-first desde Room.
- Si el cache existe, el menu se muestra de inmediato aunque la red este lenta.
- Si NO hay cache (primer arranque o venue nuevo), el menu puede estar vacio hasta que responda el backend.

**Acciones locales (no requieren red inmediata)**
- Agregar productos al pedido (Room primero, UI instantanea).
- Cambiar cantidades / eliminar items (soft delete local).
- Totales locales (sin impuestos ni descuentos backend).

**Orden estable de productos**
- El menu se ordena por `categoryId` + `displayOrder` + nombre para evitar reordenamientos.
- El refresh de backend no debe cambiar el orden visible si no hay cambios reales.
- El scroll del grid **no se resetea** con refresh de backend (solo al cambiar filtro/busqueda).

**Merge de items iguales**
- Si agregas el MISMO producto con los mismos modificadores y notas, se **fusiona** en una sola linea y sube la cantidad.
- Si el item ya fue enviado a cocina, **NO** se fusiona (se crea un nuevo item para nueva comanda).
- La UI se actualiza desde **Room (SSOT)** para evitar “saltos” o duplicados temporales.

**Orden estable de items (line_position)**
- Cada item local tiene `linePosition` persistente.
- La UI ordena por `linePosition` para que el orden visual **no cambie** al sincronizar.
- Al cachear desde backend se preserva `linePosition` si el item ya existia localmente.

**Idempotencia por item (externalId)**
- Cada linea local tiene `externalId` estable.
- El backend **upserta por externalId** para que reintentos no dupliquen items.

**UI: conteo en Cuenta**
- El badge de la pestaña **Cuenta** muestra **unidades** (suma de cantidades).
- En el header se muestran **líneas** y **unidades** para evitar confusión.

**Acciones que SI requieren red**
- Barcode quick add (busqueda de producto por codigo de barras es backend).
- Descuentos % recalculados por backend.
- Enviar a cocina, pagos, comp/void, etc.

---

## 2) Table Service / Floor Plan

**Cache-first (offline-friendly)**
- Mesas y elementos del plano se cachean en Room.
- Si la red falla, se muestra el ultimo estado conocido.

**Limitaciones offline**
- Asignar mesa / crear orden en mesa es online-only.
- Si no hay cache, el plano no puede mostrarse (muestra error).

---

## 3) Sync Local-First (Ordenes)

**Debounce seguro**
- El debounce solo cancela la espera, nunca el sync en vuelo.
- Cambios mientras sync corre se marcan como `dirty`.
- Al terminar un sync, si `dirty=true` se programa otro sync.

**Auto-resolve de conflictos (409)**
- Si otra terminal cambia la orden, se refresca desde backend y se re‑aplican cambios locales.
- Si no hay cambios locales pendientes, se evita mostrar banner de conflicto.

**Reconexion**
- Cuando la conexion regresa, se re-sincronizan ordenes PENDING.

---

## 4) Que esperar en red lenta

- La UI no debe "parpadear" ni resetearse si hay cache local.
- Los contadores de items (CheckTab) no deben bajar/subir por conflictos de version.
- Si el backend falla, se mantiene el ultimo estado conocido y se muestra snackbar.
- Los refresh de productos por socket se **throttle** (no spam de refresh).
- Si el usuario no esta en la pestaña **Menú**, el refresh se **difiere** y se aplica al volver.

---

## 5) Logs recomendados

```bash
adb logcat -c && adb logcat -s OrderSyncCoordinator,MenuViewModel,FloorPlanViewModel,TableServiceViewModel
```

Campos clave en logs:
- syncRunId, localVersion, serverVersion
- dirty=true/false
- debounceScheduledAt
- trigger (debounce / immediate / reconnect)
