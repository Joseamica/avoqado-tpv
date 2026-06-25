# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-2.md` (newest) and `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Changed**

- **Logo V2 (rebranding)**: nuevos íconos de launcher (mipmap mdpi→xxxhdpi, fondo negro + mark Q rediseñado, foreground en safe-zone) y logos in-app — `logo_avoqado.png` (lockup) y `logo_avoqado_black.png` (silueta negra del recibo térmico, usada por `PrinterManager`). Nuevo verde de marca #7ADD2C. Solo assets; sin cambios de tema. Ícono sandbox de dev sin cambios.

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
