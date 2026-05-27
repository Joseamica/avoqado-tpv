# Diseño: Unificar mensaje al promotor — "En revisión por Administración"

- **Fecha:** 2026-05-27
- **Plataforma:** TPV (Android) — solo `main/` (sin variantes sandbox/production)
- **Prioridad:** Alta
- **Tarea Asana:** [Unificar mensaje al momento que Administración de ventas revise las ventas](https://app.asana.com/1/12709793723059/project/1213523434401320/task/1215134691431318)
- **Stakeholder:** Isaac Mayoral (Bait × Play Telecom) — **dirección y textos aprobados el 2026-05-27**
- **Roadmap de confirmación:** [`2026-05-27-roadmap-unificar-mensaje-en-revision.html`](./2026-05-27-roadmap-unificar-mensaje-en-revision.html)

---

## 1. Problema

Tras una venta de SIM serializada, el promotor ve el **mismo estado** (`SaleVerification.status = PENDING`) con **dos textos distintos** en dos pantallas:

| Pantalla | Texto actual | Tono |
|----------|--------------|------|
| Inicio (`WelcomeScreen` → `PendingVerificationsCard`) | **"Pendientes Verificacion"** · "X ventas sin verificar" | 🟡 Alarma (amarillo) |
| Mis Ventas (`MySalesScreen` → `VerificationStatusBadge`) | **"En revisión"** | 🟠 Warning |

"Pendientes Verificación / sin verificar" sugiere que **el promotor** tiene una tarea pendiente. En realidad la venta solo está **esperando que Administración la revise** — lo mismo que comunica "En revisión". Es un único estado con dos nombres, y genera confusión.

### Insight clave (validado en backend)

`getPendingVerifications()` (en `avoqado-server`, `src/services/tpv/sale-verification.service.ts`) filtra **solo** por `status: 'PENDING'` — **no** por fotos faltantes. El comentario del backend lo confirma: *"downgrade to PENDING so the sale enters the queue"*.

➡️ **`PENDING` = "esperando revisión de Administración"**, NO "al promotor le faltan fotos". El banner "sin verificar" es engañoso por diseño. Suba foto o no, la venta entra igual a `PENDING`.

El flujo *push* ("Administración avisa qué falta") **ya existe**: al rechazar, Administración elige un `SaleVerificationRejectionReason` (`REVIEW_MISSING_LINKING_IMAGE` = "Falta imagen de vinculación", `REVIEW_PORTABILIDAD` = "Falta imagen de portabilidad", etc.), el estado pasa a `FAILED`, y el promotor ve "Revisar documentación" + "Toca para corregir".

---

## 2. Decisión (confirmada por Isaac)

| # | Pregunta | Respuesta |
|---|----------|-----------|
| 1 | ¿Texto exacto? | **"En revisión por Administración"** |
| 2 | ¿Banner de inicio: re-enmarcar o quitar? | **Re-enmarcar** a informativo (sin alarma) |
| 3 | ¿"Venta correcta" / "Revisar"? | **Sin cambios** |
| 4 | ¿Subtexto explicativo? | **No** |

---

## 3. Modelo de estados (lo que verá el promotor)

```
Promotor vende (con foto o sin foto)
        │
        ▼
  status = PENDING  ───────────▶  "En revisión por Administración"   (único mensaje, informativo)
        │
        ▼  Administración revisa en el dashboard
        ├─ APPROVE → COMPLETED → "Venta correcta"        (sin cambios)
        └─ REJECT  → FAILED    → "Revisar" + corregir     (sin cambios; es el canal "falta esto")
```

---

## 4. Alcance — cambios exactos en el TPV

Solo cambian **textos y el tono** de un componente. **No** cambia lógica, navegación, modelos, ni red.

### 4.1 `WelcomeScreen.kt` → `PendingVerificationsCard` (≈ líneas 1233–1291)

**Texto:**
- `"Pendientes Verificacion"` → `"En revisión por Administración"`
- `"$count venta${...} sin verificar"` → `"$count venta${...} en revisión"`
- `contentDescription = "Ver pendientes"` → `"Ver ventas en revisión"`

**Re-enmarcado de tono — `statusWarning` (🟡 `#F59E0B`) → `statusInfo` (🔵 `#3B82F6`)** en los 4 usos:
- `CardDefaults.cardColors(containerColor = …statusWarning.copy(alpha = 0.12f))` → `statusInfo`
- `Surface(color = …statusWarning.copy(alpha = 0.2f))` → `statusInfo`
- `Icon(tint = …statusWarning)` (ícono principal) → `statusInfo`
- `Icon(tint = …statusWarning)` (flecha) → `statusInfo`

**Ícono:** `Icons.Default.CameraAlt` (sugiere "toma foto / acción") → `Icons.Default.Schedule` (reloj, sugiere "espera/proceso"). Requiere ajustar el import.

> El `Card` sigue siendo `clickable` y navega a `PendingVerificationsScreen` (el promotor puede entrar a ver sus ventas en revisión y, opcionalmente, subir fotos de respaldo). **La navegación no cambia.**

### 4.2 `PendingVerificationsScreen.kt` → `TopAppBar` (≈ línea 201)

- `title = "Pendientes Verificacion"` → `title = "En revisión por Administración"`

### 4.3 `MySalesScreen.kt` → `VerificationStatusBadge` (≈ líneas 383–384)

- Caso `VerificationReviewStatus.PENDING`: label `"En revisión"` → `"En revisión por Administración"`
- `COMPLETED` ("Venta correcta") y `FAILED` ("Revisar documentación") **sin cambios**.

---

## 5. Fuera de alcance (qué NO cambia)

- ❌ Capacidad de subir fotos (en venta o como respaldo en `PendingVerificationsScreen`) — se conserva.
- ❌ Estados finales `COMPLETED` ("Venta correcta") y `FAILED` ("Revisar documentación" + corrección).
- ❌ Lógica, navegación, DTOs, endpoints, Room, modelos.
- ❌ **Backend** (`avoqado-server`) y **Dashboard** (`avoqado-web-dashboard`): ya soportan revisar/aprobar/rechazar con motivos. **Sin cambios.**
- ❌ Toggle "Verificacion" en `KioskAdminBottomSheet` (es *verificación de identidad del cliente*, no relacionado).

---

## 6. Consideraciones de UI (PAX A910S — 360×640dp)

El texto **"En revisión por Administración" (28 car.)** es notablemente más largo que los textos actuales. Validar en dispositivo / `@Preview(widthDp=360, heightDp=640)`:

1. **Badge en Mis Ventas** (`labelSmall`, 11sp, dentro de un pill pequeño): el texto largo puede apretarse o envolver. Verificar que el pill permita el ancho o el wrap sin romper el layout de la tarjeta de venta.
2. **TopAppBar** de `PendingVerificationsScreen`: confirmar que el título no se trunque con "…". Si se trunca, reducir `fontSize` del título o permitir 2 líneas.
3. **Banner de inicio**: el título cabe en una línea (`Column` con `weight(1f)`); confirmar que no empuje la flecha.

> Si en el badge pequeño el texto completo no funciona visualmente, **no** acortarlo por cuenta propia — Isaac aprobó el texto exacto. Documentar el hallazgo y consultar antes de abreviar.

---

## 7. Testing

- `./gradlew compileSandboxDebugKotlin` y `compileProductionDebugKotlin` pasan.
- `./gradlew lint --continue` pasa.
- `@Preview(widthDp=360, heightDp=640)` de las 3 pantallas, con el banner visible, renderiza el texto largo sin truncado ni overflow.
- Prueba manual en PAX A910S (sandbox): vender → ver "En revisión por Administración" en inicio y Mis Ventas; aprobar desde dashboard → "Venta correcta"; rechazar → "Revisar".
- Sin nuevos tests unitarios (solo strings/colores); confirmar que la suite existente sigue verde.

---

## 8. Entrega

- **CHANGELOG.md** (regla #1): registrar bajo `[Unreleased] → Changed` (p. ej. *"Unificado el mensaje de ventas en revisión a 'En revisión por Administración' y re-enmarcado el aviso de inicio a tono informativo"*).
- **Version bump:** PATCH (mejora de UX/textos; el usuario no puede hacer nada nuevo).
- **Cross-repo:** ninguno. Solo APK del TPV (release con firma PAX, 3–5 días).

---

## 9. Riesgos

| Riesgo | Nivel | Mitigación |
|--------|-------|------------|
| Texto largo se trunca/desborda en PAX A910S | Bajo | Previews + prueba en dispositivo; ajustar fontSize/wrap, no el texto |
| Promotor deja de subir fotos al perder la "alarma" | Muy bajo | Fotos casi siempre se suben al vender; si faltan, Administración las pide vía rechazo (flujo existente) |
| Inconsistencia con otros textos "verificación" | Bajo | Auditados: solo aplican estos 3 puntos; el resto son comentarios o features distintas |
```
