# Audit forense — bug "Procesando chip" en Doña Simona

**Handoff para segunda opinión LLM. Fecha: 2026-05-04. App version: 1.13.1.**

> Este documento es puramente forense — qué pasó, dónde está el bug en código, qué sabemos y qué no. NO contiene recomendaciones de fix; ese análisis se delega al LLM auditor.

---

## 1. Identificación del incidente

| Campo | Valor |
|---|---|
| Terminal serial físico | `2840744151` |
| Backend Terminal ID | `AVQD-2840744151` |
| Venue ID | `cmn3acoxt000mn227k1vdgj95` |
| User ID activo | `cmoix9ynr011kko29e0uw3fkf` (rol ADMIN) |
| Terminal CUID | `cmoivefhr00wxko296whmaf3q` |
| App version | `1.13.1` (build 56) |
| Build variant | `productionRelease` |
| Modelo | PAX A910S (Android 10, ARMv7, 720x1280@320dpi) |
| Fecha del incidente | 2026-05-04 ~13:03 CST (19:03 UTC) |

---

## 2. Síntoma reportado (con evidencia visual)

1. Operador inserta tarjeta chip en lectora.
2. App transiciona a screen "Pago con Tarjeta" → spinner + texto **"Procesando chip..."**.
3. A los 30 segundos aparece texto rojo **"Esto está tomando más de lo normal. Verifica tu conexión a internet."**
4. Spinner permanece indefinidamente. NO progresa a "Procesando pago" ni a Success/Error.
5. **No reinicio del device. SOLO matar/reabrir la app de Avoqado** (vía comando remoto `RESTART` del dashboard) resuelve el problema.
6. Después del restart, **misma tarjeta + mismo monto** procesa exitosamente.

---

## 3. Timeline forense (logs Render del backend, UTC)

```
18:57:55.110  Socket.IO connected: socketId=BQ5BtfpHJV4J4-TUAADu
              user=cmoix9ynr011kko29e0uw3fkf venue=cmn3acoxt000mn227k1vdgj95

18:57:55.294  Room join completado

18:57:55 → 19:03:17  Polling normal del Home cada ~30s:
                     /shift, /shifts, /sales-goals, /time-entries, /products,
                     /messages/pending, /verification/pending

[ESPERADO ~19:02:55: heartbeat #2 (intervalo 5 min) — NO LLEGÓ]

19:03:17.039  GET /shift 200 [182ms]  ← último request del Home antes del fallo

[~19:03:30 estimado: operador inserta tarjeta — UI muestra "Procesando chip"]

19:04:03.964  📡 Heartbeat REGISTERING terminal AVQD-2840744151
              socket=BQ5BtfpHJV4J4-TUAADu (MISMO socket que 18:57:55)
              ← LLEGA 70 SEGUNDOS TARDE respecto al esperado de 19:02:55

19:04:04.088  ✅ Terminal heartbeat health: 100/100

19:04:13.993  GET /messages/history?terminalId=cmoivefhr00wxko296whmaf3q  200
19:04:14.029  GET /auth/permissions  200 [142ms]
19:04:14.040  GET /auth/permissions  200 [153ms]   ← DUPLICADO 11ms después
19:04:14.400  GET /verification/pending  200

[19:04:14 → 19:05:46: SILENCIO TOTAL — ningún request del device, ningún socket event]

19:05:46.385  📡 TPV command broadcast: RESTART → AVQD-2840744151
              ← acción humana desde dashboard

19:06:05.415  📡 Socket disconnected: BQ5BtfpHJV4J4-TUAADu
              ← app se está matando por el RESTART

19:06:50.533  GET /terminals/AVQD-2840744151/config  ← app fresh boot
19:06:50.929  GET /activation-status?environment=PROD
19:06:51.055  Terminal activated (activatedAt 2026-04-28)
19:06:50 → 19:06:58  Burst típico de re-init (config x4, activation x2, products,
                     permissions, verification, shifts, sales-goals, etc.)

19:07:00.354  [Observability] Observability system initialized  ← timber framework reinicia

19:10:42.914  Recording fast payment
19:10:45.371  POST /tpv/venues/.../fast  201 [2.4s]  ← cobro EXITOSO con misma tarjeta
```

