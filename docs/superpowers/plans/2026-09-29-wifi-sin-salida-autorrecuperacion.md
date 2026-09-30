# WiFi «conectado pero sin datos»: la terminal se recupera sola — Implementation Plan (v3 + rulings de la 3.ª auditoría)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que una TPV cuyo WiFi queda enlazado al módem pero sin pasar datos lo detecte (~2 min), lo diga claro y, en `AUTO_ENFORCED`, haga sola lo que arregló a Testarudo —**un reinicio corto del WiFi (apagar ~3 s y prender)**— nunca encima de un cobro y sin poder quedarse con el WiFi apagado.

**Architecture:** Detector puro (`WifiSinSalidaDetector`) + monitor singleton serializado (`WifiSinSalidaMonitor`: sonda a Google con `HttpURLConnection` sin redirecciones, relectura de señales, bitácora y estado del banner) + `ControlDeWifi` (singleton con `Mutex` y `NonCancellable`: guarda de cobro, cuota, marca en disco, apagar → 3 s → prender, verificación del radio). `ConnectionViewModel` pide el reinicio y **se elimina el failover viejo a celular** (nunca corrió en la calle y dejaba el WiFi apagado para siempre sin chip).

**Tech Stack:** Kotlin, Hilt, Coroutines (`Mutex`, `NonCancellable`), `HttpURLConnection`, SharedPreferences, MockK + JUnit4, Jetpack Compose.

**Spec:** la sección «Diseño acordado» de este documento. La v1 y la v2 fueron RECHAZADAS por Codex (gpt-6-astra, xhigh). Casi todos sus P1 venían de la conmutación a celular (WiFi apagado minutos mientras podía haber cobros por el chip). La v3 vuelve a lo que el founder aprobó y a lo que arregló el incidente: **apagar y prender el WiFi**. La tabla «Rulings» dice qué se resolvió y cómo.

---

## Diseño acordado (el spec)

**Incidente (29-sep, Testarudo):** la Nexgo `AVQD-N860W173400` (2.11.0-nexgo-prod, Android 9) pasó 38 min sin mandar nada al servidor (16:51→17:30 CDMX) con el WiFi del local sano: la PAX blanca y la D3 trabajaron por la misma IP pública. Se arregló al apagar y prender el WiFi. Caso hermano: otra Nexgo (17-sep) con `UnknownHostException` en nuestra API **y** en el SDK de AngelPay a la vez ⇒ el enlace del aparato. Memoria: `terminal-dice-sin-internet-comparar-con-hermanas`.

**Mercado (precedente del 29-sep en `../.claude/rules/product-decisions-industry-reference.md`):** Square le pide al vendedor «Toggle off Wi-Fi and then back on». Aquí la app lo hace sola (divergencia deliberada: no controlamos el sistema ni hay cobro offline).

| Modo (`cellularFailoverMode`, por terminal, ya editable en dashboard y superadmin) | Detecta y lo DICE | Evidencia | Reinicia el WiFi |
|---|---|---|---|
| `OFF` / `MANUAL_TOGGLE` (todas hoy) | sí | sí | no |
| `AUTO_SHADOW` | sí | sí + «habría reiniciado el WiFi» | no |
| `AUTO_ENFORCED` | sí | sí | **sí** |

**Reglas (las pruebas las citan):**
1. **WiFi sin salida** = red activa WiFi dada por conectada + servidor caído (histéresis existente de 2 fallos) + socket muerto + la sonda a `https://connectivitycheck.gstatic.com/generate_204` falla **por transporte**, todo sostenido ≥ 60 s. **Cualquier respuesta HTTP** (204, 503, 302) prueba que el enlace pasa datos ⇒ no se toca el WiFi. La sonda NO sigue redirecciones ni reintenta.
2. **Sólo `WIFI_SIN_SALIDA` dispara**, y sólo en `AUTO_ENFORCED`. Se eliminan los disparadores viejos (lentitud, `!hasInternet`, rachas, enfriamiento) y toda la conmutación a celular.
3. **La acción es un reinicio corto:** apagar → esperar que el radio reporte apagado (≤ 5 s) → 3 s → prender → esperar que reporte prendido (≤ 10 s). El WiFi queda apagado segundos, no minutos.
4. **Un solo dueño** (`ControlDeWifi`, singleton + `Mutex`): dos `MainActivity` no reinician a la vez. `NonCancellable`: cerrar la pantalla no deja el reinicio a la mitad.
5. **Nunca encima de un cobro:** la guarda (`CriticalNetworkOperationManager`) ve también la Nexgo (`isCharging`, `isChargeAttemptActive`, `isRefundInFlight`) y se revisa, junto con el modo y las señales, **justo antes de apagar** (sin suspensión de por medio) y otra vez antes del canal DAL de la PAX. Antes de eso, una **sonda EN VIVO** a Google (sin el límite de 30 s) confirma que el enlace sigue muerto: `hasServer` y el socket llegan con atraso, y si el enlace volvió no se toca nada. Por qué no hace falta bloquear el INICIO de cobros: el WiFi queda apagado ~3 s y un cobro que empezara en ese lapso no alcanza a enviar nada al banco (el cliente tiene que presentar la tarjeta); su conexión falla al instante por falta de red. Prender el WiFi ~3 s después no corta conexiones por el chip: Android mantiene la red anterior viva un tiempo para lo que ya estaba abierto.
6. **Marca en disco antes de apagar** (`commit()` en IO que debe devolver `true`; si no, no se apaga). Tras pedir el apagado, **siempre** se pide PRENDER con `forzar = true`, aunque la lectura aún diga prendido: un apagado aceptado y lento se concretaría después, y Android atiende las peticiones en orden, así que la última (prender) gana. La marca se borra sólo al ver `WIFI_STATE_ENABLED` después de ese pedido. Si no se ve, la marca queda y cada evaluación y el arranque vuelven a pedir prender (forzado). No hay limpiezas por antigüedad.
7. **Cuota:** máximo 3 reinicios por hora y ninguno a menos de 60 s del anterior (en memoria del singleton; tras reiniciar la app se reinicia la cuenta: se acepta). Lo de los 60 s evita que una segunda pantalla que esperaba el `Mutex` reinicie encima del que acaba de terminar.
8. **Si tras reiniciar sigue muerto:** el aviso vuelve a «Apaga y prende el WiFi de la terminal, o usa el chip» y la detección vuelve a contar desde cero (otros ≥ 60 s), dentro de la cuota.

