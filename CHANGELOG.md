# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-2.md` (newest) and `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Fixed**

- **Vender SIM: ya no se puede re-enviar una venta que ya completó**: tras una venta exitosa, `SerializedSaleScreen` conservaba el SIM escaneado y el botón "Vender" activo; si el promotor regresaba (back) a media captura de foto/pago y volvía a presionar "Vender", el backend rechazaba con 400 "Item ya fue vendido" (6 casos en producción 30-jun/1-jul, 5 SIMs de intercambio, todos rechazados por el guard — cero duplicados). Ahora `onConfirmSale` resetea el estado (`returnToScanner()`) inmediatamente después de disparar la navegación; la navegación no se afecta porque el payload viaja por callback, no por uiState. Complementa el `resetOnEnter` existente que solo cubría el retorno feliz desde pago. Cambio hermano en backend (avoqado-server): ese 400 esperado ahora se loguea como `warn` en vez de `error` para no ensuciar dashboards.

### **Changed**

- **Self-update: no intentar el fallback PAX SDK cuando el sistema pide confirmación humana (Android 10+)**: investigación del fallo de instalación en AVQD-2841653112 (2026-07-01) reveló que **cada** self-update de la flota (215 eventos, 37 terminales) dispara el diálogo nativo `STATUS_PENDING_USER_ACTION` — el APK firmado por PAX **no** tiene privilegio de instalación silenciosa, contrario a lo que asumía el código. Cuando nadie confirma el diálogo en 120s, el timeout disparaba el fallback PAX SDK, que en Android 10+ está condenado a fallar por FUSE (100% de fallas históricas) y generaba el log engañoso `ALL install strategies failed`. Ahora, si el diálogo apareció y es Android 10+, se omite el fallback y se reporta el timeout directo (`ApkInstaller.pendingUserConfirmation`, seteado por `InstallResultReceiver` tras validar sessionId). Android ≤9 conserva el fallback intacto (ahí sí funciona). Mismo mensaje/retry para el usuario; el CRITICAL de `SelfUpdateVM Install failed` sigue emitiéndose. **Pendiente (fuera de código)**: gestionar con Blumon/PAX el privilegio `INSTALL_PACKAGES`/firma de plataforma para que el update sea realmente silencioso.

---

## [2.6.3] - 2026-07-03

### **Changed**

- **[Nexgo] AngelPay SDK 1.0.13 → 1.0.15** (entregable directo de AngelPay por el incidente del 3 jul, md5 verificado contra iCloud `Socios/AngelPay/dev/sdk_propio/1.15/`): 4 referencias de gradle actualizadas. Verificado por diff de bytecode: el flujo de registro pre-cobro (`b0/j` + `p/b`) es **byte-idéntico** a 1.0.13 — el fix real del incidente fue del lado servidor de AngelPay. Lo único nuevo del AAR: **escáner QR** (`QrScannerActivity`, `AngelPayQrScannerContract`, `QrScanResult`) — NO integrado (requiere decisión de tier antes de exponer UI). **Validado en hardware** (N86 AVQD-N860W175781, venue Amaena): cobro de prueba pasó el registro y llegó a lectura de tarjeta. Suite 569/0-fail + lint verdes con el AAR nuevo.

- **[Nexgo] AngelPay SDK 1.0.10 → 1.0.13**: se vendoreó `angelpaySDK-v1.0.13-fat-release.aar` (md5 verificado contra el entregable del vendor, iCloud `Socios/AngelPay/dev/sdk_propio/1.13/`) y se actualizaron las 4 referencias de gradle (compileOnly + nexgoImplementation + nexgoProdImplementation + testImplementation). Cambio **100% aditivo** verificado por diff de bytecode: `AppErrorCatalog` byte-idéntico (97 códigos, D308 intacto), librerías nativas EMV byte-idénticas (checksums iguales en arm64-v8a y armeabi-v7a), manifest idéntico, y ninguna firma usada por la TPV cambió. Lo único nuevo: **Ligas de Pago** (`createPaymentLink`/`getPaymentLinks` + modelos `PaymentLink`) — capacidad disponible pero NO integrada aún (requiere decisión de tier antes de construir UI). Verificado: `compileSandboxDebugKotlin` + `compileNexgoDebugKotlin` (la variante que empaqueta el AAR) + suite completa 566/0-fail + `lint --continue`, todo verde. ⚠️ Pendiente: smoke test en Nexgo físico.

