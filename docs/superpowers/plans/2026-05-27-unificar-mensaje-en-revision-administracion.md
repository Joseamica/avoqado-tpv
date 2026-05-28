# Unificar mensaje "En revisión por Administración" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el promotor vea un único mensaje — **"En revisión por Administración"** — para toda venta con `status = PENDING`, reemplazando los textos inconsistentes "Pendientes Verificación" y "En revisión", y bajando el tono del aviso de inicio de alarma a informativo.

**Architecture:** Cambio puramente de presentación (Jetpack Compose) en 3 archivos de `main/`. No hay lógica nueva, modelos, red ni Room → **no aplican unit tests**; la verificación es compilación + lint + inspección visual en PAX A910S (360×640dp). Backend y dashboard no se tocan (ya soportan el flujo de revisión/rechazo). Commit único por ser un cambio cohesivo pequeño.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, `MaterialTheme.avoqadoColors` (tema custom). Build con `JAVA_HOME` = Java 23.

**Spec:** [`docs/superpowers/specs/2026-05-27-unificar-mensaje-en-revision-administracion-design.md`](../specs/2026-05-27-unificar-mensaje-en-revision-administracion-design.md)

---

## File Structure

| Archivo | Responsabilidad | Cambio |
|---------|-----------------|--------|
| `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt` | Aviso de inicio (`PendingVerificationsCard`) | Texto + tono (`statusWarning`→`statusInfo`) + ícono (`CameraAlt`→`Schedule`) + quitar import sobrante |
| `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PendingVerificationsScreen.kt` | Pantalla de detalle de ventas en revisión | Título del TopAppBar |
| `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesScreen.kt` | Lista "Mis Ventas" (`VerificationStatusBadge`) | Label del estado `PENDING` |
| `CHANGELOG.md` | Bitácora (regla #1 del repo) | Entrada en `[Unreleased] → Changed` |

**Prep (una vez por sesión de terminal):**
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
```

---

### Task 1: Re-enmarcar el aviso de inicio (`WelcomeScreen.kt`)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt` (import línea 33 + `PendingVerificationsCard` ≈ líneas 1233–1291)

- [ ] **Step 1: Quitar el import de `CameraAlt` (quedará sin usar)**

Eliminar esta línea (≈ línea 33). `Schedule` ya está importado (línea 38), no hay que agregar nada.

```kotlin
import androidx.compose.material.icons.filled.CameraAlt
```

- [ ] **Step 2: Reemplazar el cuerpo completo de `PendingVerificationsCard`**

Buscar la función `PendingVerificationsCard` y reemplazarla por esta versión (cambios: 4× `statusWarning`→`statusInfo`, ícono `CameraAlt`→`Schedule`, 2 textos, y `contentDescription` de la flecha):

```kotlin
@Composable
private fun PendingVerificationsCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.avoqadoColors.statusInfo.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.avoqadoColors.statusInfo.copy(alpha = 0.2f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.avoqadoColors.statusInfo
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "En revisión por Administración",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$count venta${if (count != 1) "s" else ""} en revisión",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Ver ventas en revisión",
                tint = MaterialTheme.avoqadoColors.statusInfo,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
```

- [ ] **Step 3: Compilar para verificar que no hay imports rotos ni errores**

Run: `./gradlew compileSandboxDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Si falla con "unresolved reference: CameraAlt", quedó un uso de `CameraAlt` en el archivo (no debería) o se borró un import equivocado.

---

### Task 2: Título de la pantalla de revisión (`PendingVerificationsScreen.kt`)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PendingVerificationsScreen.kt:201`

- [ ] **Step 1: Cambiar el título del TopAppBar**

Buscar y reemplazar (debería ser único en el archivo):

```kotlin
                title = "Pendientes Verificacion",
```
por
```kotlin
                title = "En revisión por Administración",
```

- [ ] **Step 2: Compilar**

Run: `./gradlew compileSandboxDebugKotlin`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: Badge de estado en "Mis Ventas" (`MySalesScreen.kt`)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesScreen.kt` (≈ líneas 383–384, dentro de `VerificationStatusBadge`)

- [ ] **Step 1: Cambiar el label del caso `PENDING`**

Buscar (incluye contexto para que sea único) y reemplazar solo el string del label:

```kotlin
        VerificationReviewStatus.PENDING -> Triple(
            "En revisión",
            MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.15f),
```
por
```kotlin
        VerificationReviewStatus.PENDING -> Triple(
            "En revisión por Administración",
            MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.15f),
```

> Nota: el COLOR del badge (`statusWarning`) NO cambia aquí — Isaac solo pidió re-enmarcar el aviso de la pantalla de inicio (Task 1), no el badge de Mis Ventas. Solo cambia el texto.

- [ ] **Step 2: Compilar**

Run: `./gradlew compileSandboxDebugKotlin`
Expected: `BUILD SUCCESSFUL`

---

### Task 4: Changelog, verificación integral y commit

**Files:**
- Modify: `CHANGELOG.md`
- Commit: los 4 archivos juntos

- [ ] **Step 1: Leer `CHANGELOG.md` y agregar la entrada**

Leer el inicio del archivo, ubicar `## [Unreleased]`. Bajo una subsección `### Changed` (crearla si no existe, justo debajo de `## [Unreleased]`), agregar:

```markdown
### **Changed**
- **Mensaje de ventas en revisión**: Unificado el texto que ve el promotor para ventas `PENDING` a **"En revisión por Administración"** (antes "Pendientes Verificación" en inicio y "En revisión" en Mis Ventas). El aviso de la pantalla de inicio pasa de tono de alarma (amarillo) a informativo (azul), con ícono de reloj. Sin cambios en "Venta correcta" ni "Revisar documentación".
```

- [ ] **Step 2: Compilar ambas variantes (sandbox + production deben quedar sincronizadas; estos archivos son de `main/`, así que una compilación cubre ambas, pero se valida production por seguridad)**

Run: `./gradlew compileSandboxDebugKotlin compileProductionDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Lint (debe pasar — verifica que no quedó el import sin usar)**

Run: `./gradlew lint --continue`
Expected: `BUILD SUCCESSFUL`, sin nuevos errores. En particular, sin warning `UnusedImports` por `CameraAlt`.

- [ ] **Step 4: Verificación visual en PAX A910S (360×640dp) — texto largo**

"En revisión por Administración" (28 caracteres) es más largo que los textos previos. Instalar y revisar en dispositivo/emulador con specs PAX A910S:

Run: `./gradlew installSandboxDebug`

Luego, con un promotor que tenga al menos 1 venta en `PENDING`, verificar las 3 superficies:
1. **Pantalla de inicio** → el aviso azul informativo dice "En revisión por Administración" / "1 venta en revisión", sin truncar y sin empujar la flecha fuera de pantalla.
2. **Tocar el aviso** → el TopAppBar de la pantalla dice "En revisión por Administración" sin "…" de truncado.
3. **Mis Ventas** → el badge de la venta `PENDING` muestra "En revisión por Administración".

Monitoreo (opcional, sin crashes al navegar):
```bash
adb logcat -c && adb logcat -s MenuViewModel,MySalesViewModel,PendingVerificationsViewModel | grep -iE "verif|revis|error|exception"
```

> Si en el badge pequeño de Mis Ventas o en el TopAppBar el texto se trunca/desborda y se ve mal: **no abreviar el texto** (Isaac aprobó el texto exacto). Documentar el hallazgo con screenshot y consultar antes de cambiar tamaño de fuente o permitir 2 líneas.

- [ ] **Step 5: Commit (único, los 4 archivos)**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt \
        app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PendingVerificationsScreen.kt \
        app/src/main/java/com/jaac/avoqado_tpv/features/serialized_sale/presentation/MySalesScreen.kt \
        CHANGELOG.md
git commit -m "fix(ventas): unifica mensaje del promotor a 'En revisión por Administración'"
```

> Política del repo: **sin** `Co-Authored-By`. Commit solo estos 4 archivos (no incluir el resto del working tree).

---

## Notas de cierre

- **Version bump:** PATCH (mejora de UX/texto; el usuario no gana una capacidad nueva). Se aplica en el release, no en este commit.
- **Cross-repo:** ninguno. Backend y dashboard ya soportan revisar/aprobar/rechazar con motivos.
- **Release:** APK de producción con firma PAX (3–5 días) cuando se decida liberar.

## Self-Review

- **Cobertura del spec:** §4.1 → Task 1; §4.2 → Task 2; §4.3 → Task 3; §8 (changelog/bump) → Task 4; §6 (UI texto largo) → Task 4 Step 4; §7 (testing) → Task 4 Steps 2-4. Sin huecos.
- **Placeholders:** ninguno; todo el código y los comandos están completos.
- **Consistencia de tipos/nombres:** `statusInfo` y `statusWarning` existen en `avoqadoColors` (verificado en `Color.kt`); `Icons.Default.Schedule` ya importado; `VerificationReviewStatus.PENDING` es el caso real del `when`. El texto "En revisión por Administración" es idéntico en las 3 superficies.