**Residuales aceptados (declarados, no se construyen):**
- **Proceso muerto dentro de los ~3 s de apagado y la app no se vuelve a abrir** ⇒ WiFi apagado hasta que alguien la abra (al abrir, se prende). Ventana de segundos; un worker de respaldo sumaba más superficie que riesgo.
- **El toggle manual del `SuperAdminScreen`** (diagnóstico del founder) no pasa por `ControlDeWifi`.
- **Cambio de red WiFi A→B durante la sonda:** la confirmación exige ≥ 60 s de fallo continuo con relectura; un B que también falla 60 s también se reinicia.
- **Carrera de milisegundos** entre la sonda en vivo y el apagado (ruling v3#1).
- **Evidencia no garantizada:** la confirmación y la resolución van por `ObservabilityManager.logWarning` (archivo del aparato + `TerminalLog` best-effort + Crashlytics, que conserva sólo 8 excepciones por sesión). No se construye persistencia propia del incidente.

**Pantalla (todas):** confirmado ⇒ «El WiFi de esta terminal no está pasando datos» / `DETECTADO`: «Apaga y prende el WiFi de la terminal, o usa el chip» · `REINICIANDO`: «Reiniciando el WiFi…». Sin «Reintentar» en esta alerta: el reemplazo genérico («Los cobros se guardan…») mentiría, porque no hay cobro offline.

**Tier:** ninguno (arreglo de un defecto). **Activación:** el modo ya existe por terminal; no hay cambio de servidor, dashboard ni MCP. Los campos `cellularFailoverBadReadingsThreshold`, `cellularFailoverCooldownSeconds` y `cellularFailoverMinCellHoldSeconds` dejan de usarse en la app (siguen en el DTO para no romper el contrato). **Soltado:** el founder pone la Nexgo de Testarudo en «Auto (aplicar)» cuando llegue el APK.

## Las cuatro preguntas «sin red»

1. **Qué ve el cajero:** el aviso de arriba. La tarjeta sigue online-only a propósito. Cae en «degrada y lo DICE», más el reinicio automático en `AUTO_ENFORCED`.
2. **Si el proceso muere entre apagar y prender:** la marca ya está en disco; al abrir la app, se prende (residual declarado si nadie la abre).
3. **Orden de lo encolado:** no se encola nada nuevo.
4. **Vuelve la red y el servidor cambió:** no aplica.

## Rulings de las auditorías de Codex

| Hallazgo (v1/v2) | Estado en v3 |
|---|---|
| Cobro que empieza durante el cambio de red (v1#1, v2#1) | **Cerrado por diseño**: el WiFi queda apagado ~3 s, no minutos (regla 5). Guarda + modo + señales re-chequeados justo antes de apagar y antes del DAL |
| Prender por DAL encima de un cobro por celular (v2#2) | **Cerrado por diseño**: sin permanencia en celular; prender a los ~3 s no corta lo que ya estaba abierto |
| Seguro borrado con apagado pendiente o incierto (v1#2, v2#3) | **Cerrado**: marca sólo se borra con el radio verificado prendido; incierto ⇒ la marca queda (regla 6); la cancelación se propaga en el controlador |
| `commit`/enqueue sin verificar (v1#3) | **Cerrado**: `commit()` en IO debe dar `true`. Ya no hay enqueue (no hay worker) |
| Chip sin datos / reinicio con marca heredada (v1#4, v2#8) | **Cerrado por diseño**: no hay conmutación a celular; con marca al arrancar, se prende |
| `reportNetworkConnectivity` (v1#5) | **Cerrado**: eliminado |
| Señales viejas tras la sonda; modo cambiado (v1#7, v2#5) | **Cerrado**: `Mutex` en el monitor, relectura tras la sonda, y el predicado previo al hardware incluye modo y señales |
| Evidencia de otra red durante la sonda (v2#6) | **Residual declarado** |
| `!hasInternet` dispara solo (v1#8) | **Cerrado**: regla 2 |
| Dueño único; SuperAdmin; cuota (v1#9, v2#4, v2#9) | **Cerrado en parte**: `ControlDeWifi` serializa el automático; SuperAdmin es residual declarado; cuota en memoria (regla 7) |
| 503/redirecciones de la sonda (v1#10, v2#7) | **Cerrado**: `HttpURLConnection` con `instanceFollowRedirects = false` y sin reintentos; cualquier código ⇒ enlace vivo |
| Pruebas verdes por el motivo equivocado (v1#11, v2#13) | **Cerrado**: pruebas del failover viejo eliminadas junto con su código; barrera suspendida para solapar; `coVerifyOrder`; la cancelación se prueba con `runCatching` sobre la llamada |
| WorkManager sin plazo (v1#12) | **Cerrado**: no hay worker |
| Texto tapado por el reintento (v1#13) | **Cerrado**: `textoDelBanner` + alerta sin «Reintentar» |
| Causa envuelta / evidencia (v1#14, v2#14) | **Causa raíz: cerrado.** Durabilidad: residual declarado |
| Receta de hardware (v1#15, v2#15) | **Cerrado**: red WiFi sin salida real; el cobro se abre con red sana y después se quita la salida |
| Disco en Main / esperas bajo el Mutex (v2#10) | **Cerrado**: `commit()` en `Dispatchers.IO`; esperas acotadas (5 s + 3 s + 10 s) |
| Reloj que retrocede (v2#11) | **Cerrado por diseño**: sin plazos largos; la cuota con reloj civil sólo puede equivocarse en la cuenta |
| No compila (v2#12) | **Cerrado**: imports, `Result.Error(ApiException.NetworkError(...))` y `ConnectionRetryState` reales en el plan |
| Mutex del VM sobra (v2#16) | **Cerrado**: no hay Mutex en el VM |
| **v3#1 P1** Carrera entre la última revisión y el apagado asíncrono (devolución sin tarjeta; admisión remota sin bandera) | **Aceptado en parte**: sonda EN VIVO justo antes de apagar (las banderas de servidor/socket llegan con ~30 s de atraso), guarda + modo + señales sin suspensión antes del hardware y del DAL. **No** se agrega exclusión en las rutas de inicio de cobro/devolución: exigiría editar `AngelPayPaymentViewModel`, `PaymentViewModel` (dos variantes) y `RecordAngelPayRefundUseCase` (WIP de otra sesión). **Residual declarado:** una operación financiera que hable con el banco en los milisegundos entre la sonda en vivo (enlace muerto por transporte) y el apagado; con el enlace muerto esa llamada tampoco sale |
| **v3#2 P1** Apagado lento ⇒ marca borrada y WiFi apagado después | **Cerrado**: prender forzado SIEMPRE tras pedir apagar; estados `WIFI_STATE_*`; marca sólo se quita al ver ENABLED; sin limpieza por antigüedad |
| v3#3 P2 El DAL sin modo ni señales | **Cerrado**: `antesDelCanalPax = { !critica… && puedeActuar() }` + prueba |
| v3#4 P2 Prueba «sin internet» por el motivo equivocado | **Cerrado**: red desconectada de verdad + aserción del estado |
| v3#5 P2 Aserciones que no compilan (Truth) | **Cerrado** |
| v3#6 P3 Bitácora con repeticiones | **Cerrado**: `linkedSetOf` |

## Global Constraints

- Repo `avoqado-tpv`, rama `main`, sin ramas ni worktrees. **NO editar** (WIP de reembolsos de otra sesión): `PaymentStateHolder.kt`, `PaymentStateProvider.kt`, `PaymentAttemptDao.kt`, `PaymentAttemptLedger.kt`, `RecordAngelPayRefundUseCase.kt`, `PaymentsViewModel.kt`, `AppModule.kt`. Se LEE `PaymentStateProvider` (`isCharging`, `isChargeAttemptActive` en `HEAD`; `isRefundInFlight` en el árbol compartido, WIP ajeno con default `false`). **Este cambio y el de reembolsos se commitean juntos, o el de reembolsos primero.** Declararlo en el reporte.
- **No commitear** (política del founder). Si alguien commitea: `git commit -- <rutas>`.
- Pruebas con Java 17, por la fila, desde la raíz del workspace:
  `cd /Users/amieva/Documents/Programming/Avoqado && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./scripts/avq-verify.sh avoqado-tpv ./gradlew testSandboxDebugUnitTest --tests "<patrón>"`.
  Veredicto: el cuerpo (`Tests:`, XML de `app/build/test-results/`) y la línea `> Task :app:compileSandboxDebugKotlin` sin sufijo.
- Nombres de prueba sin emoji (`P1`/`P2`). Editar con Edit, nunca reescribir con Python (CRLF).
- Textos exactos: «El WiFi de esta terminal no está pasando datos» · «Apaga y prende el WiFi de la terminal, o usa el chip» · «Reiniciando el WiFi…».
- Constantes: confirmar `60_000L` · sonda cada `30_000L` · sonda: conectar `5_000`, leer `5_000` ms · reinicio: esperar apagado `5_000L`, pausa `3_000L`, esperar prendido `10_000L`, sondeo del radio `250L` (estados `WIFI_STATE_*`) · cuota `3` por `3_600_000L` · reinicio reciente `60_000L`.
- `CHANGELOG.md` → `[Unreleased]` obligatorio.

## Review Focus

1. **Google responde 503 con Avoqado caído** ⇒ nunca se reinicia (Task 3 `cualquier respuesta http prueba que el enlace pasa datos`; Task 4 `google 503 es problema de servidor`).
2. **Cancelar a media transición** ⇒ el reinicio termina y el WiFi queda prendido (Task 5 `P1 reinicio cancelado por el llamador termina y prende`).
3. **Cobro que empieza mientras se escribe la marca** ⇒ no se apaga (Task 5 `P1 cobro que empieza mientras se escribe la marca detiene el reinicio`).
4. **Apagado lento o encendido que no se verifica** ⇒ se pide prender forzado y la marca queda hasta verlo prendido (Task 5 `P1 apagado que no se ve a tiempo igual pide prender forzado`, `encendido no verificado conserva la marca`, `restaurar pide prender forzado y quita la marca al verlo prendido`).
5. **Modo cambiado a OFF durante la sonda** ⇒ no se reinicia (Task 6 `P1 si el modo cambia antes de actuar no se reinicia`).

---

## File Structure

| Archivo (bajo `app/src/main/java/com/jaac/avoqado_tpv/`) | Responsabilidad | Tarea |
|---|---|---|
| `core/util/CriticalNetworkOperationManager.kt` (mod) | La guarda ve la Nexgo | 1 |
| `core/util/WifiFailoverController.kt` (mod) | Propaga cancelación; re-chequeo antes del DAL | 2 |
| `core/util/WifiSinSalida.kt` (nuevo) | Enums + `respuestaExterna` + detector puro | 3 |
| `core/util/WifiSinSalidaMonitor.kt` (nuevo) | Sonda, relectura, bitácora, estado del banner | 4 |
| `core/util/ConnectionStateManager.kt` (mod) | Campo `wifiSinSalida` | 4 |
| `core/util/ControlDeWifi.kt` (nuevo) | Dueño del reinicio + `MarcaDeWifi` | 5 |
| `core/presentation/viewmodels/ConnectionViewModel.kt` (mod) | Pide el reinicio; se borra el failover viejo | 6 |
| `core/presentation/viewmodels/DeviceHealthViewModel.kt` + `core/presentation/components/DeviceAlertBanner.kt` (mod) | Alerta y textos | 7 |
| `CHANGELOG.md` | Registro | 8 |

Pruebas en `app/src/test/java/com/jaac/avoqado_tpv/`, mismo paquete.

---

### Task 1: La guarda «operación crítica» ve la Nexgo

**Files:** Modify `core/util/CriticalNetworkOperationManager.kt` · Create `core/util/CriticalNetworkOperationManagerTest.kt` (test)

**Interfaces:** Consumes `PaymentStateProvider` (ligado en `AngelPayBindingsModule` a `PaymentStateHolder`, singleton sin dependencias). Produces `CriticalNetworkOperationManager(paymentStateProvider)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalNetworkOperationManagerTest {
    private val cobro = mockk<PaymentStateProvider>().also {
        every { it.isCharging() } returns false
        every { it.isChargeAttemptActive() } returns false
        every { it.isRefundInFlight() } returns false
    }
    private val manager = CriticalNetworkOperationManager(cobro)

    @Test fun `sin nada en curso no hay operacion critica`() = assertFalse(manager.isAnyCriticalOperationInProgress())

    @Test fun `P1 cobro de AngelPay cuenta`() {
        every { cobro.isCharging() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `P1 pantalla de cobro trabajando cuenta`() {
        every { cobro.isChargeAttemptActive() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `P1 devolucion de AngelPay en curso cuenta`() {
        every { cobro.isRefundInFlight() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `las banderas de la PAX siguen contando`() {
        manager.setPaymentFlowInProgress(true)
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "*CriticalNetworkOperationManagerTest*"` ⇒ FAIL de compilación (constructor sin parámetros).

- [ ] **Step 3: Implement**

```kotlin
@Singleton
class CriticalNetworkOperationManager @Inject constructor(
    // 🔴 29-sep-2026: sólo la PAX llamaba setPaymentFlowInProgress; un cobro o una devolución de la Nexgo (AngelPay)
    // no contaba, así que cualquier acción que corte la red podía pasar encima. Misma señal que usa AppNavigation
    // para rechazar un cobro remoto.
    private val paymentStateProvider: PaymentStateProvider,
) {
    // … setters sin cambio …
    fun isAnyCriticalOperationInProgress(): Boolean {
        val current = _state.value
        return current.paymentFlowInProgress ||
            current.merchantSwitchInProgress ||
            current.sdkInitializationInProgress ||
            paymentStateProvider.isCharging() ||
            paymentStateProvider.isChargeAttemptActive() ||
            paymentStateProvider.isRefundInFlight()
    }
}
```

Import `com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider`; actualizar el KDoc de la clase.

- [ ] **Step 4:** `grep -rn "CriticalNetworkOperationManager(" app/src --include='*.kt' | grep -v "class CriticalNetworkOperationManager"` ⇒ sólo la prueba nueva.

- [ ] **Step 5: Run** — el mismo comando + `--tests "*SdkTokenRefreshSchedulerTest*"` ⇒ PASS.

---

### Task 2: `WifiFailoverController` propaga la cancelación y re-chequea antes del DAL

**Files:** Modify `core/util/WifiFailoverController.kt` · Create `core/util/WifiFailoverControllerTest.kt` (test)

**Interfaces:** Produces `suspend fun setWifiEnabled(enabled: Boolean, source: String, antesDelCanalPax: () -> Boolean = { true }, forzar: Boolean = false): WifiToggleResult` (los defaults mantienen compilando a `SuperAdminScreen`) y `fun estadoDelRadio(): Int` (= `WifiManager.wifiState`: distingue `WIFI_STATE_ENABLED`/`DISABLED` de las transiciones, cosa que `isWifiEnabled()` no hace). `forzar = true` salta el retorno temprano «ya está en ese estado»: hace falta para pedir PRENDER cuando la lectura todavía dice prendido pero un apagado aceptado sigue pendiente (Codex v3 #2).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiFailoverControllerTest {
    @After fun tearDown() = unmockkAll()

    @Test
    fun `P1 la cancelacion durante la espera no devuelve un resultado`() = runTest {
        val wifi = mockk<WifiManager>(relaxed = true) { every { isWifiEnabled } returns true }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wifi
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        var resultado: WifiToggleResult? = null
        val job = launch { resultado = WifiFailoverController(ctx).setWifiEnabled(enabled = false, source = "prueba") }
        runCurrent()
        advanceTimeBy(500)
        job.cancel()
        runCurrent()

        // Hoy `catch (exception: Exception)` se traga la CancellationException del delay(1500) y ASIGNA un resultado
        // falso (success = false aunque el apagado ya salió hacia Android).
        assertNull(resultado)
    }

    @Test
    fun `P1 forzar pide prender aunque la lectura ya diga prendido`() = runTest {
        val wifi = mockk<WifiManager>(relaxed = true) { every { isWifiEnabled } returns true }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wifi
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED

        WifiFailoverController(ctx).setWifiEnabled(enabled = true, source = "prueba", forzar = true)

        io.mockk.verify { wifi.setWifiEnabled(true) }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "*WifiFailoverControllerTest*"` ⇒ FAIL (`resultado` no es null).

- [ ] **Step 3: Implement** — en los dos `try` del archivo, primero:

```kotlin
        } catch (cancelado: kotlinx.coroutines.CancellationException) {
            throw cancelado
```

(en el primero antes de `catch (securityException: SecurityException)`; en el del DAL antes de `catch (error: Throwable)`). Y el re-chequeo antes del DAL, sólo al APAGAR:

```kotlin
        if (after != enabled && BuildConfig.ENABLE_PAX_SDK) {
            if (!enabled && !antesDelCanalPax()) {
                paxChannelError = "omitido: empezó una operación crítica"
            } else try {
                // … bloque DAL existente sin cambios …
```

Parámetros nuevos en la firma: `antesDelCanalPax: () -> Boolean = { true }` (KDoc: «Se evalúa justo antes del canal DAL de la PAX, 1.5 s después del primer intento: si en ese lapso empezó un cobro o cambió el modo o las señales, no se apaga por ese canal.») y `forzar: Boolean = false`, con el retorno temprano cambiado a `if (before == enabled && !forzar) { return … }`. Función nueva junto a `isWifiEnabled()`: `fun estadoDelRadio(): Int = wifiManager().wifiState`.

- [ ] **Step 4: Run** ⇒ PASS.

---

### Task 3: Detector puro

**Files:** Create `core/util/WifiSinSalida.kt` · Create `core/util/WifiSinSalidaDetectorTest.kt` (test)

**Interfaces:** Produces `enum class ProbeExterno { ALCANZABLE, INALCANZABLE, SIN_PROBAR }`, `fun respuestaExterna(codigoHttp: Int?): ProbeExterno`, `enum class VeredictoWifi { SANO, SOSPECHA, SERVIDOR_O_DNS, WIFI_SIN_SALIDA }`, `enum class EstadoWifiSinSalida { NO, DETECTADO, REINICIANDO }`, `data class LecturaDeRed(ahoraMs: Long, tipo: NetworkType, conectada: Boolean, hayServidor: Boolean, socketVivo: Boolean, externo: ProbeExterno)`, `class WifiSinSalidaDetector(confirmarTrasMs: Long = 60_000L) { fun evaluar(l: LecturaDeRed): VeredictoWifi }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSinSalidaDetectorTest {
    private fun lectura(
        ahoraMs: Long, tipo: NetworkType = NetworkType.WIFI, conectada: Boolean = true,
        hayServidor: Boolean = false, socketVivo: Boolean = false, externo: ProbeExterno = ProbeExterno.INALCANZABLE,
    ) = LecturaDeRed(ahoraMs, tipo, conectada, hayServidor, socketVivo, externo)

    @Test fun `P1 cualquier respuesta http prueba que el enlace pasa datos`() {
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(204))
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(503))
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(302))
        assertEquals(ProbeExterno.INALCANZABLE, respuestaExterna(null))
    }

    @Test fun `con servidor todo esta sano`() =
        assertEquals(VeredictoWifi.SANO, WifiSinSalidaDetector().evaluar(lectura(0, hayServidor = true)))

    @Test fun `P1 socket vivo nunca es wifi sin salida`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, socketVivo = true)))
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(120_000, socketVivo = true)))
    }

    @Test fun `P1 internet ajeno responde es problema de servidor, no del wifi`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, d.evaluar(lectura(0, externo = ProbeExterno.ALCANZABLE)))
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, d.evaluar(lectura(120_000, externo = ProbeExterno.ALCANZABLE)))
    }

    @Test fun `celular o sin red no es asunto de este detector`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, tipo = NetworkType.CELLULAR)))
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, tipo = NetworkType.NONE, conectada = false)))
    }

    @Test fun `antes de 60 s es sospecha y a los 60 s se confirma`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(0)))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(59_999)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(60_000)))
    }

    @Test fun `sin haber sondeado no se confirma aunque pase el tiempo`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0, externo = ProbeExterno.SIN_PROBAR))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(120_000, externo = ProbeExterno.SIN_PROBAR)))
    }

    @Test fun `si vuelve el servidor la cuenta empieza de cero`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0))
        d.evaluar(lectura(30_000, hayServidor = true))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(70_000)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(130_000)))
    }

    @Test fun `reiniciar la cuenta tras un reinicio del wifi exige otros 60 s`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0)); d.evaluar(lectura(60_000))
        d.reiniciarCuenta()
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(65_000)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(125_000)))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "*WifiSinSalidaDetectorTest*"` ⇒ FAIL de compilación.

- [ ] **Step 3: Implement**

```kotlin
package com.jaac.avoqado_tpv.core.util