### **Fixed**

- **[Nexgo] Sesión AngelPay expirada (D308) ahora se recupera sola durante el cobro**: cuando el SDK de AngelPay devolvía `D308` ("Sesión Expirada" — típico tras horas de terminal inactiva entre ventas de promotor), la app solo mostraba el error y la venta fallaba hasta reiniciar la app. El `ensureAuthenticated()` previo al cobro no lo prevenía porque el SDK sigue reportando `isAuthenticated()=true` localmente aunque la sesión del servidor ya murió. **Fix** (`AngelPayPaymentViewModel`): al recibir `D308` en el `PaymentResult`, la app hace `handleAuthExpiry()` (logout + re-auth completa con re-selección de comercio e inyección de llaves) y **relanza el mismo cobro una sola vez** (mismo `paymentAttemptId`/referencia → idempotencia intacta; guard de 1 reintento por intento evita loops; el gate de charging no se limpia durante la recuperación). Además se corrigió `AngelPayErrorMapper.isAuthError()`: la heurística `startsWith("C2")` era errónea — verificado contra el `AppErrorCatalog` del SDK 1.0.10 (extraído del AAR): `C2xx`=CLIENT (config), auth=`A0xx`, y la sesión expirada es exactamente `D308` (categoría DEVICE). Cierra la "Open Question #2" del spec. Tests: `AngelPayErrorMapperTest` actualizado + 4 tests nuevos en `AngelPayPaymentViewModelTest` (recuperación, no-loop al segundo D308, códigos no-sesión no re-autentican, re-auth fallida muestra error) — priman el estado vía el seam `@VisibleForTesting launchSdkRequest` porque sandbox compila con `ANGELPAY_SDK_ENABLED=false`. **Solo ruta AngelPay/Nexgo — cero cambios en Blumon/PAX.** Verificado: ambas variantes compilan (sandbox + nexgo), suite completa **566 tests / 0 fallos / 5 skipped**, lint limpio. ⚠️ Pendiente: verificar en Nexgo físico dejando expirar la sesión (idle de horas) y cobrando.

- **[Nexgo] "No fue posible registrar la terminal antes del cobro" ahora también se recupera solo** (incidente Alberto Dominguez / AVQD-N860W173232, 2026-07-03): desde el SDK 1.0.10 la `PaymentActivity` de AngelPay **registra la terminal antes de cobrar** (fire token + serial, con el bearer guardado y timeout interno de 10 s) — paso que NO existía en el 1.0.7 de la v2.4.x. Si la sesión del servidor ya expiró, ese registro falla y el SDK aborta con este mensaje pero con `CallResult` **N400 hardcodeado** (verificado por bytecode, idéntico en 1.0.10 y 1.0.13: `z/j.class`/`b0/j.class`), así que la recuperación D308 de esta misma versión NO lo cubría (dispara solo con `code=="D308"`). Evidencia del incidente: misma terminal cobró bien 15 h antes con el mismo APK, config de producción completa (cuenta PROD ACTIVE, merchant 974, afiliación 9946475). **Fix**: `AngelPayErrorMapper.isPreChargeRegisterFailure()` detecta el mensaje exacto del registro (por mensaje y no por código: un N400 real a media transacción NO debe re-lanzar — riesgo de doble cobro) y entra al mismo `tryRecoverFromSessionExpiry()` (re-auth + 1 relanzamiento, mismo `paymentAttemptId`; aquí el relanzamiento es seguro porque el SDK aborta ANTES de llamar al gateway — no se movió dinero). Tests: 1 nuevo en `AngelPayErrorMapperTest` + 2 en `AngelPayPaymentViewModelTest` (registro-N400 recupera y relanza; N400 de red a media transacción NO re-autentica). **✅ Validado en hardware** (N86 AVQD-N860W175781, 2026-07-03 14:11 CDMX): la recuperación disparó, `handleAuthExpiry` re-autenticó con éxito (lastValidatedAt movió en backend, llaves IPEK re-inyectadas) y relanzó el cobro una sola vez con guard anti-loop — comportamiento exacto al diseño. Nota post-mortem: el incidente del 3 jul resultó ser un outage del endpoint de registro en el servidor de AngelPay (fallaba incluso con sesión fresca; resuelto por AngelPay ese mismo día) — este fix queda como red de seguridad para el caso real de sesión expirada.