**Hechos críticos a verificar:**
1. El socket `BQ5BtfpHJV4J4-TUAADu` permaneció **el mismo** entre 18:57:55 y 19:06:05 — no hubo reconexión TCP/socket. Lo que se atrasó fue el evento `tpv:heartbeat` emitido por la app.
2. El heartbeat es enviado por `HealthMonitor.kt:75-79` vía `socketManager.emit("tpv:heartbeat", payload)` dentro de `while(isActive) { sendHeartbeat(); delay(5*60*1000) }`. El delay debió ejecutar exactamente 5 minutos pero ejecutó **6 minutos 8 segundos**.
3. Durante 90 minutos (18:30-19:00 — extendí la ventana de logs) no hay UN solo error a backend del terminal. El device estaba sano. Excepto el delay del coroutine.

---

## 4. Crashlytics (Firebase, app `1:219752736783:android:d09cd5eb6162e7ee52db7a`)

**Ventana**: 7 días (2026-04-27 → 2026-05-04). Fleet completo de PAX A910S.

| Issue | Eventos | First seen | Last seen |
|---|---|---|---|
| Socket Token expired (`SocketManager.onConnectError`) | 550 | 1.13.1 | 1.13.2 |
| Socket websocket error | 465 | 1.11.1 | 1.11.1 |
| Cell location 404 | 219 | 1.11.0 | 1.11.0 |
| VersionGate timeout (`SocketTimeoutException`) | 166 | 1.10.9 | 1.13.2 |
| DNS UnknownHostException (`api.avoqado.io`) | 75 | 1.10.9 | 1.13.2 |

**Hecho clave**: en eventos con custom key `app_terminal_serial` (presente desde v1.12.1+), el serial `2840744151` **no aparece NI UNA SOLA VEZ** como originador físico del evento. Solo aparece como `terminal_id: AVQD-2840744151` en 5 eventos cuyo `app_terminal_serial` real fue `2841548417` (device de testing del developer).

**Inferencia** (no certeza): el dispositivo físico de Doña Simona no ha emitido eventos a Crashlytics en 7 días. Posibles causas: (a) v1.13.1 no genera non-fatals para este patrón de hang, (b) los eventos quedan queued localmente sin upload, (c) versión instalada distinta a la que se reporta en dashboard.

---

## 5. Localización del bug en código

Archivo: `app/src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt`

### Punto crítico 1 — collector con caso `false` no manejado

```kotlin
// Líneas 533-553
viewModelScope.launch {
    transProcessRepository.confirmCardReadingFlow().collect { confirmed ->
        Timber.d("✅ [Card Reading] Confirmed: $confirmed")

        // ⭐ CRITICAL: SDK waits for our response via ContinueConfirmCardUseCase
        // Without this response, StartEmvTransUseCase blocks indefinitely  ← COMENTARIO LITERAL
        if (confirmed) {
            Timber.i("🔄 [Card Reading] Responding with ContinueConfirmCard...")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val params = ContinueConfirmCardParams(emvCode = 0)
                    continueConfirmCardUseCase.runInfallible(params)
                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to send ContinueConfirmCard response")
                }
            }
        }
        // ← NO HAY else. Si confirmed==false, SDK queda esperando para siempre.
    }
}
```

### Punto crítico 2 — llamada bloqueante sin timeout

```kotlin
// Líneas 2506-2510
// PASO 3: StartEmvTrans (process chip locally)
_state.value = PaymentState.Processing("Procesando chip...")
Timber.i("[PHASE 3] StartEmvTrans - Processing EMV chip...")
val emvParams = StartEmvTransParams()
val emvResult = startEmvTransUseCase.run(emvParams)  // ← suspending, JNI subyacente, sin withTimeout
```