/** Qué dijo un sitio AJENO a Avoqado (Google `generate_204`). Separa «se cayó el WiFi» de «se cayó Avoqado». */
enum class ProbeExterno { ALCANZABLE, INALCANZABLE, SIN_PROBAR }

/**
 * Cualquier respuesta HTTP (204, 503, 302 de portal cautivo) prueba que el enlace PASA DATOS: reiniciar el WiFi no
 * arregla nada y, con Avoqado caído, reiniciaría el WiFi de toda la flota. Sólo el fallo de transporte (`null`) cuenta.
 */
fun respuestaExterna(codigoHttp: Int?): ProbeExterno =
    if (codigoHttp == null) ProbeExterno.INALCANZABLE else ProbeExterno.ALCANZABLE

enum class VeredictoWifi { SANO, SOSPECHA, SERVIDOR_O_DNS, WIFI_SIN_SALIDA }

/** Lo que el banner debe decir sobre el WiFi. */
enum class EstadoWifiSinSalida { NO, DETECTADO, REINICIANDO }

data class LecturaDeRed(
    val ahoraMs: Long,
    val tipo: NetworkType,
    /** `NetworkMonitor`: capacidad INTERNET, NO `VALIDATED`: un WiFi enlazado pero muerto cuenta como conectado. */
    val conectada: Boolean,
    /** `ConnectionStateManager.hasServer`, ya con la histéresis de 2 fallos del heartbeat. */
    val hayServidor: Boolean,
    val socketVivo: Boolean,
    val externo: ProbeExterno,
)

/**
 * ¿El WiFi de ESTA terminal está enlazado pero sin pasar datos? (Testarudo, 29-sep-2026: 38 min así con el WiFi del
 * local sano.) Puro: quien llama pasa la hora. Confirma sólo con fallo de transporte sostenido [confirmarTrasMs].
 */
class WifiSinSalidaDetector(private val confirmarTrasMs: Long = CONFIRMAR_TRAS_MS) {
    private var sospechaDesdeMs: Long? = null

    fun evaluar(l: LecturaDeRed): VeredictoWifi {
        val sospechoso = l.tipo == NetworkType.WIFI && l.conectada && !l.hayServidor && !l.socketVivo
        if (!sospechoso) {
            sospechaDesdeMs = null
            return VeredictoWifi.SANO
        }
        if (l.externo == ProbeExterno.ALCANZABLE) {
            sospechaDesdeMs = null
            return VeredictoWifi.SERVIDOR_O_DNS
        }
        val desde = sospechaDesdeMs ?: l.ahoraMs.also { sospechaDesdeMs = it }
        return if (l.externo == ProbeExterno.INALCANZABLE && l.ahoraMs - desde >= confirmarTrasMs) {
            VeredictoWifi.WIFI_SIN_SALIDA
        } else {
            VeredictoWifi.SOSPECHA
        }
    }

    /** Tras un reinicio del WiFi, la siguiente confirmación exige otros [confirmarTrasMs] completos (regla 8). */
    fun reiniciarCuenta() {
        sospechaDesdeMs = null
    }

