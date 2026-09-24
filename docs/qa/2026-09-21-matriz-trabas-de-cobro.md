# Matriz de trabas de cobro — 21-sep-2026

**Pregunta que contesta cada celda:** tras el evento, ¿puede seguir cobrando **el aparato**? ¿y **el negocio**?
¿y existe una **salida dentro del producto** (sin SQL, sin reinstalar)?

**Montaje:** POS Sunmi D3 `D40625BRJ0469` (`com.avoqado.pos.dev` 2.18.4-dev, arreglo del 21-sep) ·
TPV Nexgo N86 `AVQD-N860W175781` (`com.jaac.avoqado_tpv.sandbox` 2.10.0-nexgo, arreglo del 21-sep) ·
servidor local rama `codex/launch-campanas-ligeras-20260917` · venue «Avoqado Full» · AngelPay QA (sin dinero real).

🔴 **Regla del reporte:** se declara cada celda como **EJECUTADA**, **NO EJECUTADA** o **NO APLICA**, con su motivo.
Nunca se presenta como comprobada una celda que no se ejercitó.

## Resultados

| # | Escenario | Aparato sigue cobrando | Negocio sigue cobrando | ¿Hay salida en el producto? | Estado |
|---|---|---|---|---|---|
| A1 | **TPV sola** · Pago rápido, nadie presenta tarjeta → se agota el tiempo (U101/TIMEOUT) | ✅ sí | ✅ sí | no hace falta: se cierra solo | 🟢 **EJECUTADA** — fila `f3770759…` `DESCARTADA` con `sin_autorizacion:sdk=1.0.19;code=U101;status=TIMEOUT`; 0 filas apartando la terminal |
| A2 | **TPV sola** · Pago rápido, el cajero cancela EN la terminal (U100) | ✅ sí | ✅ sí | no hace falta: vuelve solo al método de pago | 🟢 **EJECUTADA** — fila `c5ac94e5…` `DESCARTADA` con `…code=U100;status=CANCELLED`; 0 filas apartando |
| B2 | **POS → TPV** · el cajero cancela desde la tablet con la **terminal muerta** (force-stop a media venta) | ✅ sí (efectivo) · tarjeta bloqueada a propósito | ✅ sí | sí: «Volver a consultar» la resolvió al volver la terminal (`CANCELLED`/`ACCEPTED`, sin `Payment`) | 🟢 **EJECUTADA** — y destapó un defecto MÍO (ver abajo) |
| C2 | **POS** · con un cobro de tarjeta sin confirmar, ¿se puede cobrar en EFECTIVO? | ✅ **sí** | ✅ sí | — | 🟢 **EJECUTADA** — captura `PRUEBA-efectivo-disponible.png` |
| C3 | **POS** · con un cobro sin confirmar, ¿la TARJETA sigue bloqueada? | bloqueada (correcto) | ✅ sí | sí: 3 salidas en pantalla («Volver a consultar» · «Ya revisé la terminal: no se cobró» · «Salir (queda pendiente)») | 🟢 **EJECUTADA** — captura `PRUEBA-tarjeta-bloqueada.png` |

## 🔴 Defecto encontrado por esta matriz (introducido hoy, ya corregido)

**B2 destapó que la guarda sobrevivía a su propia causa.** Tras resolver el pendiente con «Volver a consultar»
—la llave quedaba libre de verdad: el servidor decía `CANCELLED` con `cancelDisposition=ACCEPTED` y sin `Payment`—
al elegir TARJETA **volvía a bloquearse**, y sólo se destrababa saliendo de la venta y empezando otra.

Causa: el ViewModel guardaba una **copia** de la llave al arrancar la venta y la prefería sobre la viva.
Arreglo: leer siempre la llave VIVA. Prueba `P1 resolver el pendiente a media venta desbloquea la TARJETA sin salir`,
verificada con sabotaje (reponer la copia tumba exactamente esa prueba). Portado a iOS **sin compilar** — iOS va al
final por decisión del founder.

## 🔑 Lo que cambia el diagnóstico del incidente de la mañana

El $25 que dejó trabada la Nexgo a las 10:30 traía el motivo **«AngelPay sin veredicto: status=CANCELLED code=U100»**
— redacción de **2.9.2**. Con **2.10.0** ese mismo desenlace se acredita y cierra solo (celdas A1 y A2). **El hueco era
de VERSIÓN, no de diseño.** Lo que sigue abierto es más angosto: un desenlace local **sin NUESTRA referencia** no se
acredita y no tiene declaración. Cuánto ocurre eso está **sin medir**.