---

## [2.6.2] - 2026-06-30

### **Added**

- **Imágenes de producto en el checkout**: el grid de productos (`ProductGridView`), el carrito (`CartDetailsSheet`) y la búsqueda (`SearchOverlayView`) ahora muestran la **foto del producto** (`imageUrl` vía Coil `AsyncImage`, recorte `Crop` + crossfade) cuando existe, con fallback a las iniciales sobre fondo tintado cuando no hay imagen. Los nombres largos se **deslizan solos** (`basicMarquee`) en lugar de cortarse con elipsis. Solo UI de checkout.

### **Fixed**

- **Cobro "se queda pasmada" cuando el emisor declina (causa raíz)**: el SDK de Blumon entrega los rechazos del emisor (ej. **"PAGO NO PERMITIDO EMISOR"**) como un `SaleIccResponse` **no-nulo** con `authorization` **vacío** + `description` con el motivo (`error=null`). El flujo en `performOnlineAuthorization` solo checaba `response == null`, así que tomaba el rechazo como **éxito** y avanzaba a `CompleteEmvTrans` (finalización del chip) → **se colgaba** sin mostrar nada (caso Arantza, AVQD-2840744149, tarjeta Santander crédito $17,000 — confirmado en portal Blumon + BetterStack: no se cobró, app v2.5.3 en línea). Otras terminales sí mostraban el motivo porque ESOS rechazos vuelven como fallo (Left), que la app ya pintaba. **Fix**: guardia en la rama de éxito de `performOnlineAuthorization` (sandbox+production, idéntico) — si `saleData.authorization` viene vacío, se trata como rechazo y se enruta por la **ruta de error ya existente y probada** (`response == null`), mostrando `saleData.description`. **No toca `CompleteEmvTrans` ni los refunds.** Seguridad del discriminador verificada contra producción: **2850/2850 aprobaciones reales de Blumon (CHIP+CONTACTLESS) tienen `authorization` no-vacío** → cero falsas declinaciones. Pendiente: repro en PAX física (aprobación sigue pasando + declinación ahora muestra el motivo).

- **PIN del cliente: el spinner confundía ("¿cuándo tecleo?")**: durante la captura de PIN, `PaymentLoadingContent` mostraba a la vez "Ingrese su PIN" + ● ● ● ● **y** un `CircularProgressIndicator` girando, que los clientes leían como "está cargando" sin saber que era su turno de teclear (reporte de campo, Arantza 2026-06-29). Ahora el spinner se oculta mientras el SDK pide PIN (`showPinSection`); la sección "Ingrese su PIN" de arriba queda como única señal. Solo UI — **no toca el flujo de cobro**. Nuevo `@Preview` del estado con PIN (`PaymentLoadingContentPinPreview`).

### **Changed**

- **Telemetría de "pago pasmado" ahora visible para la autorización online**: `reportProcessingTimeoutIfNeeded` (PaymentViewModel sandbox+production) solo reportaba a Crashlytics si el mensaje contenía "chip", así que un cuelgue ≥45s en **"Autorizando con banco…"** (paso `SaleIcc` online) quedaba **invisible** — exactamente el caso "se queda pasmada" de Arantza (AVQD-2840744149). Se quitó el filtro `"chip"`: ahora cualquier stall ≥45s emite el non-fatal `recordPaymentEmvStall` (flowOrigin/mensaje/segundos), para diagnosticar el cuelgue remoto sin `adb logcat`. **Solo observabilidad — no cambia el control de flujo del pago.** 3 tests nuevos en `PaymentViewModelTest` (dispara con mensaje no-"chip" ≥45s, no dispara <45s, deduplica). NO se agregó timeout a `SaleIcc` (riesgo de doble cobro, vetado por Edgardo).

---

## [2.6.1] - 2026-06-29

### **Changed**