    companion object {
        const val CONFIRMAR_TRAS_MS = 60_000L
    }
}
```

- [ ] **Step 4: Run** ⇒ PASS (9).

---

### Task 4: Monitor serializado + campo del banner

**Files:** Create `core/util/WifiSinSalidaMonitor.kt` · Modify `core/util/ConnectionStateManager.kt` · Create `core/util/WifiSinSalidaMonitorTest.kt` (test)

**Interfaces:**
- Consumes Task 3; `SocketManager.isCurrentlyConnected()`; `NetworkMonitor.getCurrentNetworkInfo()`; `ConnectionStateManager.connectionState`; `ObservabilityManager.logWarning(tag, message, metadata)`.
- Produces: `class InternetExternoProbe @Inject constructor()` con `suspend fun consultar(): ProbeExterno`; `class WifiSinSalidaMonitor @Inject constructor(probe, socketManager, networkMonitor, connectionStateManager, observability)` con `suspend fun evaluar(ahoraMs: Long, causaUltimoFallo: String?, modo: CellularFailoverMode): VeredictoWifi`, `fun sigueSinSalida(): Boolean`, `suspend fun confirmarEnVivo(): Boolean`, `fun registrarAccion(accion: String)`, `fun trasReiniciar()`; `ConnectionStateManager.setWifiSinSalida(estado)`; `ConnectionState.wifiSinSalida: EstadoWifiSinSalida = NO`.

- [ ] **Step 1: `ConnectionStateManager`** — en `data class ConnectionState` agregar al final `val wifiSinSalida: EstadoWifiSinSalida = EstadoWifiSinSalida.NO`. En `updateState` conservarlo: agregar `wifiSinSalida = _connectionState.value.wifiSinSalida,` al `ConnectionState(...)` de `newState` (hoy construye uno nuevo y lo borraría). Setter nuevo:

```kotlin
    /** Lo publican [WifiSinSalidaMonitor] (DETECTADO / NO) y [ControlDeWifi] vía el VM (REINICIANDO). */
    fun setWifiSinSalida(estado: EstadoWifiSinSalida) {
        val current = _connectionState.value
        if (current.wifiSinSalida != estado) {
            Timber.i("📶 [ConnectionState] WiFi sin salida: $estado")
            _connectionState.value = current.copy(wifiSinSalida = estado)
        }
    }
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiSinSalidaMonitorTest {
    private val probe = mockk<InternetExternoProbe>()
    private val socket = mockk<SocketManager>()
    private val red = mockk<NetworkMonitor>()
    private val estadoFlow = MutableStateFlow(ConnectionState(hasInternet = true, hasServer = false))
    private val estado = mockk<ConnectionStateManager>(relaxed = true)
    private val obs = mockk<ObservabilityManager>(relaxed = true)
    private lateinit var monitor: WifiSinSalidaMonitor

    private val wifi = NetworkInfo(type = NetworkType.WIFI, isMetered = false, isConnected = true, signalStrength = 3)

    @Before
    fun setup() {
        every { socket.isCurrentlyConnected() } returns false
        every { red.getCurrentNetworkInfo() } returns wifi
        every { estado.connectionState } returns estadoFlow
        coEvery { probe.consultar() } returns ProbeExterno.INALCANZABLE
        monitor = WifiSinSalidaMonitor(probe, socket, red, estado, obs)
    }

    private suspend fun ev(ms: Long, modo: CellularFailoverMode = CellularFailoverMode.OFF, causa: String? = null) =
        monitor.evaluar(ms, causa, modo)

    @Test fun `socket vivo no sondea ni sospecha`() = runTest {
        every { socket.isCurrentlyConnected() } returns true
        assertEquals(VeredictoWifi.SANO, ev(0))
        coVerify(exactly = 0) { probe.consultar() }
    }

    @Test fun `P1 google 503 es problema de servidor y no marca el wifi`() = runTest {
        coEvery { probe.consultar() } returns respuestaExterna(503)
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, ev(0))
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, ev(90_000))
        verify(exactly = 0) { estado.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO) }
    }

    @Test fun `sondea a lo mucho cada 30 s`() = runTest {
        ev(0); ev(10_000); ev(29_999)
        coVerify(exactly = 1) { probe.consultar() }
        ev(30_000)
        coVerify(exactly = 2) { probe.consultar() }
    }

    @Test fun `P1 relee las senales despues de la sonda`() = runTest {
        coEvery { probe.consultar() } answers {
            every { socket.isCurrentlyConnected() } returns true   // volvió el socket durante la sonda
            ProbeExterno.INALCANZABLE
        }
        assertEquals(VeredictoWifi.SANO, ev(0))
    }

    @Test fun `P1 dos evaluaciones a la vez sondean una sola vez`() = runTest {
        val puerta = CompletableDeferred<Unit>()
        coEvery { probe.consultar() } coAnswers { puerta.await(); ProbeExterno.INALCANZABLE }
        val a = launch { ev(0) }
        val b = launch { ev(1) }
        runCurrent()
        puerta.complete(Unit)
        a.join(); b.join()
        coVerify(exactly = 1) { probe.consultar() }
    }

    @Test fun `al confirmar publica DETECTADO y deja evidencia una sola vez`() = runTest {
        ev(0, causa = "UnknownHostException")
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, ev(60_000))
        ev(90_000)
        verify(exactly = 1) { estado.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO) }
        verify(exactly = 1) { obs.logWarning("WifiSinSalida", match { it.contains("confirmado") }, any()) }
    }

    @Test fun `al volver el servidor cierra con duracion, acciones y causa`() = runTest {
        ev(0, CellularFailoverMode.AUTO_ENFORCED, "SocketTimeoutException")
        ev(60_000, CellularFailoverMode.AUTO_ENFORCED)
        monitor.registrarAccion("wifi_reiniciado")
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(150_000, CellularFailoverMode.AUTO_ENFORCED)
        verify { estado.setWifiSinSalida(EstadoWifiSinSalida.NO) }
        verify(exactly = 1) {
            obs.logWarning(
                "WifiSinSalida",
                match { it.contains("resuelto") },
                match { it["duracionS"] == 150L && it["acciones"].toString().contains("wifi_reiniciado") && it["causa"] == "SocketTimeoutException" },
            )
        }
    }

    @Test fun `un bache que no se confirmo no deja reporte`() = runTest {
        ev(0)
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(40_000)
        verify(exactly = 0) { obs.logWarning(any(), any(), any()) }
    }

    @Test fun `tras reiniciar la confirmacion exige otros 60 s y no duplica el reporte`() = runTest {
        ev(0); ev(60_000)
        monitor.trasReiniciar()
        assertEquals(VeredictoWifi.SOSPECHA, ev(90_000))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, ev(150_000))
        verify(exactly = 1) { obs.logWarning("WifiSinSalida", match { it.contains("confirmado") }, any()) }
    }

    @Test fun `sigueSinSalida relee red, servidor y socket`() {
        assertEquals(true, monitor.sigueSinSalida())
        every { socket.isCurrentlyConnected() } returns true
        assertEquals(false, monitor.sigueSinSalida())
    }

    @Test fun `P1 confirmarEnVivo sondea aunque haya sondeado hace poco y respeta una respuesta`() = runTest {
        ev(0)   // sonda #1
        assertEquals(true, monitor.confirmarEnVivo())   // sonda #2, sin esperar 30 s
        coVerify(exactly = 2) { probe.consultar() }
        coEvery { probe.consultar() } returns respuestaExterna(204)
        assertEquals(false, monitor.confirmarEnVivo())
    }

    @Test fun `la bitacora no repite acciones`() = runTest {
        ev(0); ev(60_000)
        repeat(5) { monitor.registrarAccion("no_se_reinicio:cobro_en_curso") }
        estadoFlow.value = ConnectionState(hasInternet = true, hasServer = true)
        ev(90_000)
        verify { obs.logWarning("WifiSinSalida", match { it.contains("resuelto") }, match { it["acciones"] == "no_se_reinicio:cobro_en_curso" }) }
    }
}
```

- [ ] **Step 3: Run to verify it fails** — `… --tests "*WifiSinSalidaMonitorTest*"` ⇒ FAIL de compilación.

- [ ] **Step 4: Implement**

```kotlin
package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.payment.domain.model.CellularFailoverMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pregunta a Google, no a Avoqado: separa «se cayó el WiFi» de «se cayó nuestro servidor». `HttpURLConnection` pelón a
 * propósito: sin redirecciones, sin reintentos por 503, sin los interceptores ni el callTimeout de 25 s de la API.
 */
@Singleton
class InternetExternoProbe @Inject constructor() {
    suspend fun consultar(): ProbeExterno = withContext(Dispatchers.IO) {
        val codigo = try {
            val conexion = URL(URL_SONDA).openConnection() as HttpURLConnection
            try {
                conexion.instanceFollowRedirects = false
                conexion.useCaches = false
                conexion.connectTimeout = TIMEOUT_MS
                conexion.readTimeout = TIMEOUT_MS
                conexion.responseCode
            } finally {
                conexion.disconnect()
            }
        } catch (e: IOException) {
            null
        }
        if (codigo != null && codigo != 204) Timber.w("📶 [WifiSinSalida] Google respondió $codigo: el enlace pasa datos")
        respuestaExterna(codigo)
    }

    companion object {
        const val URL_SONDA = "https://connectivitycheck.gstatic.com/generate_204"
        const val TIMEOUT_MS = 5_000
    }
}

/**
 * Vigila el WiFi «enlazado pero muerto» en TODAS las terminales sin tocar la red: estado del banner y evidencia al
 * confirmar y al resolverse. Serializado ([mutex]): el loop, el observador y «Reintentar» lo llaman a la vez. El
 * reinicio lo hace [ControlDeWifi] y sólo lo pide `ConnectionViewModel` en `AUTO_ENFORCED`.
 */