## La matriz COMPLETA — todo escenario que puede dejar sin cobrar

🔴 **Instrucción del founder (21-sep):** *«hagas pruebas de todo, absolutamente todo el escenario que puede
ocurrir… ver dónde hay un escenario donde se puede romper y se puede quedar trabado sin poder cobrar tanto el
venue como algún dispositivo»*.

Cada celda contesta lo MISMO: ¿sigue cobrando **el aparato**? ¿sigue cobrando **el negocio**? ¿hay **salida
dentro del producto** (sin SQL, sin reinstalar, sin reiniciar)? Estado: 🟢 ejecutada · ⬜ pendiente · ⛔ no aplica.

### Bloque A — el cobro nace y muere en la TPV (sin POS)

| # | Escenario | Estado |
|---|---|---|
| A1 | Pago rápido · nadie presenta tarjeta → U101/TIMEOUT | 🟢 **cierra solo** (`DESCARTADA`), 0 filas apartando |
| A2 | Pago rápido · cancelar EN la terminal → U100 | 🟢 **cierra solo**, vuelve al método de pago |
| A3 | Pago rápido · matar la app con el lector ACTIVO | 🟢 **EJECUTADA al segundo intento (21-sep 18:30:55, corte a los 8 s del lector; el primero no valió porque el lector se vence solo a los ~37 s).** Fila `f1bbe404…` queda **`AUTORIZANDO`** sin `last_error` y **SÍ aparta la terminal** — correcto por diseño: el SDK pudo haber cobrado, no se libera a ciegas. ✅ Al relanzar, el aviso verde la NOMBRA («Quedó un cobro de **$40.00** sin confirmar hace unos segundos») con «Revisar» (el botón nuevo de hoy) y «Ayuda». ✅ Un cobro nuevo se rechaza con el TEXTO NUEVO: «La terminal está apartada por otro cobro sin confirmar (**$40.00, hace 2 min**). NO se inició este cobro. Resuélvelo o cobra con otra terminal.» 🔴 **PERO: «Resuélvelo» no tiene cómo** — la declaración «Ya revisé la terminal: no se cobró» exige solicitud del POS, y ésta es LOCAL. 🔴 **Y MEDIDO: a los 3 minutos NADIE la ha tocado** — sigue `AUTORIZANDO`, `verify_attempts = 0`, sin evidencia del servidor y con `updated_at` congelado en el instante del corte (18:30:46 → comprobado 18:33:42). O sea: ni siquiera ha arrancado la consulta al servidor. La terminal lleva 3 min apartada **sin nada intentando resolverlo y sin salida para el cajero**. ⚠️ Y el encabezado sigue diciendo «Error en el pago» cuando la barrera hizo bien su trabajo (mismo defecto de texto que D-4). |
| A4 | Pago rápido · tarjeta APROBADA y sin red al registrar | ⬜ el dinero ya salió: ¿se encola y se reproduce? |
| A5 | Pago rápido · tarjeta aprobada y el proceso muere ANTES del POST | ⬜ ¿existe la fila write-ahead? |
| A6 | Pago rápido · rechazo del banco (fondos, código 05) | ⬜ ¿libera y ofrece reintentar? |
| A7 | Pago rápido · desenlace del SDK **sin NUESTRA referencia** | ⬜ 🔴 el hueco conocido: `INDETERMINADO` **sin declaración** |
| A8 | Cobro con ORDEN desde «Cobrar» de la TPV · los mismos cortes | ⬜ la cerca es por venta, no por aparato |
| A9 · E-2 | EFECTIVO en la TPV con una obligación de TARJETA pendiente | 🟢 **EJECUTADA (21-sep 19:15) — SÍ cobra.** Con la fila `f1bbe404…` apartando la terminal, un Pago rápido de $40 en EFECTIVO llegó hasta «Nuevo Pago · Pago en efectivo · Total pagado $40.00» con su QR, y el `Payment` está en el servidor (`cmubzfl8p000zc9az6ua6192x`, CASH, COMPLETED, 01:15:22 UTC). 🔑 **La terminal apartada NO está muerta: sólo se le cierra la TARJETA**, que es el único instrumento que podría duplicar el cargo incierto. Es la misma regla que se aplicó hoy en el POS, y aquí ya estaba bien. |
| A10 | Reembolso en la TPV sin red (el SDK ya devolvió) | ⬜ cola durable + barrera del cierre |