### Punto crítico 3 — duplicado en flujo de refund

Línea 7141: misma llamada `startEmvTransUseCase.run(emvParams)` en `processChipRefund()`. También sin timeout.

### Punto crítico 4 — guard global

```kotlin
// Línea 263-264
private val _isPaymentInProgress = MutableStateFlow(false)
val isPaymentInProgress: StateFlow<Boolean> = _isPaymentInProgress.asStateFlow()

// Línea 2118
_isPaymentInProgress.value = true
```

Si la coroutine queda colgada en línea 2510, **el guard no se libera**, bloqueando todos los siguientes intentos hasta que el ViewModel se destruya (= app restart).

### Punto crítico 5 — colectores en `viewModelScope`

`collectPinDialogFlows()` (línea 474) registra 6 collectors a `transProcessRepository`. Tiene un guard `pinDialogFlowsStarted: Boolean` (member variable, se resetea con cada nuevo VM). El `transProcessRepository` es Hilt **Singleton** scope (compartido entre VMs).

---

## 6. Arquitectura relevante

- **Hilt DI**: `transProcessRepository` (de Blumon SDK closed-source) es `@Singleton` — sobrevive recreaciones de ViewModel.
- **6 flows que el SDK expone**: `getEventPinDialogStateFlow()`, `getKeyboardPinStateFlow()`, `getPinResultFlow()`, `getPinAttemptsFlow()`, `getSelectAppStateFlow()`, `confirmCardReadingFlow()`. Su naturaleza (cold vs hot, StateFlow vs SharedFlow vs Flow plain) es **desconocida** sin acceso al fuente del SDK.
- **8 features comparten PaymentViewModel**: Fast Pay, Quick Order, Table Service, Pay Later, Serialized Inventory, Split Payments, Refunds, Kiosk. Variantes sandbox/production tienen archivos PaymentViewModel.kt separados que deben mantenerse sincronizados.
- **Heartbeat**: `HealthMonitor.kt`, intervalo 5 min, vía `socketManager.emit("tpv:heartbeat", payload)`.
- **Build pipeline crítico**: Firma PAX requiere 3-5 días desde APK hasta terminales de producción. NO hay Firebase Remote Config en el proyecto, por lo que rollback de cambios de comportamiento requiere otro APK firmado (3-5 días adicionales).
- **Heartbeat backend handler**: `src/communication/sockets/controllers/observability.controller.ts:234` — registra cada heartbeat recibido con `Registering terminal` log.

---

## 7. Hipótesis de causa raíz (probabilidad estimada, no certeza)

| # | Hipótesis | Evidencia a favor | Evidencia en contra | Cómo confirmar |
|---|---|---|---|---|
| H1 | `confirmCardReadingFlow` emite `false` y nuestro código lo ignora silenciosamente | Comentario explícito línea 539, branch `else` ausente | Asume que SDK alguna vez emite `false` — desconocido | Log explícito en `else`, esperar reproducción |
| H2 | Doze/App Standby/PAX vendor power management throttea el proceso | Heartbeat 70s tarde, sin actividad backend 1.5 min | App estaba en foreground (PaymentScreen), Doze normalmente no aplica a foreground | `dumpsys deviceidle` durante reproducción |
| H3 | Race condition: VM destruido + nuevo VM, callback Blumon registrado al VM viejo | `transProcessRepository` es Singleton, collectors mueren con vmScope | Sin acceso a fuente Blumon SDK no podemos validar registro de callbacks | Logs con `vmHash=${hashCode()}` en cada collector |
| H4 | Contaminación de transacción anterior — kernel EMV en estado inconsistente | v1.13.1 fixeó un caso similar (socket-cancel state contamination) | El siguiente cobro post-restart funciona bien con misma tarjeta | Reproducir secuencia: cancel → nuevo cobro inmediato |
| H5 | Socket duplicado / reauth deja `transProcessRepository` con estado stale | `/auth/permissions` duplicado en 11ms | El socket nunca cambió ID en el incidente | Logs detallados de SocketManager auth |