@Singleton
class WifiSinSalidaMonitor @Inject constructor(
    private val probe: InternetExternoProbe,
    private val socketManager: SocketManager,
    private val networkMonitor: NetworkMonitor,
    private val connectionStateManager: ConnectionStateManager,
    private val observability: ObservabilityManager,
) {
    private val mutex = Mutex()
    private val detector = WifiSinSalidaDetector()
    private var ultimoProbe = ProbeExterno.SIN_PROBAR
    private var ultimoProbeAtMs: Long? = null
    private var incidente: Incidente? = null

    private class Incidente(val inicioMs: Long) {
        var confirmado = false
        var causa: String? = null
        // Conjunto ordenado: cada evaluación repite «cobro_en_curso» o «tope_por_hora»; el reporte guarda cada acción una vez.
        val acciones = linkedSetOf<String>()
    }

    /** Relee red, servidor y socket AHORA. [ControlDeWifi] lo usa justo antes de apagar. */
    fun sigueSinSalida(): Boolean {
        val red = networkMonitor.getCurrentNetworkInfo()
        return red.type == NetworkType.WIFI && red.isConnected &&
            !connectionStateManager.connectionState.value.hasServer &&
            !socketManager.isCurrentlyConnected()
    }

    /**
     * Sonda EN VIVO, sin el límite de 30 s: `hasServer` y el socket pueden llegar con ~30 s de atraso. Si el enlace volvió
     * justo antes del reinicio, no se toca (Codex v3 #1). true = sigue muerto por transporte.
     */
    suspend fun confirmarEnVivo(): Boolean =
        sigueSinSalida() && probe.consultar() == ProbeExterno.INALCANZABLE && sigueSinSalida()

    suspend fun evaluar(ahoraMs: Long, causaUltimoFallo: String?, modo: CellularFailoverMode): VeredictoWifi = mutex.withLock {
        if (!sigueSinSalida()) {
            ultimoProbe = ProbeExterno.SIN_PROBAR
            ultimoProbeAtMs = null
        } else if (ultimoProbeAtMs.let { it == null || ahoraMs - it >= PROBE_CADA_MS }) {
            ultimoProbe = probe.consultar()
            ultimoProbeAtMs = ahoraMs
        }
        // Relectura DESPUÉS de la sonda (hasta 5 s + 5 s): pudo volver el servidor o el socket, o cambiar la red.
        val red = networkMonitor.getCurrentNetworkInfo()
        val conexion = connectionStateManager.connectionState.value
        val veredicto = detector.evaluar(
            LecturaDeRed(ahoraMs, red.type, red.isConnected, conexion.hasServer, socketManager.isCurrentlyConnected(), ultimoProbe)
        )

        when (veredicto) {
            VeredictoWifi.SOSPECHA, VeredictoWifi.WIFI_SIN_SALIDA -> {
                val inc = incidente ?: Incidente(ahoraMs).also { incidente = it }
                if (causaUltimoFallo != null) inc.causa = causaUltimoFallo
                if (veredicto == VeredictoWifi.WIFI_SIN_SALIDA && !inc.confirmado) {
                    inc.confirmado = true
                    connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO)
                    observability.logWarning(TAG, "WiFi sin salida confirmado", metadatos(inc, ahoraMs, red, modo))
                }
            }
            VeredictoWifi.SANO, VeredictoWifi.SERVIDOR_O_DNS -> {
                // Durante los ~3 s del reinicio el detector dice SANO (tipo NONE) pero el incidente NO terminó: se cierra al
                // volver el servidor, o si el internet ajeno responde (entonces es Avoqado, no el WiFi).
                if (conexion.hasServer || veredicto == VeredictoWifi.SERVIDOR_O_DNS) cerrar(ahoraMs, red, modo)
            }
        }
        veredicto
    }

    fun registrarAccion(accion: String) {
        incidente?.acciones?.add(accion)
    }

    /** Tras un reinicio: la siguiente confirmación exige otros 60 s (regla 8); el incidente y su reporte siguen siendo uno. */
    fun trasReiniciar() {
        detector.reiniciarCuenta()
        ultimoProbe = ProbeExterno.SIN_PROBAR
        ultimoProbeAtMs = null
    }

    private fun cerrar(ahoraMs: Long, red: NetworkInfo, modo: CellularFailoverMode) {
        val inc = incidente ?: return
        incidente = null
        connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.NO)
        if (inc.confirmado) observability.logWarning(TAG, "WiFi sin salida resuelto", metadatos(inc, ahoraMs, red, modo))
    }

    private fun metadatos(inc: Incidente, ahoraMs: Long, red: NetworkInfo, modo: CellularFailoverMode): Map<String, Any?> = mapOf(
        "duracionS" to (ahoraMs - inc.inicioMs) / 1_000L,
        "acciones" to inc.acciones.joinToString(","),
        "causa" to inc.causa,
        "redAhora" to red.type.name,
        "modo" to modo.name,
    )

    companion object {
        const val TAG = "WifiSinSalida"
        const val PROBE_CADA_MS = 30_000L
    }
}
```

Nota sobre el test `tras reiniciar…`: la segunda confirmación del MISMO incidente no vuelve a llamar `logWarning("…confirmado…")` porque `inc.confirmado` ya es `true`. Es a propósito: un incidente, un reporte de confirmación.

- [ ] **Step 5: Run** ⇒ PASS (10).

---

### Task 5: `ControlDeWifi` — el reinicio corto, con un solo dueño

**Files:** Create `core/util/ControlDeWifi.kt` (contiene `ControlDeWifi`, `MarcaDeWifi`, `ResultadoReinicio`) · Create `core/util/ControlDeWifiTest.kt` (test)

**Interfaces:**
- Consumes Task 1 (guarda) y Task 2 (`setWifiEnabled(enabled, source, antesDelCanalPax, forzar)`, `estadoDelRadio()`).
- Produces: `sealed interface ResultadoReinicio { data object Reiniciado; data class NoSeReinicio(val motivo: String); data object Incierto }`; `class ControlDeWifi @Inject constructor(wifi: WifiFailoverController, critica: CriticalNetworkOperationManager, marca: MarcaDeWifi)` con `suspend fun reiniciar(confirmarEnVivo: suspend () -> Boolean, puedeActuar: () -> Boolean): ResultadoReinicio`, `suspend fun restaurarSiQuedoApagado(): Boolean` e `internal var ahora: () -> Long`; `class MarcaDeWifi @Inject constructor(@ApplicationContext context: Context)` con `fun desde(): Long?`, `suspend fun poner(ahoraMs: Long): Boolean`, `suspend fun quitar()`.

**Reglas que implementa (Codex v3 #1-#3):** sonda EN VIVO justo antes de apagar; tras pedir el apagado **siempre** se pide PRENDER con `forzar = true`, aunque la lectura aún diga prendido (un apagado aceptado y tardío se concretaría después; Android atiende las peticiones en orden y la última, prender, gana); la marca se quita sólo con `estadoDelRadio() == WIFI_STATE_ENABLED` tras ese pedido; restaurar una marca = pedir prender forzado (sin limpiezas por antigüedad); el callback del DAL relee cobro, modo y señales.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jaac.avoqado_tpv.core.util

import android.net.wifi.WifiManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlDeWifiTest {
    private val wifi = mockk<WifiFailoverController>()
    private val critica = mockk<CriticalNetworkOperationManager>()
    private val marca = mockk<MarcaDeWifi>()
    private lateinit var control: ControlDeWifi
    private var radioPrendido = true
    private var marcaDesde: Long? = null

    private fun toggle(enabled: Boolean) = WifiToggleResult(enabled, !enabled, enabled, true, false, null, true)

    @Before
    fun setup() {
        radioPrendido = true
        marcaDesde = null
        every { critica.isAnyCriticalOperationInProgress() } returns false
        every { wifi.estadoDelRadio() } answers {
            if (radioPrendido) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
        }
        coEvery { wifi.setWifiEnabled(any(), any(), any(), any()) } answers { radioPrendido = firstArg(); toggle(firstArg()) }
        every { marca.desde() } answers { marcaDesde }
        coEvery { marca.poner(any()) } answers { marcaDesde = firstArg(); true }
        coEvery { marca.quitar() } answers { marcaDesde = null }
        control = ControlDeWifi(wifi, critica, marca)
    }

    private fun TestScope.reloj() { control.ahora = { 1_000_000L + testScheduler.currentTime } }
    private suspend fun reiniciar(vivo: Boolean = true, puede: () -> Boolean = { true }) =
        control.reiniciar(confirmarEnVivo = { vivo }, puedeActuar = puede)

    @Test fun `reinicia con la marca puesta antes, prende forzado y quita la marca al final`() = runTest {
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        coVerifyOrder {
            marca.poner(any())
            wifi.setWifiEnabled(false, any(), any(), any())
            wifi.setWifiEnabled(true, any(), any(), true)
            marca.quitar()
        }
        assertTrue(radioPrendido)
    }

    @Test fun `P1 cobro en curso no reinicia ni pone la marca`() = runTest {
        every { critica.isAnyCriticalOperationInProgress() } returns true
        assertEquals(ResultadoReinicio.NoSeReinicio("cobro_en_curso"), reiniciar())
        coVerify(exactly = 0) { marca.poner(any()); wifi.setWifiEnabled(any(), any(), any(), any()) }
    }

    @Test fun `P1 si la sonda en vivo responde el enlace volvio y no se toca`() = runTest {
        assertEquals(ResultadoReinicio.NoSeReinicio("el_enlace_volvio"), reiniciar(vivo = false))
        coVerify(exactly = 0) { marca.poner(any()); wifi.setWifiEnabled(any(), any(), any(), any()) }
    }

    @Test fun `P1 cobro que empieza mientras se escribe la marca detiene el reinicio`() = runTest {
        coEvery { marca.poner(any()) } answers {
            marcaDesde = firstArg()
            every { critica.isAnyCriticalOperationInProgress() } returns true
            true
        }
        assertEquals(ResultadoReinicio.NoSeReinicio("cambio_a_ultimo_momento"), reiniciar())
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
        coVerify { marca.quitar() }
    }

    @Test fun `P1 marca que no se pudo guardar no reinicia`() = runTest {
        coEvery { marca.poner(any()) } returns false
        assertEquals(ResultadoReinicio.NoSeReinicio("marca_no_guardada"), reiniciar())
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
    }

    @Test fun `modo o senales que cambian antes de actuar no reinician`() = runTest {
        assertEquals(ResultadoReinicio.NoSeReinicio("ya_no_aplica"), reiniciar(puede = { false }))
    }

    @Test fun `P2 el callback del DAL relee cobro, modo y senales`() = runTest {
        var puede = true
        val antesDelDal = slot<() -> Boolean>()
        coEvery { wifi.setWifiEnabled(false, any(), capture(antesDelDal), any()) } answers { radioPrendido = false; toggle(false) }
        reiniciar(puede = { puede })
        assertTrue(antesDelDal.captured())
        puede = false
        assertFalse(antesDelDal.captured())
        puede = true
        every { critica.isAnyCriticalOperationInProgress() } returns true
        assertFalse(antesDelDal.captured())
    }

    @Test fun `tope de 3 por hora`() = runTest {
        reloj()
        repeat(3) {
            assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
            advanceTimeBy(61_000)
        }
        assertEquals(ResultadoReinicio.NoSeReinicio("tope_por_hora"), reiniciar())
    }

    @Test fun `no reinicia otra vez dentro de 60 s del anterior`() = runTest {
        reloj()
        assertEquals(ResultadoReinicio.Reiniciado, reiniciar())
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), reiniciar())
    }

    @Test fun `P1 apagado que no se ve a tiempo igual pide prender forzado`() = runTest {
        // Android aceptó apagar pero la lectura sigue diciendo prendido (transición lenta): el plan v3 quitaba la marca y
        // no pedía prender, y el apagado tardío dejaba la terminal sin WiFi (Codex v3 #2).
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } returns toggle(false).copy(after = true)
        val r = reiniciar()
        advanceUntilIdle()
        assertEquals(ResultadoReinicio.NoSeReinicio("el_radio_no_se_apago"), r)
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
        coVerify { marca.quitar() }   // sólo tras ver WIFI_STATE_ENABLED después del pedido de prender
    }

    @Test fun `encendido no verificado conserva la marca`() = runTest {
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(after = false)   // no prende
        val r = reiniciar()
        advanceUntilIdle()
        assertEquals(ResultadoReinicio.Incierto, r)
        coVerify(exactly = 0) { marca.quitar() }
    }

    @Test fun `P1 reinicio cancelado por el llamador termina y prende`() = runTest {
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } coAnswers { delay(1_500); radioPrendido = false; toggle(false) }
        val job = launch { reiniciar() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(radioPrendido)
        coVerify { marca.quitar() }
    }

    @Test fun `P1 dos pedidos simultaneos reinician una sola vez`() = runTest {
        reloj()
        val puerta = CompletableDeferred<Unit>()
        coEvery { wifi.setWifiEnabled(false, any(), any(), any()) } coAnswers { puerta.await(); radioPrendido = false; toggle(false) }
        var segundo: ResultadoReinicio? = null
        val a = launch { reiniciar() }
        val b = launch { segundo = reiniciar() }   // espera el Mutex y encuentra el reinicio recién hecho
        runCurrent()
        puerta.complete(Unit)
        advanceUntilIdle(); a.join(); b.join()
        coVerify(exactly = 1) { wifi.setWifiEnabled(false, any(), any(), any()) }
        assertEquals(ResultadoReinicio.NoSeReinicio("reinicio_reciente"), segundo)
    }

    @Test fun `restaurar pide prender forzado y quita la marca al verlo prendido`() = runTest {
        marcaDesde = 1L; radioPrendido = false
        assertTrue(control.restaurarSiQuedoApagado())
        assertTrue(radioPrendido)
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
        coVerify { marca.quitar() }
    }

    @Test fun `restaurar con la lectura ya prendida igual pide prender forzado`() = runTest {
        marcaDesde = 1L; radioPrendido = true
        assertTrue(control.restaurarSiQuedoApagado())
        coVerify { wifi.setWifiEnabled(true, any(), any(), true) }
    }

    @Test fun `restaurar que no logra prender conserva la marca`() = runTest {
        marcaDesde = 1L; radioPrendido = false
        coEvery { wifi.setWifiEnabled(true, any(), any(), any()) } returns toggle(true).copy(after = false)
        assertFalse(control.restaurarSiQuedoApagado())
        coVerify(exactly = 0) { marca.quitar() }
    }

    @Test fun `sin marca restaurar no hace nada`() = runTest {
        assertTrue(control.restaurarSiQuedoApagado())
        coVerify(exactly = 0) { wifi.setWifiEnabled(any(), any(), any(), any()) }
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "*ControlDeWifiTest*"` ⇒ FAIL de compilación.

- [ ] **Step 3: Implement `ControlDeWifi.kt`**