- **Vender SIM: precio fijo (no editable) para SKUs de promotor**: en la pantalla "Vender SIM" (`SerializedSaleScreen`) el campo **"Precio de venta"** ya no es editable cuando el SIM pertenece a una **categoría a nivel organización con precio sugerido** — p. ej. **"$100 de promotor"** y **"E-SIM de promotor"** ($100). Antes el promotor podía abrir el numpad y cambiar el monto; ahora el campo se muestra de solo lectura, con ícono de candado y leyenda "Precio fijo de promotor — no editable", fijado al precio central de la categoría. Las categorías creadas por la tienda (venue) siguen editables, igual que las categorías org **sin** precio sugerido (no hay precio fijo al cual anclar). Regla en `SerializedSaleUiState.isPriceLocked` (org-level = `venueId == null` + `suggestedPrice != null`); cambio **solo en la app**, sin backend ni Dashboard. 5 tests nuevos en `SerializedSaleUiStateTest`. (Asana 1216097720443488, opción 1).

---

## [2.6.0] - 2026-06-29

### **Added**

- **Mis Comisiones (Cash Out promotor, self-service)**: nueva pantalla para que el promotor vea su **saldo de comisión** disponible y solicite un **retiro ("Retirar")** desde la TPV. Aparece en el menú (WelcomeScreen) donde haya **inventario serializado activo** + permiso `cash-out:view_own`. El retiro solo se permite en un **día activo** configurado (lo valida el backend; el botón se deshabilita si no, y se muestra el mensaje del backend, ej. "Hoy no es un día habilitado para retirar"). Self-scoped: el backend lee `staffId`/`venueId` de la sesión — la TPV nunca envía identidad. Dinero en pesos (BigDecimal). Endpoints `GET tpv/cash-out/my-saldo` + `POST tpv/cash-out/withdraw`. Archivos nuevos: `features/cash_out/` (`CashOutDto`, `MyCommissionsViewModel`, `MyCommissionsScreen`); wiring en `ApiService`, `NavRoute.MyCommissions`, `AppNavigation`, `WelcomeScreen`.

### **Fixed**

- **Splash con logo viejo (rebranding V2)**: `drawable/isotipo.png` seguía con el mark anterior (733×893). Lo usan el **splash nativo** (`windowSplashScreenAnimatedIcon` en `themes.xml`) y el Compose **`SplashScreen`** (`Image(painterResource(R.drawable.isotipo))`), así que ambos mostraban el logo viejo al abrir la app. Reemplazado por el isotipo V2. Solo asset; sin cambios de lógica ni de tema.

---

## [2.5.8] - 2026-06-25

### **Changed**

- **Logo V2 (rebranding)**: nuevos íconos de launcher (mipmap mdpi→xxxhdpi, fondo negro + mark Q rediseñado, foreground en safe-zone) y logos in-app — `logo_avoqado.png` (lockup) y `logo_avoqado_black.png` (silueta negra del recibo térmico, usada por `PrinterManager`). Nuevo verde de marca #7ADD2C. Solo assets; sin cambios de tema. Ícono sandbox de dev sin cambios.
- **Copy de estados de verificación ("Mis Ventas" y revisión de ventas)**: textos unificados en `MySalesScreen`, `WelcomeScreen`, `PendingVerificationsScreen` y `SaleCorrectionScreen`:
  - `PENDING`: "En revisión por **Administración**" → "En revisión por **administración**" (minúscula).
  - `COMPLETED`: "Venta correcta" → "**Aprobada**".
  - `FAILED`: "Revisar documentación" → "**Revisar por promotor**".
  - Banner de reenvío: "En revisión por **back-office**" → "En revisión por **administración**".
  - Solo copy/etiquetas; sin cambios de lógica.

### **Fixed**

- **"Mis Ventas" preserva el cluster "Por revisar" al recargar el mes**: `loadSales()` reconstruía `MySalesUiState` con el constructor, que omitía `salesToReview` (default `emptyList`) y borraba el cluster pineado "Por revisar" (feed cross-month que solo mantiene `loadSalesToReview()`) y su leyenda en cada carga/refresh. Con 2+ ventas por revisar la leyenda desaparecía, la venta `FAILED` quedaba en la lista del día y el conteo mostraba (1) habiendo 2. Fix: `loadSales()` usa `_uiState.value.copy(...)` (también resuelve el race init/socket); `navigateMonth()` re-sincroniza con `loadSalesToReview()`. 3 tests de regresión en `MySalesViewModelReviewTest`. (Isaac, Asana 1215587362953156)