### Bloque B — cobro remoto POS → TPV, por ESTADO de la terminal

Los 17 estados de `.claude/rules/cobro-remoto-pos-a-tpv.md` § «La matriz que hay que ejercitar», cruzados con el
evento que más rompe. **Ninguno se da por bueno sin ejercitarlo.**

| # | Estado de la terminal al llegar el cobro | Estado |
|---|---|---|
| B-1 | Inicio (tiles) | ⬜ |
| B-2 | Pago rápido capturando monto | ⬜ ¿se pierde el monto tecleado? |
| B-3 | Pago rápido en calificación / propina / paso 3 | ⬜ medido antes en HEAD: rechaza → UNKNOWN |
| B-4 | Venta LOCAL con el SDK leyendo tarjeta | ⬜ |
| B-5 | Autorización en curso con el host | ⬜ |
| B-6 | Pantalla de recibo tras un cobro | ⬜ |
| B-7 | Historial / detalle de un pago | ⬜ |
| B-8 | Reembolso (motivo · leyendo tarjeta · TX_024) | ⬜ incluye «reembolso remoto mientras se cobra» |
| B-9 | «Cobrar» con carrito abierto | ⬜ ¿se pierde el carrito? |
| B-10 | Turnos habilitados SIN turno abierto | ⬜ medido antes: **descarte SILENCIOSO** |
| B-10b | Turno abierto/cerrado en el servidor, tile sin refrescar | ⬜ |
| B-11 | Kiosco | ⬜ |
| B-12 | Ya hay OTRO cobro remoto en curso | ⬜ 409 `TERMINAL_BUSY` correlacionado |
| B-13 | Pantalla apagada · Doze | ⬜ medido antes: acusa y **no despierta** |
| B-14 | Arranque en frío (colas, merchants, SDK sin init) | ⬜ |
| B-15 | Sin red al servidor con socket vivo · socket muerto con WiFi bien | ⬜ |
| B-16 | Dos `MainActivity` apiladas | ⬜ |
| B-17 | Local sin internet y la terminal con SIM (y su espejo) | ⬜ el escenario del founder |

### Bloque C — los eventos que pueden llegar encima

| # | Evento | Estado |
|---|---|---|
| C-a | `payment_cancel` desde la tablet, terminal VIVA | ⬜ ¿contesta la disposición y libera? |
| C-b | `payment_cancel` desde la tablet, terminal MUERTA | 🟢 queda pendiente y honesto; «Volver a consultar» lo resolvió |
| C-c | Resultado TARDÍO después de un cancel aceptado | ⬜ 🚨 no puede pagar otra venta |
| C-d | Duplicado del mismo `requestId` | ⬜ ACK sin re-ejecutar |
| C-e | Sonda `payment_probe` al reconectar | ⬜ |
| C-f | Expiración de sesión a media venta | ⬜ |
| C-g | Comando remoto (LOCK · RESTART · actualización) a media venta | ⬜ sólo en entorno descartable |

### Bloque D — el POS (tablet)

| # | Escenario | Estado |
|---|---|---|
| D-1 | Con un cobro de tarjeta sin confirmar, ¿cobra en EFECTIVO? | 🟢 **sí** |
| D-2 | …¿la TARJETA sigue bloqueada, con salidas visibles? | 🟢 **sí**, 3 salidas |
| D-3 | …resolver el pendiente a media venta, ¿desbloquea sin salir? | 🟢 **sí** (era un defecto mío; corregido) |
| D-4 | POS SIN red al mandar el cobro | 🟢 **EJECUTADA (21-sep, WiFi apagado por `svc wifi disable`)** — ✅ el negocio NO se traba: banner naranja **«Sin conexión — las ventas se guardan en el dispositivo»** y el EFECTIVO disponible; ✅ **no deja llave pendiente**: al volver la red, «Cobrar con terminal» abre el selector normal. 🔴 **Pero el texto MIENTE:** tocar «Cobrar con terminal» sin red da «**Error en el pago** · Error de conexión · Reintentar», cuando no se intentó cobrar nada — y contradice su propio banner, que en esa misma pantalla dice que no hay conexión. Rompe la regla «offline es estado normal, nunca rojo de error». Y de paso: el POS sigue ofreciendo «Cobrar con terminal» sin avisar que no hay red (`probeTerminalAvailability` falla en abierto, a propósito). |
| D-5 | POS mata su proceso entre el toque y el POST | ⬜ llave durable ANTES del POST |
| D-6 | «Ya pagó de otra forma» con una obligación pendiente | ⬜ es otro instrumento: debería dejar |
| D-7 | Selector de terminales: una ocupada, otra libre | ⬜ ¿deja usar la libre? |