```kotlin
package com.jaac.avoqado_tpv.core.util

import android.content.Context
import android.net.wifi.WifiManager
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ResultadoReinicio {
    data object Reiniciado : ResultadoReinicio
    data class NoSeReinicio(val motivo: String) : ResultadoReinicio
    /** Se pidió prender y el radio no lo confirmó: la marca queda y cada evaluación y el arranque vuelven a pedirlo. */
    data object Incierto : ResultadoReinicio
}

/**
 * El ÚNICO que reinicia el WiFi por cuenta de la app (Testarudo, 29-sep-2026): apagar → ~3 s → prender, lo que Square
 * le pide hacer a mano al vendedor. Singleton con [mutex] (varias `MainActivity` no reinician a la vez) y
 * [NonCancellable] (cerrar la pantalla no deja el WiFi apagado a la mitad). Reglas del plan: 3, 4, 5, 6, 7.
 */
@Singleton
class ControlDeWifi @Inject constructor(
    private val wifi: WifiFailoverController,
    private val critica: CriticalNetworkOperationManager,
    private val marca: MarcaDeWifi,
) {
    private val mutex = Mutex()
    private val reinicios = ArrayDeque<Long>()

    @VisibleForTesting
    internal var ahora: () -> Long = System::currentTimeMillis

    /**
     * [confirmarEnVivo]: sonda a Google SIN el límite de 30 s (las banderas de servidor y socket llegan con atraso).
     * [puedeActuar]: modo AUTO_ENFORCED + señales, releído sin suspensión justo antes del hardware y antes del DAL.
     */
    suspend fun reiniciar(confirmarEnVivo: suspend () -> Boolean, puedeActuar: () -> Boolean): ResultadoReinicio =
        mutex.withLock {
            withContext(NonCancellable) {
                val now = ahora()
                while (reinicios.isNotEmpty() && now - reinicios.first() > VENTANA_TOPE_MS) reinicios.removeFirst()
                when {
                    critica.isAnyCriticalOperationInProgress() -> ResultadoReinicio.NoSeReinicio("cobro_en_curso")
                    reinicios.size >= MAX_REINICIOS_POR_HORA -> ResultadoReinicio.NoSeReinicio("tope_por_hora")
                    // Dos pantallas que piden a la vez: la segunda espera el Mutex y NO reinicia encima del anterior.
                    reinicios.lastOrNull()?.let { now - it < REINICIO_RECIENTE_MS } == true ->
                        ResultadoReinicio.NoSeReinicio("reinicio_reciente")
                    !puedeActuar() -> ResultadoReinicio.NoSeReinicio("ya_no_aplica")
                    // Si el enlace volvió justo antes, no se corta nada que acabara de empezar (Codex v3 #1).
                    !confirmarEnVivo() -> ResultadoReinicio.NoSeReinicio("el_enlace_volvio")
                    !marca.poner(now) -> ResultadoReinicio.NoSeReinicio("marca_no_guardada")
                    // Última revisión SIN suspensión entre ésta y la petición de apagado (setWifiEnabled no suspende antes
                    // de pedirlo). El radio sigue prendido: quitar la marca aquí es seguro.
                    critica.isAnyCriticalOperationInProgress() || !puedeActuar() -> {
                        marca.quitar()
                        ResultadoReinicio.NoSeReinicio("cambio_a_ultimo_momento")
                    }
                    else -> apagarYPrender(now, puedeActuar)
                }
            }
        }

    private suspend fun apagarYPrender(now: Long, puedeActuar: () -> Boolean): ResultadoReinicio {
        wifi.setWifiEnabled(
            enabled = false,
            source = "wifi_sin_salida",
            antesDelCanalPax = { !critica.isAnyCriticalOperationInProgress() && puedeActuar() },
        )
        val seApago = esperarEstado(WifiManager.WIFI_STATE_DISABLED, ESPERA_APAGADO_MS)
        if (seApago) {
            reinicios.addLast(now)
            delay(PAUSA_MS)
        } else {
            Timber.w("📶 [ControlDeWifi] el radio no reportó apagado en ${ESPERA_APAGADO_MS} ms: igual se pide prender")
        }
        // SIEMPRE se pide prender, forzado: un apagado aceptado y lento se concretaría después y dejaría la terminal sin
        // WiFi. Android atiende las peticiones en orden: la última (prender) gana.
        val prendido = prender("wifi_sin_salida:prender")
        return when {
            !prendido -> ResultadoReinicio.Incierto
            seApago -> ResultadoReinicio.Reiniciado
            else -> ResultadoReinicio.NoSeReinicio("el_radio_no_se_apago")
        }
    }

    /** Prender no corta nada: sin guarda. La marca se quita SÓLO al ver `WIFI_STATE_ENABLED` después del pedido. */
    private suspend fun prender(source: String): Boolean {
        wifi.setWifiEnabled(enabled = true, source = source, forzar = true)
        val prendido = esperarEstado(WifiManager.WIFI_STATE_ENABLED, ESPERA_PRENDIDO_MS)
        if (prendido) marca.quitar() else Timber.e("📶 [ControlDeWifi] se pidió prender y no se vio: la marca queda ($source)")
        return prendido
    }

    /** Si quedó una marca (proceso muerto a la mitad, o un encendido no confirmado), se pide prender. true = nada pendiente. */
    suspend fun restaurarSiQuedoApagado(): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            if (marca.desde() == null) true else prender("restaurar_marca")
        }
    }

    /** Estado REAL del radio (`WIFI_STATE_*`); `isWifiEnabled()` no distingue las transiciones. */
    private suspend fun esperarEstado(estado: Int, maxMs: Long): Boolean {
        var esperado = 0L
        while (wifi.estadoDelRadio() != estado && esperado < maxMs) {
            delay(SONDEO_RADIO_MS)
            esperado += SONDEO_RADIO_MS
        }
        return wifi.estadoDelRadio() == estado
    }

    companion object {
        const val MAX_REINICIOS_POR_HORA = 3
        const val VENTANA_TOPE_MS = 3_600_000L
        const val REINICIO_RECIENTE_MS = 60_000L
        const val ESPERA_APAGADO_MS = 5_000L
        const val PAUSA_MS = 3_000L
        const val ESPERA_PRENDIDO_MS = 10_000L
        const val SONDEO_RADIO_MS = 250L
    }
}

/** «La app apagó el WiFi», en disco. I/O delgado sin reglas propias: lo cubren `ControlDeWifiTest` (mockeado) y el hardware. */
@Singleton
class MarcaDeWifi @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun desde(): Long? = prefs.getLong(KEY, 0L).takeIf { it > 0L }

    /** `commit()` síncrono, fuera de Main: tiene que estar en disco ANTES de apagar. false ⇒ no se apaga. */
    suspend fun poner(ahoraMs: Long): Boolean = withContext(Dispatchers.IO) { prefs.edit().putLong(KEY, ahoraMs).commit() }

    suspend fun quitar() {
        withContext(Dispatchers.IO) { prefs.edit().remove(KEY).commit() }
    }

    companion object {
        private const val PREFS = "wifi_restauracion"
        private const val KEY = "apagado_por_la_app_ms"
    }
}
```

Nota: `esperarEstado` usa `delay` en tiempo virtual dentro de `runTest`. `WifiManager.WIFI_STATE_*` son constantes de compilación: no hace falta Robolectric.

- [ ] **Step 4: Run** — `… --tests "*ControlDeWifiTest*"` ⇒ PASS (18). Compilar variantes: `cd /Users/amieva/Documents/Programming/Avoqado && ./scripts/avq-verify.sh avoqado-tpv ./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin` ⇒ `BUILD SUCCESSFUL`.

---

### Task 6: `ConnectionViewModel` — pedir el reinicio; se borra el failover viejo

**Files:** Modify `core/presentation/viewmodels/ConnectionViewModel.kt` · Modify `core/presentation/viewmodels/ConnectionViewModelTest.kt` (test)

**Interfaces:** Consumes Tasks 4-5. El constructor cambia `wifiFailoverController` y `criticalNetworkOperationManager` por `wifiSinSalidaMonitor: WifiSinSalidaMonitor` y `controlDeWifi: ControlDeWifi` (el VM ya no toca el radio ni la guarda). `tpvSettingsRepository` se queda (también lo usa `refreshFromTerminalConfig`, ~L621).

- [ ] **Step 1: Pruebas — borrar las 5 del failover viejo y poner al día el helper**

Borrar `auto enforced disables wifi after threshold when wifi is degraded`, `auto enforced does not toggle wifi during critical operations`, `auto enforced does not restore wifi while cellular remains unhealthy`, `auto enforced restores wifi when cellular is healthy` y `auto shadow never toggles wifi`: prueban código que se elimina (lentitud, rachas, permanencia en celular). Sus garantías viven ahora en `ControlDeWifiTest` y en las pruebas de abajo.
Helper: quitar `wifiFailoverController` y `criticalNetworkOperationManager`. Agregar `wifiSinSalidaMonitor = mockk(relaxed = true)` con `coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.SANO`, y `controlDeWifi = mockk(relaxed = true)` con `coEvery { controlDeWifi.reiniciar(any(), any()) } returns ResultadoReinicio.NoSeReinicio("prueba")` y `coEvery { controlDeWifi.restaurarSiQuedoApagado() } returns true`.

- [ ] **Step 2: Write the failing tests**