---

## [2.5.7] - 2026-06-16

### **Added**

- **"Mis Ventas" — estado "Rechazada" para ventas perdidas**: nuevo estado de verificación `REJECTED` (terminal: no se logró la vinculación/portabilidad o el cliente desistió), distinto de `FAILED` ("Revisar documentación", que el promotor SÍ puede corregir re-subiendo fotos). Se muestra como chip rojo sólido **"Rechazada"** con el motivo del back-office.
    - **No accionable**: a diferencia de `FAILED`, una venta `REJECTED` **no es tocable** (no lleva al flujo de corrección — está perdida) y **no aparece en la sección "Por revisar"** (esa la alimenta el backend vía `getSalesToReview()`, que excluye REJECTED).
    - **Retrocompatible**: estados desconocidos siguen cayendo a `NONE` sin crashear; el estado solo se muestra cuando el backend envía `"REJECTED"`. Archivos: `MySalesViewModel.kt` (enum + mapeo), `MySalesScreen.kt` (badge + motivo). `MySalesViewModelReviewTest` 8/8 verde.

---

## [2.5.6] - 2026-06-15

### **Fixed**

- **Room: migración correctiva 23→24 — crash-loop `Migration didn't properly handle: products` al actualizar terminales viejas**: `MIGRATION_12_13` (dic-2025) agregó la columna `color` a `products`, pero `ProductEntity` declara `category_color` (commits separados por 13 segundos que nunca coincidieron). Cualquier terminal cuya tabla `products` venga de la cadena de migraciones (instalada con DB ≤12) falla la validación post-migración de Room al actualizar y entra en **crash-loop al arrancar** (recuperable solo con factory reset — mismo modo de fallo que `MIGRATION_16_17` corrigió para `pending_payments` en ene-2026, y explicación probable de por qué AVQD-2841653485 necesitó factory reset además del self-update). Adicionalmente `MIGRATION_5_6` creó un índice compuesto extra en `historical_periods` que la entidad no declara (3 índices donde Room espera exactamente 2 — misma falla de validación para dispositivos con DB ≤5).
    - **`MIGRATION_23_24` (impacto mínimo)**: `products` se reconstruye **solo en las terminales rotas** — detectadas por la columna `category_color` ausente. Las terminales sanas (la gran mayoría: instalaciones frescas cuyo `products` ya coincide con la entidad) quedan **intactas**, así que su caché de menú **sobrevive la actualización sin re-sync en blanco**. Las rotas reciben un `DROP`+`CREATE` con el DDL exacto que Room valida (`app/schemas/.../24.json`) — para ellas el caché (24h TTL) se repobla en la siguiente carga del menú, costo despreciable frente a un crash-loop. `historical_periods`: solo se normaliza el set de índices (datos preservados; no-op en terminales sanas). Idempotente. **No toca ninguna tabla de pagos** — `pending_payments` y `draft_orders` intactas.
    - **Verificación en dispositivo real** (`AvoqadoDatabaseMigrationTest`, instrumentado con Room `MigrationTestHelper`, corrido en emulador Android 12 **y en PAX A910S físico**): **5 tests, 0 fallos**. Cubre: (1) `driftedLegacyShape` **reproduce la forma exacta de la terminal vieja rota** y confirma que la migración la sana y pasa **la validación interna de Room contra `24.json`** — el mismo check que crashea en producción; (2) `healthyShape_preservesProductCache` confirma que una terminal sana **conserva su caché de productos** tras migrar; (3) `realDatabaseModuleBuilder_opensDriftedV23_migratesWithoutWipe` abre la DB rota con el **builder real de `DatabaseModule`** y verifica que el pago en cola (`pending_payments`) **sobrevive** (prueba de que quitar el fallback destructivo no borra dinero); (4) happy-path v23→v24; (5) idempotencia. **En el PAX A910S físico** (mismo modelo/OS que la terminal que crasheaba): actualización real `install -r` con Blumon corriendo → migra a v24 sin crash; y restaurando una DB sana real, el caché de 30 productos se preserva. Suite completa de unit tests verde (544/0).

### **Changed**