**Las 5 son posibles, ninguna está confirmada por evidencia directa.** El throttle de 70s es lo único anómalo medible objetivamente.

---

## 8. Comportamiento de UI durante el hang (`PaymentScreen.kt`)

```kotlin
// Líneas 671-688: estado Processing maneja timeouts progresivos
is PaymentState.Processing -> {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentState) {
        elapsedSeconds = 0
        while (true) { kotlinx.coroutines.delay(1_000); elapsedSeconds++ }
    }
    PaymentLoadingContent(
        message = currentState.message,
        showTimeoutWarning = elapsedSeconds >= 30,        // texto rojo aparece a 30s
        onCancel = if (elapsedSeconds >= 45) {{ viewModel.cancelPayment() }} else null  // botón Cancelar a 45s
    )
}
```

El botón "Cancelar" se vuelve clickable a los 45s. **No tenemos evidencia de si la merchant lo intentó o no.** Si lo tocó y tampoco respondió, el problema es más profundo (la cancelación misma queda esperando estado del SDK).

---

## 9. Lo que NO sabemos

1. **El comportamiento real del SDK Blumon** — `confirmCardReadingFlow()`, `StartEmvTrans`, `ContinueConfirmCard` son closed-source. La documentación oficial está en `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/production/SDK-PAX-1.11.0.2-DocV4.docx` pero no la hemos consultado para este caso.
2. **Si el thread JNI subyacente respeta `Job.cancel()`** o sigue ejecutando hasta completar la operación nativa (probable lo segundo).
3. **Cuántas veces ha ocurrido este bug en otras terminales** — Crashlytics no captura el hang porque no se lanza excepción.
4. **El estado del kernel EMV después de un timeout forzado** — si llamamos a `StopDetectCard` o `CancelIcc` sobre un kernel hung, comportamiento del SDK indefinido.
5. **Si los 6 collectors quedan con referencias colgadas** después de timeout — sin código fuente del SDK no podemos saber si emisiones zombie llegan al collector.

---

## 10. Pregunta directa para el LLM auditor

> **Dada esta evidencia, ¿cuál es la causa raíz más probable y cuál es el fix mínimo, defendible y rollback-friendly que NO arriesgue romper las 8 features de pago en las ~100 terminales del fleet, considerando que el ciclo de deploy es 3-5 días con firma PAX y NO existe Firebase Remote Config en la app para apagar features remotamente?**

Adjunto a este mensaje:
- Repo TPV: `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv` (rama `main`, último commit `ebe6b2c`)
- Repo backend: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server` (rama `main`)
- Manual SDK Blumon: `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/production/SDK-PAX-1.11.0.2-DocV4.docx`
- Backend dashboard: https://api.avoqado.io
- Render service: `srv-d2oe3gggjchc73elk460` (avoqado-server prod)
- Firebase project: `avoqado-d0a24`, app ID `1:219752736783:android:d09cd5eb6162e7ee52db7a`

---

## Anexo A — restricciones operativas

- Cliente clave (Doña Simona) ya está perdiendo confianza por incidentes repetidos. Una nueva regresión es inaceptable.
- El fleet tiene ~100 terminales activas con 8 flujos de pago distintos compartiendo el mismo `PaymentViewModel`. Cualquier cambio debe NO romper ninguno de esos flujos.
- Sin Firebase Remote Config en la app: **no hay kill switch remoto** para deshabilitar comportamiento nuevo. Una regresión exige 3-5 días para revertir vía firma PAX.
- Las variantes sandbox y production tienen `PaymentViewModel.kt` separados — cualquier fix debe sincronizarse entre ambos.
- Hay 220 unit tests existentes (`./gradlew testSandboxDebugUnitTest`) que deben continuar pasando.
- El build pipeline produce `assembleProductionRelease`, firma con apksigner v2 (no jarsigner), envío a Blumon → Blumon firma con cert PAX → distribución a terminales.

## Anexo B — Logs Render obtenidos

Comando reproducible para auditar:

```bash
render logs --resources srv-d2oe3gggjchc73elk460 \
  --start 2026-05-04T18:30:00Z --end 2026-05-04T19:15:00Z \
  --text "AVQD-2840744151" --limit 200 -o json