```kotlin
    private fun modo(m: CellularFailoverMode) {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings.DEFAULT.copy(cellularFailoverMode = m)
    }

    @Test
    fun `P1 wifi sin salida en enforced pide el reinicio y avisa REINICIANDO`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_ENFORCED)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        coEvery { controlDeWifi.reiniciar(any(), any()) } returns ResultadoReinicio.Reiniciado
        val vm = createViewModel(); runCurrent()
        coVerifyOrder {
            connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.REINICIANDO)
            controlDeWifi.reiniciar(any(), any())
            connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO)
        }
        verify { wifiSinSalidaMonitor.trasReiniciar() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 sin veredicto no se reinicia aunque no haya internet`() = runTest(testDispatcher) {
        // Codex v3 #4: sin red real (no sólo el estado), porque con red y heartbeat OK el arranque reescribe ambos a true.
        modo(CellularFailoverMode.AUTO_ENFORCED)
        every { networkMonitor.getCurrentNetworkInfo() } returns disconnectedNetworkInfo
        connectionSnapshotState.value = com.jaac.avoqado_tpv.core.util.ConnectionState(hasInternet = false, hasServer = false)
        val vm = createViewModel(); runCurrent()
        assertThat(connectionSnapshotState.value.hasInternet).isFalse()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `P1 si el modo cambia antes de actuar no se reinicia`() = runTest(testDispatcher) {
        every { tpvSettingsRepository.getCurrentSettings() } returnsMany listOf(
            TpvSettings.DEFAULT.copy(cellularFailoverMode = CellularFailoverMode.AUTO_ENFORCED),
        ) andThen TpvSettings.DEFAULT   // OFF desde la segunda lectura
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        every { wifiSinSalidaMonitor.sigueSinSalida() } returns true
        coEvery { controlDeWifi.reiniciar(any(), any()) } coAnswers {
            val puedeActuar = secondArg<() -> Boolean>()
            if (puedeActuar()) ResultadoReinicio.Reiniciado else ResultadoReinicio.NoSeReinicio("ya_no_aplica")
        }
        val vm = createViewModel(); runCurrent()
        verify { wifiSinSalidaMonitor.registrarAccion("no_se_reinicio:ya_no_aplica") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `sombra detecta y anota una vez pero nunca reinicia`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_SHADOW)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        val vm = createViewModel(); runCurrent()
        vm.forceCheck(); runCurrent()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        verify(exactly = 1) { wifiSinSalidaMonitor.registrarAccion("habria_reiniciado_wifi") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `al arrancar y en cada evaluacion restaura una marca heredada`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.OFF)
        val vm = createViewModel(); runCurrent()
        coVerify(atLeast = 1) { controlDeWifi.restaurarSiQuedoApagado() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `la causa guardada es la raiz, no el envoltorio`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.OFF)
        coEvery { heartbeatRepository.sendHeartbeat(any()) } returns
            Result.Error(ApiException.NetworkError(java.net.UnknownHostException("api.avoqado.io")))
        val vm = createViewModel(); runCurrent()
        vm.forceCheck(); runCurrent()
        coVerify { wifiSinSalidaMonitor.evaluar(any(), "UnknownHostException", any()) }
        vm.viewModelScope.cancel()
    }
```

Imports: `com.jaac.avoqado_tpv.core.util.WifiSinSalidaMonitor`, `ControlDeWifi`, `ResultadoReinicio`, `VeredictoWifi`, `EstadoWifiSinSalida`, `com.jaac.avoqado_tpv.core.domain.models.ApiException`, `io.mockk.coVerifyOrder`, `io.mockk.verify`.

- [ ] **Step 3: Run to verify they fail** — `… --tests "*ConnectionViewModelTest*"` ⇒ FAIL de compilación.

- [ ] **Step 4: Implement**

4a. Constructor: quitar `wifiFailoverController` y `criticalNetworkOperationManager`; agregar `private val wifiSinSalidaMonitor: WifiSinSalidaMonitor` y `private val controlDeWifi: ControlDeWifi`. **Borrar** los campos `failoverBadReadingsStreak`, `failoverHealthyCellStreak`, `lastWifiToggleAtMs`, `lastAutoWifiDisableAtMs` y `failoverTransitionInProgress`, y las funciones `evaluateCellularFailover` (el cuerpo viejo) y `failoverToggleGateReason`. Agregar:

```kotlin
    /** Clase de la excepción RAÍZ del último fallo del heartbeat (no el `ApiException.NetworkError` que la envuelve). */
    private var ultimaCausaFallo: String? = null
    private var anotadoEnSombra = false
```

4b. `markServerProbeFailed`, primera línea:
`ultimaCausaFallo = generateSequence(cause) { it.cause }.lastOrNull()?.javaClass?.simpleName ?: source`.

4c. `evaluateCellularFailover` nuevo:

```kotlin
    private suspend fun evaluateCellularFailover(source: String) {
        // Una marca que quedó (proceso muerto a mitad del reinicio, o un encendido no confirmado) se atiende siempre.
        controlDeWifi.restaurarSiQuedoApagado()

        val mode = tpvSettingsRepository.getCurrentSettings().cellularFailoverMode
        // Detección, aviso y evidencia para TODAS las terminales, sin tocar la red (Testarudo, 29-sep-2026).
        val veredicto = wifiSinSalidaMonitor.evaluar(System.currentTimeMillis(), ultimaCausaFallo, mode)
        if (veredicto != VeredictoWifi.WIFI_SIN_SALIDA) {
            anotadoEnSombra = false
            return
        }
        when (mode) {
            CellularFailoverMode.AUTO_SHADOW -> if (!anotadoEnSombra) {
                Timber.i("👤 [WifiSinSalida][SHADOW] Habría reiniciado el WiFi (source=$source)")
                wifiSinSalidaMonitor.registrarAccion("habria_reiniciado_wifi")
                anotadoEnSombra = true
            }
            CellularFailoverMode.AUTO_ENFORCED -> reiniciarWifi(source)
            else -> Unit   // OFF / MANUAL_TOGGLE: sólo detectar y avisar
        }
    }

    private suspend fun reiniciarWifi(source: String) {
        connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.REINICIANDO)
        // El modo y las señales se releen DENTRO de ControlDeWifi, justo antes de apagar: pudieron cambiar durante la sonda.
        val resultado = controlDeWifi.reiniciar(
            confirmarEnVivo = { wifiSinSalidaMonitor.confirmarEnVivo() },
            puedeActuar = {
            tpvSettingsRepository.getCurrentSettings().cellularFailoverMode == CellularFailoverMode.AUTO_ENFORCED &&
                wifiSinSalidaMonitor.sigueSinSalida()
        })
        when (resultado) {
            ResultadoReinicio.Reiniciado -> {
                wifiSinSalidaMonitor.registrarAccion("wifi_reiniciado")
                wifiSinSalidaMonitor.trasReiniciar()
            }
            ResultadoReinicio.Incierto -> wifiSinSalidaMonitor.registrarAccion("wifi_reinicio_incierto")
            is ResultadoReinicio.NoSeReinicio -> wifiSinSalidaMonitor.registrarAccion("no_se_reinicio:${resultado.motivo}")
        }
        Timber.i("📶 [WifiSinSalida] reinicio: $resultado (source=$source)")
        // Ya no está reiniciando. Si el WiFi volvió, el monitor lo cierra (NO) al contestar el servidor; si no, el aviso
        // vuelve a «Apaga y prende…».
        connectionStateManager.setWifiSinSalida(EstadoWifiSinSalida.DETECTADO)
    }
```

4d. Borrar los imports sin uso (`WifiFailoverController`, `CriticalNetworkOperationManager`, `NetworkType` si ya no se usa) y reemplazar el comentario de sección «PHASE 1: CELLULAR FAILOVER» por «WiFi sin salida (29-sep-2026): ver `ControlDeWifi` y `docs/superpowers/plans/2026-09-29-wifi-sin-salida-autorrecuperacion.md`».

4e. **Enmienda del 30-sep (founder: «Nexgo primero; PAX sólo con el aviso hasta probarla»).** En PAX (Android 10,
targetSdk 34) `setWifiEnabled` está bloqueado y el único camino es el canal DAL de PAX, nunca probado en hardware.
Además en prod hay una PAX dormida (`AVQD-2841548633`) ya en `AUTO_ENFORCED`. Por eso en builds PAX `AUTO_ENFORCED`
se comporta como `AUTO_SHADOW` (detecta, avisa y anota «habría reiniciado») hasta probar el DAL en una PAX real.
Función pura de nivel superior en `ConnectionViewModel.kt`:

```kotlin
/**
 * En PAX el reinicio automático del WiFi se degrada a sombra: Android 10 bloquea `setWifiEnabled` y el canal DAL de
 * PAX no se ha probado en hardware (decisión del founder, 30-sep-2026). Quitar cuando una PAX real lo acredite.
 */
internal fun modoEfectivoWifi(modo: CellularFailoverMode, esPax: Boolean): CellularFailoverMode =
    if (esPax && modo == CellularFailoverMode.AUTO_ENFORCED) CellularFailoverMode.AUTO_SHADOW else modo