### Bloque E — el NEGOCIO (la pregunta que más importa)

| # | Escenario | Estado |
|---|---|---|
| E-1 | Un aparato trabado: ¿puede cobrar OTRO del mismo venue? | ⬜ 🔴 la pregunta del founder |
| E-2 | Una VENTA cercada: ¿se pueden cobrar las demás cuentas? | ⬜ es la promesa de F0 |
| E-3 | Cerrar turno / caja con una obligación pendiente | ⬜ la cola es barrera a propósito |
| E-4 | Dos terminales del mismo venue, una con obligación vieja | ⬜ |


## 🔑 CAUSA RAÍZ de A3 — encontrada, y es de UNA LÍNEA

`verify_attempts = 0` no era un job mal agendado: es que **un cobro LOCAL está EXCLUIDO por SQL** de la
recuperación por servidor.

`PaymentAttemptDao.candidatasDeConsultaAlServidor` (`app/src/main/java/.../ledger/PaymentAttemptDao.kt:604`):

```sql
AND terminal_payment_request_id IS NOT NULL      -- ← un cobro LOCAL nunca entra
```

Un Pago rápido no tiene solicitud del POS ⇒ `terminal_payment_request_id` es NULL ⇒ **nunca es candidato**.
Por eso nada lo consultó en 3 minutos: no es que tardara, es que **no está en la lista**.

### La cadena completa de por qué queda trabado

1. El proceso muere entre `AUTORIZANDO` y el desenlace ⇒ **el hook `onUncertaintyBorn` NUNCA dispara**
   (`LedgerRecoveryTrigger`), porque la incertidumbre nunca llegó a «nacer»: no hubo transición a
   `INDETERMINADO`. Sin hook: ni recuperación inmediata ni el worker de respaldo a 2 min.
2. La consulta al servidor la excluye por el filtro de arriba.
3. Lo único que puede tocarla es `quarantineStaleAuthorizing` (`LedgerShadowSweepWorker`), que corre
   **cada 6 h** y una vez ~2 min después de arrancar/entrar. La pasa a `INDETERMINADO` con
   `cuarentena_por_antiguedad`.
4. 🔴 Y ese estado **SIGUE apartando la terminal**: `findTerminalHold` incluye explícitamente
   `state='INDETERMINADO' AND last_error='cuarentena_por_antiguedad'`.
5. La declaración «Ya revisé la terminal: no se cobró» exige `_socketRequestId` ⇒ tampoco aplica.

⇒ **Esa terminal queda apartada hasta que alguien la limpie a mano.**

### Lo que hace barato el arreglo (para la decisión del founder)

El endpoint del servidor **ya está llaveado por ATTEMPT, no por solicitud**:
`GET /tpv/venues/:venueId/terminal-payment/attempts/:attemptId` (`avoqado-server/src/routes/tpv.routes.ts:3491`),
y su respuesta trae `attempt: { outcome, paymentId, paymentStatus, … }` **aparte** de `request`. O sea: **puede
contestar por un cobro local**. Relajar ese `IS NOT NULL` haría que un cobro local que SÍ se cobró se cierre solo.

⚠️ **No cierra el hueco entero:** si el servidor contesta 404 / `NO_EVIDENCE`, eso **no acredita ausencia de
cobro** (regla dura), así que el caso «de verdad no se cobró» sigue necesitando la declaración para locales.
Son dos piezas, no una.


## 🟢 ARREGLADA la mitad barata (21-sep, decisión del founder)

`PaymentAttemptDao.candidatasDeConsultaAlServidor`: se retiró `AND terminal_payment_request_id IS NOT NULL`.