- **Room: eliminado el `fallbackToDestructiveMigration()` global (protección de la cola de pagos offline)**: el fallback destructivo corría también en `productionRelease` — cualquier migración olvidada en el futuro borraba **toda** la base de datos en silencio, incluida `pending_payments` (cobros ya aprobados por Blumon TPV pendientes de registrar en backend = dinero) y `draft_orders`, sin crash ni telemetría. Ahora una migración faltante falla **ruidosamente** al arrancar (`IllegalStateException`) y se detecta en QA antes de firmar el APK.
    - Se conserva únicamente: (a) la ruta destructiva desde **DB v1** (`fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)`) — esquema dic-2024 sin `MIGRATION_1_2`; esos dispositivos siempre fueron wipeados al actualizar, comportamiento idéntico; y (b) el `fallbackToDestructiveMigrationOnDowngrade()` intencional para rollback vía `INSTALL_VERSION`.
    - **Nota para devs**: si cambias una `@Entity` sin escribir migración, el build debug ahora crashea al arrancar en vez de borrar la DB silenciosamente — es intencional (regla #5). Recuperación en dispositivo de dev: `adb uninstall` o limpiar datos de la app.
- **Room: `exportSchema = true`** — los JSONs de esquema se generan en `app/schemas/` (commiteados a git, cero costo en runtime). Son el DDL canónico para escribir futuras migraciones y habilitan `MigrationTestHelper`. Este export es justo lo que habría detectado el bug de `category_color` en diciembre.
- **CHANGELOG rotado** (política 50KB): entradas ≤2.5.4 movidas a `CHANGELOG-archive-2.md`.

### **Added**

- **Infraestructura de tests de migración Room** (`AvoqadoDatabaseMigrationTest` + dependencia `androidx.room:room-testing`, esquemas `app/schemas/` expuestos como assets de androidTest): permite validar cualquier migración futura contra el esquema canónico que Room genera, en Android real. Correr con `./gradlew connectedTutorialEmuDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabaseMigrationTest`. Cierra el hueco que dejó pasar el bug de `category_color` en diciembre.

---

## [2.5.5] - 2026-06-10

### **Added**

- **"Mis Ventas" — sección "Por revisar" pinneada arriba (no se pierden las ventas rechazadas)**: hallazgo de campo — cuando el promotor tiene muchas ventas correctas, se confía y no revisa las que el back-office **no aprobó**. Ahora "Mis Ventas" muestra, fijada al inicio de la lista, una tarjeta roja **"Por revisar (N)"** con las ventas que necesitan su atención.
    - **Qué incluye**: ventas **rechazadas** (`FAILED` → "Revisar documentación", tocable → flujo `SaleCorrectionScreen` existente para re-subir fotos) y **en revisión** (`PENDING`/`PROCESSING`). Orden: rechazadas primero, luego en revisión; dentro de cada grupo las más viejas arriba (las que más urge atender).
    - **Cross-mes**: la sección es **independiente del mes seleccionado** — una venta rechazada de un mes anterior sigue apareciendo arriba aunque el promotor esté viendo el mes actual. La lista por día normal no cambia.
    - **Tiempo real** (`MySalesViewModel`): se recarga al abrir y al recibir el socket `sale-verification.reviewed` (mismo que ya refresca el mes). Fallo no fatal: si el endpoint falla, la sección queda vacía y no bloquea la lista del mes.
    - **TPV** (`MySalesScreen`, `MySalesViewModel`, `SerializedInventoryDto`, `ApiService`): nueva sección `ToReviewSection`, estado `salesToReview`, mapeo `MySaleItem.toSaleItem()` reutilizado por la lista mensual y el feed. Nuevo endpoint cliente `getSalesToReview()`.
    - **Backend (avoqado-server, deploy primero)**: nuevo `GET /tpv/serialized-inventory/sales-to-review` — mismo shape `SaleItem` que `my-sales`, sin filtro de mes, filtrando `SaleVerification.status ∈ {PENDING, PROCESSING, FAILED}` del promotor; **siempre** scope por `venueId` (aislamiento de tenant). `serialized-inventory:sell`. Sin migración. Retrocompatible: clientes viejos ignoran el endpoint.
    - Sin gating nuevo (va sobre el acceso `serialized-inventory` existente). Compila `sandboxDebug`; backend `tsc` sin errores; `MySalesViewModelReviewTest` pasa.

---