```

En el VM, costura de prueba (las pruebas corren en `sandboxDebug`, que ES PAX): `@VisibleForTesting internal var
esPax: Boolean = BuildConfig.ENABLE_PAX_SDK`. `evaluateCellularFailover` usa
`val mode = modoEfectivoWifi(tpvSettingsRepository.getCurrentSettings().cellularFailoverMode, esPax)` y `puedeActuar`
relee con la misma función (`modoEfectivoWifi(…, esPax) == AUTO_ENFORCED`). El helper `createViewModel()` de las
pruebas pone `esPax = false` antes del primer `runCurrent()`, para que las pruebas de `AUTO_ENFORCED` de arriba sigan
describiendo la Nexgo. Pruebas nuevas:

```kotlin
    @Test
    fun `P1 en PAX enforced se comporta como sombra y nunca reinicia`() = runTest(testDispatcher) {
        modo(CellularFailoverMode.AUTO_ENFORCED)
        coEvery { wifiSinSalidaMonitor.evaluar(any(), any(), any()) } returns VeredictoWifi.WIFI_SIN_SALIDA
        val vm = createViewModel(esPax = true); runCurrent()
        coVerify(exactly = 0) { controlDeWifi.reiniciar(any(), any()) }
        verify(exactly = 1) { wifiSinSalidaMonitor.registrarAccion("habria_reiniciado_wifi") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `modoEfectivoWifi degrada solo enforced y solo en PAX`() {
        CellularFailoverMode.values().forEach { m ->
            assertThat(modoEfectivoWifi(m, esPax = false)).isEqualTo(m)
            val esperado = if (m == CellularFailoverMode.AUTO_ENFORCED) CellularFailoverMode.AUTO_SHADOW else m
            assertThat(modoEfectivoWifi(m, esPax = true)).isEqualTo(esperado)
        }
    }
```

El monitor recibe el modo EFECTIVO (su evidencia debe decir «sombra» en una PAX, no «enforced»).

- [ ] **Step 5: Run** — `… --tests "*ConnectionViewModelTest*" --tests "*WifiSinSalida*" --tests "*ControlDeWifiTest*" --tests "*CriticalNetworkOperationManagerTest*" --tests "*WifiFailoverControllerTest*"` ⇒ PASS.

---

### Task 7: El aviso en pantalla

**Files:** Modify `core/presentation/viewmodels/DeviceHealthViewModel.kt`, `core/presentation/components/DeviceAlertBanner.kt` · Modify `core/presentation/viewmodels/DeviceHealthViewModelTest.kt` (test) · Create `core/presentation/components/DeviceAlertBannerTextoTest.kt` (test)

**Interfaces:** Produces `DeviceAlert.WifiSinSalida(reiniciando: Boolean)`, `DeviceAlertType.WIFI_SIN_SALIDA`, `internal fun textoDelBanner(alert: DeviceAlert, retryState: ConnectionRetryState): Pair<String, String>`.

- [ ] **Step 1: Write the failing tests**

En `DeviceHealthViewModelTest` (usa Truth: `assertThat`; agregar `import com.jaac.avoqado_tpv.core.util.EstadoWifiSinSalida`), copiando el armado de `no internet alert when disconnected` (~L301) con los dos estados:

```kotlin
    @Test
    fun `wifi sin salida reemplaza a sin conexion al servidor`() = runTest(testDispatcher) {
        // mismo armado que `no internet alert when disconnected`, con
        // ConnectionState(hasInternet = true, hasServer = false, wifiSinSalida = EstadoWifiSinSalida.DETECTADO)
        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.WifiSinSalida && !it.reiniciando }).isTrue()
        assertThat(alerts.any { it is DeviceAlert.ServerDown }).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `reiniciando con el wifi apagado no dice sin conexion a internet`() = runTest(testDispatcher) {
        // ConnectionState(hasInternet = false, hasServer = false, wifiSinSalida = EstadoWifiSinSalida.REINICIANDO)
        val alerts = viewModel.activeAlerts.value
        assertThat(alerts.any { it is DeviceAlert.WifiSinSalida && it.reiniciando }).isTrue()
        assertThat(alerts.any { it is DeviceAlert.NoInternet }).isFalse()
        viewModel.viewModelScope.cancel()
    }
```

`DeviceAlertBannerTextoTest.kt`:

```kotlin
package com.jaac.avoqado_tpv.core.presentation.components

import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionRetryState
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAlertBannerTextoTest {
    @Test fun `P2 el texto del wifi sin salida no lo tapa el reintento`() {
        assertEquals(
            "El WiFi de esta terminal no está pasando datos" to "Apaga y prende el WiFi de la terminal, o usa el chip",
            textoDelBanner(DeviceAlert.WifiSinSalida(reiniciando = false), ConnectionRetryState.Failed),
        )
        assertEquals(
            "El WiFi de esta terminal no está pasando datos" to "Reiniciando el WiFi…",
            textoDelBanner(DeviceAlert.WifiSinSalida(reiniciando = true), ConnectionRetryState.Retrying),
        )
    }

    @Test fun `el servidor caido conserva su reemplazo de reintento`() {
        assertEquals(
            "No se pudo conectar" to "Los cobros se guardan y se envian al reconectar",
            textoDelBanner(DeviceAlert.ServerDown, ConnectionRetryState.Failed),
        )
        assertEquals(
            "Reconectando..." to "Verificando la conexion con el servidor",
            textoDelBanner(DeviceAlert.NoInternet, ConnectionRetryState.Retrying),
        )
    }
}
```

- [ ] **Step 2: Run to verify they fail** — `… --tests "*DeviceHealthViewModelTest*" --tests "*DeviceAlertBannerTextoTest*"` ⇒ FAIL de compilación.

- [ ] **Step 3: Implement**

`DeviceHealthViewModel.kt`: en `DeviceAlertType`, `WIFI_SIN_SALIDA,  // P2 - WiFi enlazado sin pasar datos` tras `SERVER_DOWN`. Junto a `ServerDown`:

```kotlin
    /** WiFi de ESTA terminal enlazado sin pasar datos (P2): Google tampoco contesta. Testarudo, 29-sep-2026. */
    data class WifiSinSalida(val reiniciando: Boolean) : DeviceAlert(2, DeviceAlertType.WIFI_SIN_SALIDA) {
        val message: String get() = "El WiFi de esta terminal no está pasando datos"
        val description: String get() =
            if (reiniciando) "Reiniciando el WiFi…" else "Apaga y prende el WiFi de la terminal, o usa el chip"
    }
```

`isRetryable` NO la incluye (su texto ya dice qué hacer). En `updateAlerts`, reemplazar P0 y P2 de conexión (`import com.jaac.avoqado_tpv.core.util.EstadoWifiSinSalida`):

```kotlin
        val wifi = connectionState.wifiSinSalida
        // P0: No internet — salvo que la app esté reiniciando el WiFi a propósito.
        if (!connectionState.hasInternet) {
            alerts.add(if (wifi == EstadoWifiSinSalida.REINICIANDO) DeviceAlert.WifiSinSalida(reiniciando = true) else DeviceAlert.NoInternet)
        }
        // P2: Server down — si el detector confirmó que es el WiFi de la terminal, se dice ESO.
        if (connectionState.hasInternet && !connectionState.hasServer) {
            alerts.add(
                when (wifi) {
                    EstadoWifiSinSalida.NO -> DeviceAlert.ServerDown
                    EstadoWifiSinSalida.DETECTADO -> DeviceAlert.WifiSinSalida(reiniciando = false)
                    EstadoWifiSinSalida.REINICIANDO -> DeviceAlert.WifiSinSalida(reiniciando = true)
                }
            )
        }
```

`DeviceAlertBanner.kt`: agregar antes de `AlertBannerRow`

```kotlin
/** Mensaje y descripción del banner. El reemplazo de «Reintentar» sólo aplica a NoInternet/ServerDown. */
internal fun textoDelBanner(alert: DeviceAlert, retryState: ConnectionRetryState): Pair<String, String> {
    val base = when (alert) {
        is DeviceAlert.UpdateAvailable -> alert.message to alert.description
        is DeviceAlert.NoInternet -> alert.message to alert.description
        is DeviceAlert.BatteryCritical -> alert.message to alert.description
        is DeviceAlert.ServerDown -> alert.message to alert.description
        is DeviceAlert.WifiSinSalida -> alert.message to alert.description
        is DeviceAlert.SlowConnection -> alert.message to alert.description
        is DeviceAlert.PendingPayments -> alert.message to alert.description
        is DeviceAlert.BatteryLow -> alert.message to alert.description
        is DeviceAlert.StorageLow -> alert.message to alert.description
        is DeviceAlert.WeakWifi -> alert.message to alert.description
        is DeviceAlert.MemoryLow -> alert.message to alert.description
    }
    val esDeConexion = alert is DeviceAlert.NoInternet || alert is DeviceAlert.ServerDown
    return when {
        retryState is ConnectionRetryState.Retrying && esDeConexion -> "Reconectando..." to "Verificando la conexion con el servidor"
        retryState is ConnectionRetryState.Failed && esDeConexion -> "No se pudo conectar" to "Los cobros se guardan y se envian al reconectar"
        else -> base
    }
}
```

En `AlertBannerRow`: borrar los `when` de `baseMessage`/`baseDescription` y los `message`/`description` calculados, y usar `val (message, description) = textoDelBanner(alert, retryState)`. Si más abajo se usa `isRetrying` (spinner), conservarlo como `val isRetrying = retryState is ConnectionRetryState.Retrying && (alert is DeviceAlert.NoInternet || alert is DeviceAlert.ServerDown)`. En el `when` de `icon`: `is DeviceAlert.WifiSinSalida -> Icons.Default.SignalWifiStatusbarConnectedNoInternet4`. El compilador señala cualquier otro `when` exhaustivo sobre `DeviceAlert`.

- [ ] **Step 4: Run** ⇒ PASS.

---

### Task 8: CHANGELOG + suite completa + variantes

- [ ] **Step 1:** `wc -c /Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/CHANGELOG.md`: si pasa de 50000, rotar (`changelog-policy.md`).
- [ ] **Step 2:** bajo `## [Unreleased]`:

```markdown
### **Fixed**
- **[PAX] [Nexgo] WiFi «conectado pero sin datos»**: la terminal lo detecta (~2 min: servidor y socket caídos y Google sin transporte), lo dice claro («El WiFi de esta terminal no está pasando datos») y deja evidencia `WifiSinSalida` al confirmarlo y al resolverse. Con la conmutación en «Auto (aplicar)» hace sola lo que arregló a Testarudo: apaga el WiFi ~3 s y lo vuelve a prender, con un solo dueño (`ControlDeWifi`), nunca encima de un cobro o una devolución, con una marca en disco antes de apagar y máximo 3 veces por hora. Caso: Nexgo de Testarudo 38 min muda con el WiFi del local sano (29-sep).
- **[Nexgo] La guarda de «operación crítica» no veía los cobros ni las devoluciones de AngelPay**: `CriticalNetworkOperationManager` lee `PaymentStateProvider`.
- **[PAX] [Nexgo] `WifiFailoverController` se tragaba la cancelación** y devolvía «no se apagó» aunque el apagado ya había salido hacia Android.

### **Removed**
- **[PAX] [Nexgo] Conmutación automática a celular por WiFi lento**: nunca corrió en la calle y, sin chip con datos, dejaba el WiFi apagado para siempre. `cellularFailoverBadReadingsThreshold`, `cellularFailoverCooldownSeconds` y `cellularFailoverMinCellHoldSeconds` dejan de usarse en la app.
```

- [ ] **Step 3:** `cd /Users/amieva/Documents/Programming/Avoqado && JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./scripts/avq-verify.sh avoqado-tpv ./gradlew testSandboxDebugUnitTest` ⇒ 0 fallos (leer `Tests:` y los XML). Falla en un archivo NO tocado aquí ⇒ `git diff` de ese archivo: si es WIP ajeno, anotarlo y no tocarlo.
- [ ] **Step 4:** `cd /Users/amieva/Documents/Programming/Avoqado && ./scripts/avq-verify.sh avoqado-tpv ./gradlew compileProductionDebugKotlin compileNexgoProdDebugKotlin lint --continue` ⇒ `BUILD SUCCESSFUL`, sin lint nuevo en los archivos tocados.

---

### Task 9: Prueba en hardware (Nexgo N86 de la oficina, por USB)

Veredicto en tres lados: **logcat** (`adb -s N86 logcat | grep -E "WifiSinSalida|ControlDeWifi|WifiFailoverController"`), **pantalla** (`adb -s N86 exec-out screencap -p > …png`) y **radio** (`adb -s N86 shell dumpsys wifi | grep -m1 "Wi-Fi is"`). Anotar horas reales.

- [ ] **Step 1: ¿El aparato está libre?** `adb -s N86 shell dumpsys package com.jaac.avoqado_tpv.sandbox | grep -m1 lastUpdateTime; adb -s N86 shell dumpsys activity activities | grep -m1 mResumedActivity` + Huella `quien_trabaja` (tpv y android). Si otra sesión lo usa: no instalar; declarar pendiente con esta receta.
- [ ] **Step 2: Build e instalación** — `./scripts/avq-verify.sh avoqado-tpv ./gradlew assembleNexgoDebug`; `adb -s N86 install -r <APK producido>`. Iniciar sesión. En `nexgoDebug` `ObservabilityManager` no queda habilitado solo (`HomeViewModel` no pasa `enableInDebug`): la evidencia se verifica en logcat (Timber) y se declara que el envío a `TerminalLog` no se probó en debug. Terminal de QA en «Auto (aplicar)» en el servidor al que apunta el build (**nunca** en producción).
- [ ] **Step 3: Red SIN salida real.** Un DNS privado inválido NO sirve (no corta conexiones ya abiertas). Opciones: (a) hotspot de un teléfono Android con datos móviles APAGADOS; (b) un router con el cable de internet desconectado. **Requiere al founder o a alguien en la oficina: pedirlo en MAYÚSCULAS con la acción exacta.** Precondiciones OBSERVADAS antes de esperar: WiFi asociado, socket desconectado en logcat, sonda externa fallando por transporte. Esperar ~2-3 min: aviso, `WiFi sin salida confirmado`, `wifi_sin_salida` apagado y `:prender` ~3 s después; con la red aún muerta, otra confirmación ≥ 60 s después y no más de 3 reinicios en la hora. Volver a la red buena: `WiFi sin salida resuelto` con `duracionS` y `acciones`, y el aviso desaparece.
- [ ] **Step 4: Nunca encima de un cobro** (SIN tarjeta: «PORFAVOR NO PASES TARJETA»): **con la red sana**, abrir Pago rápido y llegar a la pantalla del SDK; **después** quitar la salida a internet (paso 3). Mientras la pantalla de cobro siga activa: cero apagados y `no_se_reinicio:cobro_en_curso` en logcat. Si el SDK termina por timeout antes de que el detector confirme, esta celda se declara **inconclusa** (no probada). Cancelar ⇒ el reinicio ocurre en el siguiente ciclo.
- [ ] **Step 5: Proceso muerto a mitad del reinicio:** en cuanto logcat muestre el apagado `wifi_sin_salida`, `adb -s N86 shell am kill com.jaac.avoqado_tpv.sandbox` con la app en segundo plano (NO `force-stop`). Abrir la app: el WiFi se prende (`restaurar_marca`). Registrar tiempos.
- [ ] **Step 6: Reporte:** celdas ejecutadas, no ejecutadas y no aplicables, con justificación. La PAX (Android 10, canal DAL) queda pendiente si no hay una conectada. Separar lo observado de lo inferido.

---

## Self-Review

- **Cobertura:** reglas 1 (T3, T4), 2 (T6), 3-7 (T5), 8 (T3, T4, T6). Pantalla T7. Evidencia T4. Guarda T1-T2. Hardware T9.
- **Firmas consistentes:** `WifiSinSalidaMonitor.evaluar(ahoraMs, causaUltimoFallo, modo)`, `sigueSinSalida()`, `registrarAccion(…)`, `trasReiniciar()` (T4 = T6); `ControlDeWifi.reiniciar(puedeActuar)`, `restaurarSiQuedoApagado()` (T5 = T6); `ResultadoReinicio.{Reiniciado, NoSeReinicio(motivo), Incierto}` (T5 = T6); `setWifiEnabled(enabled, source, antesDelCanalPax)` (T2 = T5); `EstadoWifiSinSalida.{NO, DETECTADO, REINICIANDO}` (T3 = T4 = T6 = T7).
- **Fuera de alcance a propósito:** servidor, dashboard, superadmin y MCP; el default del servidor sigue OFF; sin conmutación a celular, sin worker, sin persistir el incidente y sin `reportNetworkConnectivity`; el toggle manual de `SuperAdminScreen` no cambia.