**Por qué es seguro, y no es una opinión:** ampliar la lista no vuelve liberable a nadie. El veredicto
(`VeredictoDeIntento.desdeConsultaS6`) sólo acredita con un `paymentId`; la LIBERACIÓN
(`LiberacionDelServidor.desdeConsultaS6`) exige solicitud **a propósito** («sin solicitud no hay pertenencia que
comprobar»); y la comprobación de pertenencia de `aplicarVeredictoDelServidor` tolera `requestId` nulo. ⇒ un cobro
local puede cerrarse **como cobrado**, nunca liberarse en falso.

**TDD:** 3 pruebas en `LedgerServerRecoveryRoomTest` — las 2 positivas vistas en **ROJO** primero; la 3ª (de
seguridad, «sin evidencia NUNCA se libera») pasaba ya, que es justo lo que se quería demostrar. **Sabotaje**
(reponer el filtro): caen **exactamente** las 2 positivas y la de seguridad **no**, o sea que no dependía de él.
73 pruebas de las 4 suites del área, 0 fallos.

**🟢 VERIFICADO EN LA N86, no sólo con pruebas:** la misma fila local `f1bbe404…` que llevaba media hora con
`server_check_count = 0` pasó a **`server_check_count = 1`, consultada a las 18:50:43** en cuanto se instaló el APK.
Sigue `AUTORIZANDO` — **y así debe ser**: ese cobro nunca ocurrió, el servidor no tiene `Payment`, nada se acredita
y nada se libera en falso.

**Lo que esto cierra y lo que NO:**

| | |
|---|---|
| ✅ Cierra | un cobro LOCAL que **sí se cobró** y quedó sin registrar: ahora se consulta y se cierra solo |
| 🔴 NO cierra | un cobro LOCAL que **de verdad no se cobró**: sigue apartando la terminal y sin declaración («Ya revisé» exige solicitud del POS). Es servidor + TPV, decisión pendiente |


## 🔴 HALLAZGO 2 — la tablet TIRA el motivo que la terminal le manda

**Celda:** POS → TPV con la terminal apartada por un cobro LOCAL anterior. **EJECUTADA (21-sep 19:03).**

| Aparato | Lo que dice |
|---|---|
| Terminal ✅ | «Este cobro del POS NO se inició: la terminal tiene un cobro anterior sin confirmar (**$40.00, hace 36 min**). Resuélvelo o cobra con otra terminal.» |
| Tablet 🔴 | «Error en el pago — **El cobro fue rechazado. No se cobró la tarjeta.**» + **Reintentar** |

**No es que la terminal calle: el servidor tiene guardado el motivo correcto.** Fila
`4acb871b-6f79-4af3-8bd4-b26c4a80db5f`, `status=FAILED`, `failureCode=TPV_CONFIRMED_NO_CHARGE`:

```json
"errorMessage": "La terminal tiene un cobro anterior sin confirmar: este cobro NO se inició.
                 Resuélvelo en la terminal o cobra con otra.",
"outcomeEvidence": "PRE_AUTHORIZATION"
```

**Por qué se pierde:** el payload del estado (`TerminalPaymentRequestStatus`,
`avoqado-server/src/services/terminal-payment.service.ts:130-147`) **no expone `errorMessage`** — vive sólo dentro
de `resultJson`. Y el POS decide el texto por `status` a secas:
`CardChargeOutcome.kt:311` → `probe.status == "FAILED" -> NotCharged("El cobro fue rechazado. No se cobró la tarjeta.")`.

**Por qué importa:** «el cobro fue rechazado» es lo que un cajero lee como **«el banco rechazó la tarjeta»**, y aquí
no hubo banco. No le dice qué pasa, no nombra el cobro que estorba, y le ofrece **«Reintentar»**, que va a fallar
igual hasta que alguien resuelva el otro cobro. El texto correcto EXISTE — simplemente no se entrega.

✅ **Lo bueno:** el negocio NO se traba. Hay «Cancelar», el efectivo sigue disponible y el mensaje de la terminal sí
nombra la salida («o cobra con otra terminal»).

**Arreglo (aditivo, server + android; iOS al final por decisión del founder):** exponer el mensaje del desenlace en
el payload de estado (campo nuevo opcional, sin quitar nada) y que el POS lo prefiera sobre su texto genérico cuando
venga con `outcomeEvidence = PRE_AUTHORIZATION`. **Sin construir** — se agrupa con el resto al cerrar la matriz.