```

## Anexo C — Crashlytics Firebase MCP queries

```
appId: 1:219752736783:android:d09cd5eb6162e7ee52db7a (production)
appId: 1:219752736783:android:aa8d57cc3022eb9c52db7a (sandbox)

# Listar top issues últimos 7 días
crashlytics_get_report(appId, report="topIssues",
  filter={intervalStartTime: "2026-04-27T00:00:00Z",
          intervalEndTime: "2026-05-04T23:59:59Z"})

# Eventos de un issue específico
crashlytics_list_events(appId,
  filter={issueId: "<hex>",
          intervalStartTime: "...", intervalEndTime: "..."},
  pageSize=50)
```

Custom keys presentes en eventos v1.12.1+:
- `app_build_variant` (release/production)
- `app_environment` (PROD)
- `app_terminal_serial` (serial físico del device)
- `app_version`, `app_version_code`, `app_version_name`
- `blumon_env` (PROD)
- `blumon_sdk_status` (READY)
- `network_internet`, `network_latency_ms`, `network_server`, `network_slow`
- `terminal_id` (formato `AVQD-{serial}`, asignado por backend)
- `venue_id`

---

## Anexo D — Conclusión del auditor Codex (2026-05-04)

### Crashlytics MCP

Se instaló Firebase MCP vía `firebase-tools mcp` y se consultó Crashlytics antes de modificar código.

Resultado para `appId=1:219752736783:android:d09cd5eb6162e7ee52db7a`, ventana `2026-04-27T00:00:00Z` → `2026-05-04T23:59:59Z`:

- `ANR`: 0 resultados.
- `FATAL`: 1 issue viejo en `1.10.2` (`lateinit property dal has not been initialized`), no relacionado con Doña Simona ni `1.13.1`.
- `NON_FATAL/topIssues`: predominan socket token expired, websocket error, location 404, VersionGate timeout y DNS; no hay señal directa del hang de chip.

Conclusión: Crashlytics confirma que este patrón no está instrumentado como crash/non-fatal. No descarta el bug; solo explica por qué no hay stack trace.

### Corrección de hipótesis H1

`confirmCardReadingFlow(false)` NO es la causa raíz más probable.

Al decompilar `app/libs/blumon_sdk-prod.aar`, `TransProcessRepositoryImpl.confirmCardReadingFlow` aparece como `MutableStateFlow(false)` inicial. El SDK llama `showConfirmCard()`, emite `true`, bloquea en `confirmCardReadingCv`, y solo entonces espera `ContinueConfirmCardUseCase`. Por lo tanto:

- `false` inicial significa idle, no rechazo.
- Responder con `ContinueConfirmCard` cuando `confirmed == false` sería riesgoso.
- El código actual sí responde correctamente cuando `confirmed == true`.

### Causa raíz más probable

El hang defendible está en `getSelectAppStateFlow()`.

El SDK Blumon implementa `onWaitAppSelect(boolean, List<CandidateAID>)` así:

1. Si la lista está vacía/null, retorna error.
2. Si hay candidatos, emite la lista en `selectAppStateFlow`.
3. Cierra `appSelectCv` y bloquea sin timeout.
4. Solo continúa cuando alguien llama `SetSelectAppCodeUseCase`.

El TPV observaba ese flow pero no respondía:

```kotlin
transProcessRepository.getSelectAppStateFlow().collect { candidateList ->
    Timber.d("📱 [App Selection] Available apps: ${candidateList?.size ?: 0}")
    // SDK automatically selects the best matching app
    // We just need to collect this flow for the SDK to proceed
}
```

Ese comentario era falso para tarjetas con múltiples aplicaciones EMV. En ese caso `StartEmvTransUseCase.run(StartEmvTransParams())` queda esperando indefinidamente, la UI permanece en `Procesando chip...`, no hay excepción y `_isPaymentInProgress` queda bloqueado hasta destruir el ViewModel/app.

### Fix aplicado

Fix mínimo, rollback-friendly y sincronizado en `sandbox/` + `production/`:

- Inyectar `SetSelectAppCodeUseCase`.
- En `collectPinDialogFlows()`, si `candidateList` no está vacío, auto-seleccionar el índice `0` con `SetSelectAppCodeParams(0)`.
- No tocar la rama `confirmCardReadingFlow(false)`.
- No meter timeout sobre `StartEmvTransUseCase` como fix primario porque el bloqueo subyacente puede estar en JNI/ConditionVariable y `Job.cancel()` no necesariamente libera el kernel EMV.

Validación local:

- `./gradlew testSandboxDebugUnitTest --tests com.jaac.avoqado_tpv.features.payment.presentation.PaymentViewModelTest`
- `./gradlew compileProductionDebugKotlin`

Ambos verdes con Java 23.

### Observabilidad agregada

Para cubrir el caso "vuelve a colgarse pero no crashea", se agregó instrumentación explícita en Crashlytics:

- Custom keys:
  - `payment_emv_stage`
  - `payment_emv_flow_origin`
  - `payment_emv_message`
  - `payment_emv_app_count`
  - `payment_emv_selected_app_index`
  - `payment_emv_elapsed_seconds`
- Breadcrumbs en:
  - inicio de `StartEmvTrans` de venta
  - inicio de `StartEmvTrans` de refund
  - solicitud de selección de aplicación EMV
  - respuesta `SetSelectAppCode`
- Non-fatal:
  - `PaymentEmvStallException("Payment EMV chip processing stuck for 45s")` cuando la UI sigue en `Procesando chip...` o `Procesando reembolso con chip...` por 45s.

Ese non-fatal no cambia comportamiento del cobro. Solo fuerza evidencia en Crashlytics para un hang que normalmente no genera stack trace.

### Actualización por reporte "todas las tarjetas" después de idle

La conversación del 04-may-2026 con Doña Simona cambia la hipótesis: si falló con Visa, Mastercard y Amex, y solamente se corrigió al reiniciar la TPV, entonces el problema no puede explicarse únicamente por una tarjeta con múltiples aplicaciones EMV.

La lectura más probable ahora es doble:

1. El bug de selección EMV sigue siendo real y corregible: si el SDK pide `SetSelectAppCodeUseCase`, antes nos quedábamos colgados.
2. Además puede existir estado viejo en Blumon/Neptune polling después de dejar la terminal encendida, cargando y con la pantalla apagada. Reiniciar la app limpia ese estado; por eso el mismo comercio pudo cobrar después del reinicio.

Mitigación agregada:

- Antes de cada cobro con tarjeta y reembolso, ejecutar `StopDetectCardUseCase(StopDetectCardParams())` como preflight antes de `PreTrans`.
- Si había un polling anterior vivo, se limpia antes de abrir uno nuevo.
- Si no había polling vivo y el SDK devuelve error/no-op, se ignora y el cobro normal continúa.
- Se deja breadcrumb Crashlytics `EMV preflight StopDetectCard...` para saber si el preflight ocurrió antes de un futuro stall.

Riesgo esperado: bajo. No cambia monto, merchant, backend, MSI, autorización ni tags EMV; solo evita iniciar un nuevo `PreTrans -> StartDetectCard -> StartEmvTrans` encima de un detector anterior potencialmente atorado.
