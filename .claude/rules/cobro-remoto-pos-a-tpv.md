# Cobro remoto POS → TPV: la terminal NO está en una pantalla fija

Instrucción del founder (2026-09-10), tras ver en hardware el circuito tablet → terminal:

> «puede que la TPV esté en la pantalla home, en propinas, en calificaciones, en "cobrar", reembolsando…
> hay mil situaciones donde la TPV puede estar en una pantalla o estado específico y no sabemos si en
> alguna, si lo mandamos de POS → TPV, vaya a romper algo, deje algo huérfano o haya falsos positivos.
> La terminal no siempre está en una pantalla fija y no se sabe cuándo el POS mandará la orden de pago.»

Aplica a **quien toque cualquiera de los tres lados** del camino: el POS que manda
(`avoqado-android` / `avoqado-ios`, `TerminalPaymentService` + `CardChargeDecision`), el servidor que
arbitra (`avoqado-server`, `terminal-payment.service.ts` + sockets) y la terminal que recibe (este repo,
`core/remotepayment/*`, `SocketManager`, `PaymentViewModel`). Los tres repos tienen un apuntador a esta
regla en su `.claude/rules/`. Auditada por Codex (gpt-6-astra) el 10-sep en dos pasadas (RECHAZADA
con 7 P1; después S1–S6) una verificación interina y una pasada FINAL independiente (Fable 5.1, por decisión del founder del 10-sep,
en lugar de Codex): **APROBADA CON CAMBIOS, sin P1 de dinero**; esta versión incorpora todas sus ediciones.

## Condición obligatoria de dinero y prioridades de producto

🔴 **Los dos límites, dichos por el founder el 12-sep-2026 tras quedarse sin poder cobrar en una
Nexgo:** «no podemos interrumpir el proceso de cobro en un negocio, eso sería catastrófico, pero
tampoco dejando de lado que cobren doble, o no se registre la venta, o que registre la venta sin
que en realidad haya pasado el cobro». Se juzgan JUNTOS. La salida no es quitar el bloqueo ante un
desenlace incierto —eso produjo el cobro doble del 10-ago— sino que el bloqueo **dure segundos y lo
resuelva el cajero**: la duda se resuelve PREGUNTANDO por el desenlace acreditado, no esperando, y
lo que bloquea se ve, se explica y se puede volver a consultar desde la pantalla. Hoy la única
salida real ha sido escribir SQL en la base del aparato, dos veces. Detalle y reglas de aplicación:
memoria `invariante-del-cobro-founder`.


🔴 **La rapidez nunca autoriza otra ejecución capaz de cobrar mientras la anterior pueda haber movido
dinero.** Timeout, cancel solicitado, desconexión y ausencia de `Payment` no prueban ausencia de cargo.
Los reintentos de transporte y de registro conservan sus respectivas llaves; repetir un mensaje no
autoriza repetir la llamada al SDK. (Incidente del 2026-08-10: un 409 leído como «no se cobró» produjo
un cobro doble.)

Dentro de esa condición, las prioridades del founder (10-sep), en este orden:

1. **Que el cobro pase sin demoras evitables**, también con internet lento o intermitente, cuando el
   transporte y el procesador lo permitan. Medir por separado entrega, espera del cliente, SDK/procesador
   y registro. Investigar las esperas de 30–120 s atribuibles al sistema; **no convertir ese intervalo en
   un timeout financiero ni acortar las ventanas de tarjeta para cumplirlo** (el POS espera hasta 330 s a
   propósito, `CardChargeOutcome.kt`; la regla offline de Android excluye esta espera de los timeouts
   cortos).
2. **Registro correcto y recuperable en Avoqado**: `Payment`, orden, turno, recibo, webhooks, atribución
   a la terminal que cobró. Puede llegar TARDE por cola; nunca se pierde ni se duplica (llave idempotente
   = `attempt_id` de la libreta).
3. **Ninguna obligación sin responsable.** Cada solicitud, intento, cola y llave del POS conserva su
   correlación y un responsable de ejecución o conciliación hasta el desenlace acreditado. No se exige
   cerrar todas las capas simultáneamente. Un estado activo o incierto puede ser correcto; **es defecto
   que describa falsamente una ejecución** (una libreta en «autorizando» un reembolso que Blumon rechazó
   en el preflight, o en «preparando» una venta abortada) **o que carezca de seguimiento**.
4. **Pantalla honesta**: ni «cobrado» sin cobro, ni «desconectada» con el socket vivo (el banner rojo es
   el latido HTTP; los cobros entran por el socket — medido el 10-sep), ni «conectada» con la terminal
   dormida (Doze).

## La matriz que hay que ejercitar antes de dar por bueno un cambio

**Estados de la TPV** (filas). No es una lista cerrada: agrega la pantalla o el estado que tocaste.

| # | Estado de la terminal cuando llega el evento |
|---|---|
| 1 | Inicio (tiles) |
| 2 | Pago rápido: capturando monto |
| 3 | Pago rápido: paso calificación · paso propina · paso 3 (cuenta + método) |
| 4 | Venta LOCAL con el SDK leyendo tarjeta («Acerca o inserta») |
| 5 | Autorización en curso (host de Blumon / AngelPay) |
| 6 | Pantalla de recibo tras un cobro («Nuevo Pago») |
| 7 | Historial de pagos · detalle de un pago |
| 8 | Reembolso: motivo · leyendo tarjeta · error de reembolso (TX_024) |
| 9 | Cobrar (mesas / órdenes) con carrito abierto |
| 10 | Sistema de turnos habilitado sin turno abierto; contrastar con sistema de turnos deshabilitado, donde la ausencia de turno no impide por sí sola cobrar (`PaymentViewModel.kt`, guarda de turno) |
| 10b | **Turno abierto (o cerrado) en el servidor desde la tablet, con el Inicio de la terminal todavía sin refrescar** (defecto conocido: el tile «Sin turno de caja» sólo cambia al reiniciar la app; medido en la PAX el 10-sep 22:21–22:30). Pregunta del founder: ¿qué pasa si en ese estado la terminal cobra local, o el POS le manda un remoto? Medido en la PAX (10-sep 23:01–23:14): el tile sólo se refresca al reanudar la app; el **remoto** consulta al servidor (con turno abierto entra aunque el tile diga «Sin turno»; sin turno se descarta); la **venta local** avanza hasta el Paso 3 aunque la caja esté cerrada en el servidor (el cobro nacería sin turno). Ejecutada sin tarjeta |
| 11 | Kiosco |
| 12 | Ya hay OTRO cobro remoto en curso (segundo `payment_request`) |
| 13 | App en background · pantalla apagada · Doze (la app sin red aunque el Wi-Fi esté bien) |
| 14 | Arranque en frío (colas reproduciendo, merchants cargando, SDK sin inicializar) |
| 15 | Sin red al servidor (banner rojo) pero socket vivo · socket muerto con Wi-Fi bien |
| 16 | Dos instancias de `MainActivity` apiladas (pasa con `am start` sobre la app abierta) |
| 17 | **El local se queda sin internet y la terminal SÍ tiene internet por su SIM (LTE)**: la terminal sigue viva para el servidor; el que está a ciegas es el POS. Y su espejo: terminal en Wi-Fi caído sin SIM con el POS en datos móviles |

**Estados adicionales obligatorios cuando el cambio alcance sus handlers:** activación/login y
expiración de sesión; cambio de venue; AngelPay externo esperando resultado; aprobación pendiente de
registro; `Queued`; `ResultadoIncierto`; error con reintento; cambio de merchant (`Switching`); efectivo y
cripto pendientes; división por producto/persona; `RefundConfirmation` y `PaymentTransactions`; venta
serializada; actualización, bloqueo y mantenimiento.

**Eventos que llegan del POS o del servidor** (columnas): `terminal:payment_request` ·
`terminal:payment_cancel` · sonda `terminal:payment_probe` · `terminal:refund_request` · replay al
reconectar (`replayPendingForTerminal`, sujeto a capacidad, conexión y vigencia) · impresión
(`terminal:print_receipt_request`) · periféricos (`card_reader_status`, `printer_status`,
`peripheral_error`) · expiración de sesión · comandos recibidos por socket o por heartbeat. Distinguir
`LOCK`, `MAINTENANCE_MODE`, `RESTART`, `SHUTDOWN`, cambios de merchant, actualización y `FACTORY_RESET`
por sus efectos sobre la ejecución y los datos. **Los efectos destructivos se prueban sólo en entornos
descartables con la evidencia monetaria preservada.**

**Cruces obligatorios:** los estados relevantes contra duplicados del mismo `requestId`, contrato distinto
con id repetido, cancel tardío, resultado tardío, y pérdida de conexión o muerte del proceso entre
persistencia, ACK, claim, entrada al SDK, aprobación, encolado y publicación del resultado. Distinguir
falta de red del POS, de la TPV al servidor y de la TPV al procesador.

**Escenario del founder (10-sep): «se va el internet del local pero la TPV sí tiene internet por su SIM».**
El objetivo es que la terminal pueda cobrar por LTE y registrar en Avoqado, sujeto a la condición
obligatoria de dinero y a la admisión local. La SIM de la TPV no restablece la conexión del POS.
El POS debe explicar su falta de red y evitar registrar dos veces la misma venta.

En Android, `probeTerminalAvailability` falla en abierto: un error de esa consulta (no es la sonda `terminal:payment_probe`) deja habilitado
«Cobrar con terminal»; si falla la carga posterior del selector, se muestra un error.
Existe «Ya pagó de otra forma → Tarjeta (otra terminal)» (`ManualPaymentMethod.CARD_EXTERNAL`),
pero registrar allí una venta ya registrada por la TPV puede duplicar el ingreso.

Dos pendientes declarados:
1. El POST persiste su llave antes de ejecutarse y, ante una excepción de transporte, conserva la
   obligación mientras consulta el estado. Algunos fallos pueden ocurrir antes de enviar el POST,
   pero `ConnectException`, `UnknownHostException`, un error TLS o su equivalente en iOS NO lo
   demuestran por sí solos. «Nunca se envió» exige evidencia correlacionada y recuperable de que
   ninguna copia del POST transmitió cabeceras ni cuerpo y de que ningún intento pendiente puede
   transmitirlos después. La comprobación abarca reintentos, redirecciones y ejecuciones anteriores
   del mismo `requestId`; no basta observar el último intento, ausencia de handshake o cero bytes
   de cuerpo. Sin esa evidencia se conserva la misma llave y se concilia; timeout y GET 404 no
   acreditan ausencia de cargo. Mantener la persistencia antes del POST. Diseñar y probar esta
   distinción con TDD en Android e iOS, incluyendo conexiones reutilizadas, HTTP/2, intermediarios,
   reintentos y muerte del proceso.
2. Si la misma venta se cobra y registra mediante Pago rápido en la TPV y además se registra
   manualmente en la tablet como tarjeta de otra terminal, pueden quedar dos registros de ingreso
   sin dos cargos al cliente. Falta definir la conciliación entre ambos registros, su referencia
   al cobro original y la deduplicación en el servidor; es una decisión de producto pendiente.

Referencia de mercado (buscada en vivo el 10-sep; precedente en
`../.claude/rules/product-decisions-industry-reference.md` — raíz del workspace, entrada del 10-sep «Cobro con
terminal externa cuando el POS (tablet) NO tiene internet»): Square, en su modo tablet + Terminal pareada
por la nube (Terminal API), exige internet en el POS y no soporta modo offline ahí; su plan B es operar
la Terminal sola con el *device code*. Toast no separa POS y lector; Clip sólo los separa en su lector
Bluetooth, que depende del internet del celular, y no documenta el POS sin red con el lector vivo.

**Las respuestas dependen del protocolo y de la evidencia de ESA solicitud:**

| Situación | Respuesta permitida |
|---|---|
| Solicitud nueva admisible | Persistir antes del ACK positivo; reclamar una sola vez antes de abrir el cobro. |
| Espera de inicialización o navegación | Conservar la solicitud durable y respetar cancelación y admisión. La TPV no guarda `expiresAt` ni comprueba la edad al terminar `awaitInitialization()`: intenta reclamar mediante CAS una fila RECEIVED del venue actual. Una cancelación o sonda que la haya resuelto impide ese claim. El servidor fija un vencimiento de 5 min; el vigía puede pasar una solicitud vencida sin desenlace acreditado a UNKNOWN cuando la procese. Ese vencimiento no detiene por sí mismo el SDK. Un resultado tardío sólo resuelve según las validaciones de `closeRow`, incluida evidencia negativa o un Payment acreditado para éxito. No prometer una cola de atención posterior: `AppNavigation` rechaza la solicitud nueva cuando otro cobro está activo. |
| Nueva solicitud que no puede admitirse | `failed + PRE_AUTHORIZATION` **sólo** si se acredita que ESA solicitud no inició ninguna ejecución capaz de autorizar (entrar al kernel puede aprobar sin host: una avería posterior NO acredita ausencia de cargo, `PaymentAttemptLedger.kt`). Rechazar B porque A ocupa la terminal nunca libera A. **Barrera de la libreta (§3.8, 18-sep):** si la reserva de la libreta rechaza una solicitud del POS, la TPV responde `failed + PRE_AUTHORIZATION` AL INSTANTE y NOMBRA lo que aparta la terminal (importe y antigüedad); nunca se queda muda. Lo que lo hace seguro es la bandeja: `resolverDesenlaceNegativo` sólo escribe (y el socket sólo emite) si ningún intento de ESA solicitud quedó fuera de PREPARANDO/DESCARTADA, y la venta que aparta la terminal no se toca. **AngelPay SDK 1.0.19 (18-sep): el proveedor pone la frontera en el ENVÍO, no en el kernel.** Un resultado que el SDK armó él mismo con `authorizationAttempted = false` y NUESTRA referencia —sin campos del host, sin tarjeta leída salvo `E608`, con código de catálogo— acredita que ESE intento no salió al banco: la libreta lo escribe (`markSinAutorizacion`, `AUTORIZANDO → DESCARTADA` sin `host_approved`) y sale `failed + PRE_AUTHORIZATION` al instante (`AngelPayOutcomeClassifier.decidirSegunElSdk119`). **Sin NUESTRA referencia** (el C200 al abrir la pantalla del SDK, los respaldos del contrato) **o con la de otro intento, nada se acredita: INCIERTO** — la única excepción sin referencia es la sesión expirada (D308 / «registrar la terminal»). Si el servidor acredita dinero de ese intento (S5) mientras se escribe la libreta, la pantalla termina en el cobro o en la contradicción, nunca en «no se cobró»; y si ese dinero sólo consta en el aviso (S5 lo publica aunque su propia escritura falle), la pantalla deja durable el MISMO veredicto de S5 antes de pintar (Codex r2). Una relectura que falla tras el CAS **no es «sin dinero»**: falla CERRADO — la fila vuelve a INDETERMINADO (`reabrirSinAutorizacion`, sólo la de ESE cierre) y el cobro sigue incierto, sin `failed`. Sólo con el AAR 1.0.19 auditado (versión y SHA-256 fijados por prueba); con otra versión, la tabla de hoy. |
| Declinación acreditada del procesador | `failed + PROCESSOR_DECLINED`, correlacionado con ese intento. Timeout o error genérico no equivalen a declinación. Un «no se cobró» del SDK 1.0.19 (`authorizationAttempted = false`) **no es una declinación**: viaja como `PRE_AUTHORIZATION` y nunca con `host_approved = 0`. ⬜ Pendiente declarado (diseño 1.0.19 §2.2 4a, decisión del founder del 18-sep): con `authorizationAttempted = true`, un `G500`/`N400` que no trae respuesta del host sigue contando como declinación, como hoy. |
| Negativo del SDK SIN evidencia del procesador (cancelación sin tarjeta, U101, timeout) | **Ventana de confirmación (17-sep):** la terminal manda `timeout` al servidor como aviso de incertidumbre (sin resolver bandeja ni libreta, sin evidencia negativa) y ESPERA su veredicto: S6 inmediata y cada 5 s hasta 45 s (`estampar=false`: el sondeo interactivo no consume el backoff de N3), después «Consultar de nuevo». Nunca «Reintentar». Tres salidas, todas del servidor: **liberada** (`request.outcome=NOT_CHARGED` + `outcomeEvidence ∈ {NO_EVIDENCE_AFTER_WINDOW, OPERATOR_RECONCILED}`, a los 30 s o por la declaración del cajero «El cliente no presentó tarjeta» — un toque; PIN de supervisor sólo ante 403 `SUPERVISOR_AUTHORIZATION_REQUIRED`, censurado en los logs) ⇒ la libreta cierra la fila INDETERMINADO de SU solicitud (`terminal_payment_request_id`) como DESCARTADA `liberada_por_el_servidor:<evidencia>` y la pantalla dice «se puede volver a cobrar»; **cobrada** (S5 `registrado`, S6 RECORDED, evidencia del 2xx) ⇒ Success, el dinero manda; **contradicción** (evidencia con dinero — RECORDED/SECOND_CAPTURE/REFERENCE_COLLISION/PENDING — o `processorEvidence=APPROVED` sin Payment) ⇒ «NO lo vuelvas a cobrar», sin reset automático. La evidencia positiva del servidor es DURABLE (`payment_attempts.server_processor_evidence`, Room 36): veta el cancel remoto (bloqueador en `contarIntentosBloqueadores`, también H.3), la liberación atrasada (CAS `IS NOT 'APPROVED'`), la cerca de la MISMA venta (guarda 2 y el CAS a AUTORIZANDO) y sobrevive a recrear la pantalla (restauración por solicitud con importe/propina/venta). Una venta LOCAL sin solicitud del POS conserva el camino viejo (sin espera ni botón). |
| Duplicado en PROCESSING | ACK sin volver a navegar ni ejecutar el SDK. |
| Solicitud ya resuelta | Reproducir el resultado durable, sin otra autorización. |
| Cancel remoto | El árbol de la TPV (no el APK publicado) acepta mediante CAS de RECEIVED a RESOLVED; devuelve ALREADY_RESOLVED con replay, o ACTIVE cuando no puede acreditar una cancelación segura (`RemotePaymentInbox.cancel`). El `Deliver` de HEAD contesta `accepted=queued`: con el canal lleno, ACK negativo sobre una fila ya persistida RECEIVED. |
| Desenlace pendiente | Conservar la obligación y comunicar incertidumbre. ACTIVE es la respuesta correcta mientras la solicitud siga reclamada (PROCESSING) o exista una ejecución o un intento de libreta que la respalde; una fila PROCESSING sin intento correlacionado también contesta ACTIVE y pasa a conciliación explícita (ver «Defectos conocidos»): nunca se convierte en NOT_FOUND ni en `failed + PRE_AUTHORIZATION`. |
| Sonda sin fila local | Responder NOT_FOUND **dejando lápida** (`NOT_FOUND_ANSWERED`, 11-sep): la declaración es durable y esa solicitud ya no se ejecuta en esta bandeja aunque llegue después; sin lápida escrita no se contesta. El servidor decide su alcance (sólo libera filas nunca entregadas). No convertirlo localmente en «no se cobró». |

Un ACK positivo o un resultado financiero debe basarse en estado durable. Si `receive` devuelve Reject
por un fallo de persistencia, `SocketManager` responde `accepted:false`; eso no demuestra que la bandeja
esté vacía. Si el servidor recibe ese rechazo mientras la solicitud sigue PENDING, la pasa a
UNKNOWN/ACK_REJECTED; si no recibe el ACK, puede quedar UNKNOWN/ACK_TIMEOUT. La sonda requiere una
terminal que declare la capacidad: su respuesta depende del estado durable encontrado. NOT_FOUND
sólo permite la transición prevista por el servidor sin ACK positivo registrado, fuera de vuelo
y dentro del predicado de desenlace pendiente; RECEIVED_CANCELLED o RESOLVED siguen sus validaciones
de evidencia. Ninguno de esos desenlaces se garantiza por haber fallado Room.
🔴 **«Sin ACK ⇒ nunca recibida» YA NO es la regla del servidor (11-sep, plan D de Codex).** NOT_FOUND libera
SÓLO una fila que nunca se entregó a ningún socket: su `deliveryProvenance` es exactamente `{deliveries: []}`, y esa
condición va dentro del propio UPDATE. Una fila entregada —a un socket legacy (v2.8.7, la PAX de Testarudo, sin bandeja
ni `terminalPaymentAckVersion`), o a uno durable cuyo ACK se perdió— o de procedencia desconocida (`null`, anterior a la
columna) se conserva y se audita UNA vez (`TERMINAL_PAYMENT_PROBE_UNACCREDITED`, evidencia `NOT_FOUND_AFTER_DELIVERY` /
`NOT_FOUND_UNKNOWN_PROVENANCE`). El motivo de no aceptar el NOT_FOUND de una bandeja durable: esa bandeja puede haberse
vaciado DESPUÉS de ejecutar el cobro (downgrade por `INSTALL_VERSION`, que es destructivo; borrado de datos;
reinstalación; `FACTORY_RESET`), y NOT_FOUND no distingue «nunca la tuve» de «la tuve y me borraron». 🔴 Consecuencia
declarada (auditoría del 11-sep, P2-2): una PAX dormida que perdió el ACK queda reservada hasta que un operador la
concilie, y hoy no hay palanca (`releaseUnknownRequest` no libera) — decisión del founder pendiente.
Si falla la red, conservar lo necesario para reproducir el resultado y no afirmar recepción del servidor.
Impresión, reembolso y comandos administrativos usan sus propios contratos; los dirigidos a otra
terminal se ignoran. Ningún evento financiero debe destruir trabajo del cajero sin recuperación.

**Contrato del reembolso remoto:** `terminal:refund_request` solicita ABRIR la interfaz de devolución;
no autoriza ni acredita un reembolso. `opened` sólo puede enviarse después de confirmar que la navegación
aceptó y mostró el pago solicitado. Si un cobro activo impide abrirlo, responder el fallo correspondiente
del protocolo de reembolso; nunca `opened`. (Hoy `HomeViewModel.openRemoteRefund` contesta `opened` de
inmediato y `AppNavigation` puede descartar la petición si hay un cobro en curso: es un falso positivo
conocido.) El resultado financiero de la devolución se verifica por separado en procesador, cola, libreta
y servidor. Incluir expresamente la celda «reembolso remoto mientras se está cobrando».

## Lo que existe hoy (para no reinventarlo)

- `RemotePaymentCoordinator` del **árbol**: canal en memoria de capacidad 1 y `queuedRequestIds`; Room
  decide la admisión mediante claim por solicitud y venue. El cancel remoto consulta la bandeja: cancela
  atómicamente una solicitud RECEIVED, reproduce un resultado ya resuelto o responde ACTIVE. No navega
  fuera de una ejecución reclamada. **Este comportamiento difiere del APK publicado (HEAD)**, que
  resolvía en su bandeja sin contestar la disposición y emitía cancel incluso sin cobro en curso.
- `RemotePaymentInbox` (Room `remote_payment_requests`): persiste la solicitud antes del ACK positivo,
  reconoce duplicados, rechaza reutilizar un `requestId` con otro contrato monetario y conserva los
  resultados finales para reproducirlos ante una reentrega o sonda. **La reconexión, por sí sola, no
  garantiza que se reproduzcan todas las filas**: aplican las capacidades, estados y vencimientos del
  protocolo del servidor. 🪦 **La sonda deja LÁPIDA (11-sep, Codex plan D):** cuando la bandeja no tiene la
  solicitud, escribe `NOT_FOUND_ANSWERED` (sin contrato de dinero, nunca entregable) ANTES de contestar
  NOT_FOUND; `receive` rechaza esa solicitud si llega después (entrega retrasada, replay de un servidor sin
  procedencia), la sonda repetida contesta lo mismo, un cancel sobre la lápida sigue siendo ACTIVE, y sin
  lápida escrita (Room falla, evento sin `venueId`) no hay respuesta: el servidor conserva la reserva.
- `PaymentAttemptLedger` del árbol **no es una cadena lineal**. Distingue preparación, entrada al kernel,
  autorización, aprobación, incertidumbre y registro. Un registro fallido puede pasar de
  `REGISTRO_FALLIDO` a `ENTREGADA_A_COLA`: desde ahí la cola durable es responsable del registro.
  `REGISTRADO` puede pasar después a `CERRADA`. `reserveTerminal` impide otra reserva mediante una operación
  atómica sobre los estados de bloqueo definidos en el DAO. No atribuir estas garantías al APK publicado ni
  interpretar filas heredadas sólo por el nombre de su estado.
- Servidor (**árbol**; producción HEAD `3000f3d0` aún libera `UNKNOWN` por plazo, `d26bb746` — ver apuntador del
  servidor): UNA solicitud en vuelo por terminal (`409 TERMINAL_BUSY` con `blockingRequest`) **y por
  orden** (no se inicia otro cobro sobre una orden con desenlace pendiente, ni se cancela esa orden para
  eludirlo); nunca libera a ciegas (`UNKNOWN` retiene la ranura); sonda a terminales que la declaran;
  evidencia acreditada (`outcomeEvidence`); clases de evidencia A/B/C (relevo Testarudo §2-ter).
  **Procedencia de cada entrega (11-sep, Codex plan D):** `TerminalPaymentRequest.deliveryProvenance` se
  escribe ANTES de emitir (protocolo LEGACY/DURABLE, capacidades, socket, replay); sin ella grabada no se
  emite. El replay al reconectar sólo reentrega filas entregadas con protocolo DURABLE (legacy o procedencia
  desconocida se saltan con auditoría `TERMINAL_PAYMENT_REPLAY_SKIPPED`); NOT_FOUND libera sólo filas con
  procedencia `[]` (nunca entregadas a ningún socket), con esa condición dentro del propio UPDATE.
- POS: llave durable en disco antes del POST; 409 correlacionado por `requestId` (T15) — acredita que
  ESA solicitud nueva no se creó, no resuelve el cobro del bloqueador; «cancelar ≠ no se cobró» (consulta
  el estado hasta que conste); un GET 404 no acredita ausencia de cargo.

## Defectos conocidos (no reinventar el diagnóstico)

- 🔴 **La recuperación de aprobaciones del árbol selecciona filas sin filtrar SALE/REFUND**
  (`PaymentAttemptDao` — consulta de candidatas por `HOST_RESPONDIO/AUTORIZADO/REGISTRO_FALLIDO` sin
  `kind`) **y usa registradores de ventas** (`LedgerApprovalRecovery`). No desplegar esa recuperación sin
  separar los tipos de operación y verificar que un REFUND nunca se registra como SALE. Comprobar también
  que el reembolso sincronizado actualice su obligación en la libreta; `pending_refunds = SUCCESS` no basta
  si otra fila sigue reservando la terminal.
- 🔴 La libreta deja filas que mienten: reembolso rechazado por Blumon en el preflight (TX_024) marcado
  AUTORIZANDO y luego INDETERMINADO; venta abortada por un cancel en PREPARANDO.
  INDETERMINADO/HOST_RESPONDIO no se liberan por antigüedad ni tienen liberación manual; su recuperación
  exige evidencia. PREPARANDO tampoco tiene una liberación temporal garantizada: `discardStalePreparing`
  selecciona filas del venue actual con `updated_at` de hace más de 10 min, pero `runGated` omite ese
  barrido con `paymentLedgerMode=OFF` o sin venue. El árbol reserva aunque el modo sea OFF, y la reserva
  física puede quedar retenida por otro venue. Se programa trabajo periódico de 6 h y trabajo diferido
  2 min al arranque/login y al reconectar el socket; esos valores no garantizan el momento de ejecución.
  Resolver esta diferencia antes de presentar el barrido como salida de disponibilidad, sin atribuir
  a filas heredadas garantías que su versión no tenía.
- 🔴 **La llegada de un remoto durante una venta LOCAL debe evaluarse por ruta y estado real**.
  En las rutas de pago, calificación, propina y selección de cuenta publican un intento activo:
  con esa bandera publicada, `AppNavigation` rechaza el remoto antes de autorizarlo. No afirmar
  que el paso 3 se abandona siempre. Fuera de esas rutas puede abrirse el cobro remoto:
  `FastPaymentEntryScreen` guarda el monto con `remember`, por lo que puede perderlo al salir
  de composición. En Checkout, la navegación por sí sola no demuestra pérdida del carrito:
  su ViewModel y la entrada anterior pueden conservarlo. Probar las celdas 2, 3 y 9, incluidas
  las transiciones de la bandera y el regreso desde el cobro remoto, y decidir qué trabajo se
  conserva o qué solicitud nueva se rechaza. Estas celdas no están acreditadas en hardware.
- 🔴 **Una fila `PROCESSING` de la bandeja sin intento correlacionado en la libreta requiere
  conciliación explícita**: la sonda responde `ACTIVE`, la reentrega devuelve `AckOnly` y el barrido
  no resuelve esa fila de la bandeja. Un caso es la muerte del proceso entre `markProcessingForVenue`
  y `openAttempt`; sólo si se acredita esa interrupción concreta puede concluirse que ese camino
  no llegó al SDK. La ausencia de libreta NO basta: el APK publicado permite continuar con la libreta
  desactivada o tras fallar su escritura, y existen eliminaciones posteriores de filas terminales.
  No fabricar `PRE_AUTHORIZATION`, repetir la autorización ni liberar por esa ausencia.
  `releaseUnknownRequest` puede conciliar un Payment exacto a COMPLETED, pero no ofrece liberación
  manual sin evidencia. Falta asignar un responsable de resolver también la obligación local,
  conservando la correlación y descartando cualquier ejecución todavía capaz de autorizar.
- 🔴 **Con el árbol, un cancel remoto sobre una ejecución reclamada NO se refleja en la pantalla de la
  terminal** (el flujo `paymentCancelRequests` de HEAD se retiró; `HomeViewModel` descarta
  `TerminalPaymentCancel`). La terminal sigue pidiendo tarjeta y el cliente puede pagar un cobro que el
  cajero ya canceló; el servidor lo cierra tarde con 🚨 y la tablet dice «sigue activo». Es el reverso exacto
  del defecto del APK publicado (que navegaba al inicio con el SDK leyendo). Celda 4 × `payment_cancel`:
  decidir el aviso en terminal antes de desplegar.
- El selector de terminales del POS no muestra «ocupada» ni «dormida»: el cajero descubre el bloqueo al
  enviar (fase 3 del selector, sin construir).
- 🔴 **Cierre remoto partido (AngelPay 1.0.19, Codex r1 P2, 18-sep; pendiente declarado):** el «no se cobró» de un cobro
  del POS se escribe en dos pasos — el CAS de la libreta y después el final en la bandeja (`SocketManager`, otra
  corrutina). Si el proceso muere entre los dos: no hay negativo falso, **se pierde la notificación**, la sonda contesta
  ACTIVE, y el servidor deja la solicitud UNKNOWN al vencer y suelta la ranura por tiempo (`AUTO_RELEASED`, 20 min tras
  el regreso de la terminal) con la venta protegida. Arreglo pendiente: commit conjunto o una obligación durable de
  completar ese final. Detalle: `docs/ANGELPAY_INTEGRATION.md` › «SDK 1.0.19».
- ⚠️ **Residual declarado del veto durable (AngelPay 1.0.19, Codex r2):** si el proceso muere entre el aviso de S5 (con su
  propia escritura fallida) y la escritura de su veredicto desde la pantalla, o si fallan esa escritura Y la reapertura de
  la fila, la terminal pierde la evidencia de ese dinero (el servidor conserva su Payment). Queda el reporte
  `AngelPaySdkContradiccion` cuando la pantalla alcanzó a escribirlo. El arreglo de raíz sería que S5 reintentara su
  propia escritura antes de publicar. Detalle: `docs/ANGELPAY_INTEGRATION.md` › «Sin red», pregunta 4.
- 🔴 **Efectivo en un cobro remoto (medido en hardware el 10-sep, N86 HEAD 2.9.2 + servidor del árbol).** La
  pantalla «Método de Pago» del remoto ofrece **Efectivo** sin condición (`AngelPayPaymentScreen.kt`, HEAD :595, árbol :603,
  `showCashOption = true`); el cajero lo confirma, la terminal registra una venta rápida CASH propia y emite
  `payment_result status=success` sin método; el servidor sólo cierra con tarjeta (`closeRowFromPaymentTx`,
  árbol y HEAD) ⇒ degrada a `timeout`, deja la fila UNKNOWN (terminal reservada) con el dinero ya cobrado, y la
  tablet dice «cobro sin confirmar» con su carrito vivo: un segundo registro a un toque. Conciliada a mano a COMPLETED **por SQL en la base local de QA** (no hay camino de producto:
  `findReconcilablePayment` sólo mira tarjeta, `:1376`, y `releaseUnknownRequest` no libera), la tablet cierra con un solo Payment pero pinta el recibo como «Tarjeta». Decisión de producto
  pendiente (ocultar Efectivo en remotos, o aceptar CASH como cierre y comunicar el método). No liberar esa
  fila como «sin dinero»: el dinero SÍ entró.
- 🔴 **Un rechazo legítimo de HEAD se vuelve UNKNOWN con el servidor del árbol (medido, celda 3, 10-sep):** con
  una venta local en curso (Calificación), HEAD rechaza el remoto sin tocar el SDK y emite `payment_result
  status=failed` SIN `outcomeEvidence`; el servidor del árbol lo degrada a `timeout` y deja la fila UNKNOWN en
  < 1 s (terminal reservada, tablet «cobro sin confirmar»). Es el P1-1 de la auditoría en hardware: el orden de
  despliegue no es libre.
- 🔴 **Tres grupos de APK, no dos** (Codex, 11-sep, verificado): 2.8.7 no declara ninguna capacidad; 2.9.2 declara sólo
  `terminalPaymentAckVersion` (acuse, pero sus `failed`/`cancelled` siguen sin evidencia ni disposición); el árbol declara
  acuse, disposición y sonda. Cualquier regla «por capacidad» debe distinguir los tres y guardar la procedencia de la
  entrega POR SOLICITUD y ANTES de emitir. ✅ En el árbol desde el 11-sep (plan D): `deliveryProvenance` se graba con
  `await` antes del `emit`, en los dos protocolos, y sin ella grabada no se emite. Producción (HEAD) sigue sin procedencia.
- 🔴 **Reejecución tras actualizar el APK**: `replayPendingForTerminal` reenvía filas sin desenlace según las capacidades
  del socket ACTUAL, no del que recibió la entrega original; una fila entregada a un APK sin bandeja puede reenviarse a la
  app nueva, cuya bandeja no la tiene, y ejecutarse otra vez. Y la sonda de la TPV no dejaba lápida al contestar NOT_FOUND,
  así que una entrega tardía posterior se ejecutaría. ✅ Cerrado en el árbol el 11-sep (plan D): el replay sólo reentrega
  filas de procedencia DURABLE (legacy o desconocida ⇒ `TERMINAL_PAYMENT_REPLAY_SKIPPED`) y la bandeja deja lápida
  `NOT_FOUND_ANSWERED` antes de contestar NOT_FOUND. Sigue abierto en producción hasta desplegar servidor y APK.
- 🔴 **Históricos**: `UNRESOLVED_FINANCIAL_OUTCOME` bloquea todo TIMED_OUT sin mirar `failureCode`; las filas que
  producción liberó por tiempo (`AUTO_RELEASED`) volverían a reservar su terminal el día del despliegue. Nulo en
  procedencia significa «desconocida», no «nunca entregada»: no inferir garantías de una bandeja que no existía.
  **Medido en producción el 11-sep (sólo lectura):** producción no tiene la columna `cancelDisposition` y la migración la
  agrega en NULL ⇒ el predicado del árbol bloquearía 305 filas en la PAX de Testarudo `2841653112`, 53 en la Nexgo
  `n860w173400` y 12 en la Nexgo de Amaena `n860w173570` desde el primer minuto (relevo §2-ter).
- 🔴 **Sin turno abierto (turnos habilitados), un remoto se ACUSA y se DESCARTA en silencio** (medido en la PAX, celda
  10, 10-sep): la app navega al cobro, el guard de turno la regresa al Inicio y no contesta nada al servidor (ni rechazo
  ni resultado); la fila queda SENT y la tablet espera hasta su tope. Debe contestar `failed + PRE_AUTHORIZATION`
  («abre la caja primero») antes de tocar el SDK.
- 🔴 **Pantalla apagada sin Doze (USB): el remoto llega y se ACK-ea, la terminal NO despierta** (celda 13,
  medido): la tablet espera contra una pantalla negra. Ni la TPV enciende la pantalla al recibir un cobro ni el
  POS sabe que está dormida.
- 🔴 **En HEAD, el cobro remoto reemplaza la pantalla y el cancel manda al Inicio**: el carrito de Cobrar
  ($100) y el monto tecleado en Pago rápido ($75) se PERDIERON, medido en la N86 el 10-sep (celdas 2 y 9). El
  árbol rechaza el remoto sólo con la bandera de intento activo publicada (ver arriba); fuera de esas rutas navega igual al cobro remoto encima de la pantalla actual (el monto de
  `FastPaymentEntryScreen` se pierde en los dos por el `remember`), pero **no navega al Inicio al cancelar**
  (`HomeViewModel.kt:1092`; el handler durable de `SocketManager` nunca navega), así que la pérdida del carrito
  medida en HEAD no está acreditada en el árbol — allí el defecto es el anterior: la terminal sigue pidiendo tarjeta.
  Qué trabajo se conserva sigue sin decidirse.

## Lo medido el 10-sep (trampas concretas, no teoría)

- **Cancelar desde la tablet** con la PAX publicada en «Acerca o inserta la tarjeta» y el servidor del
  árbol: la PAX volvió al inicio sin comunicar disposición. Sin `Payment` ni desenlace acreditado, el
  vigía deja `status=UNKNOWN` y `failureCode=TIMED_OUT` al procesar la solicitud vencida o la cancelación
  cuya gracia de 30 s haya transcurrido (no es un plazo exacto de respuesta HTTP). El árbol de la TPV añade
  la disposición (`terminal:payment_cancel_disposition`). **Aplicar el plan de compatibilidad del
  apuntador del servidor antes de decidir el orden de despliegue.**
- **Atrás en la Nexgo** durante un cobro remoto: `cancelled` sin evidencia → el servidor no libera; al
  reconectar no hay sonda porque el APK publicado no la declara.
- **Dos `MainActivity` apiladas** (`am start -n …` con otra app encima): el cobro remoto se pintó en la
  instancia de abajo; la de arriba mostraba el inicio. Artefacto de QA, pero la app tampoco lo detecta.
- **Ocho celdas más en la PAX (HEAD 2.9.2-sandbox, 22:48–23:14)**: Cobrar con carrito y Pago rápido con monto
  (perdidos), Calificación con venta local (rechazo → UNKNOWN), Detalle de pago, pantalla apagada (no despierta), sin
  turno (descarte silencioso) y las dos mitades de la nota del founder; más el 503 del túnel que dejó a la tablet con
  una llave irresoluble (P1-3 en hardware). Detalle en el relevo §2-ter «Matriz — segundo bloque, PAX».
- **Siete celdas de la matriz en la N86 (HEAD) sin tarjeta**: Mensajes, Cobrar con carrito, Pago rápido con monto,
  Efectivo sobre un remoto, Detalle de un pago, Calificación con venta local en curso y pantalla apagada (Reembolso:
  motivo NO ejecutada: exige el botón de dinero «Procesar Reembolso», que toca el founder; arranque en frío parcial: **en la N86** el socket de HEAD conectó ~12 s después de lanzar la app y después de
  inicializar AngelPay y comercios, así que desde la tablet no se alcanzó la ventana; es coincidencia de tiempos, no
  orden del código (`connectSocketIfNeeded()` va en el `init` de `HomeViewModel`, HEAD :243, en paralelo con Blumon
  :703) y `awaitPaxPaymentReady` es no-op en Nexgo (`AppNavigation.kt:3235-3243`): en una PAX con Blumon lento la
  ventana SÍ puede existir y no está medida. El caso real es la reproducción de una fila pendiente al reconectar) — resultados y filas en el relevo §2-ter «Matriz … primer bloque»; la PAX no entró en el
  selector en todo el bloque por Doze.
- **Banner «No se pudo conectar» con el socket vivo**: el cobro llegó igual. **Y al revés**: la PAX dormida (Doze) sigue figurando «conectada» mientras el servidor no detecta la caída del
  socket (el registro sólo se borra al `disconnect`, `terminal-registry.ts:114-119`) y no recibe nada; pasada esa
  ventana desaparece del selector — por eso en el bloque de la matriz no apareció en todo el rato. Tener un `socketId` registrado no demuestra
  que la terminal esté disponible para ejecutar ahora.

## Cómo se prueba (receta)

1. Un aparato real por marca (PAX/Blumon y Nexgo/AngelPay) y una tablet con el POS. Hardware, no
   emulador: el SDK y Doze no se emulan.
2. Usar aparatos y datos de QA identificados. **No borrar `avoqado_database*` como preparación
   rutinaria**: contiene bandeja, libreta, `pending_payments` y `pending_refunds`, y puede haber una
   autorización incierta o aprobada todavía fuera de `pending_payments`; borrar la bandeja también
   compromete la interpretación posterior de `NOT_FOUND`. Inventariar y conservar inbox, libreta, pagos y
   reembolsos pendientes, incluidos desenlaces inciertos y aprobaciones aún no encoladas. Probar
   actualizaciones sobre la base anterior con datos (migraciones de Room, `critical-warnings.md`). Un
   reinicio limpio es un escenario separado, en un entorno descartable y sin obligaciones monetarias
   pendientes; **nunca es una forma de liberar una terminal ni una prueba de migración**.
3. **Nunca `am start -n …MainActivity` sobre la TPV ya abierta** (apila instancias). Trae la tarea al
   frente o usa el launcher.
4. Lleva la terminal al estado de la fila con adb (`uiautomator dump` + `input tap`), dispara el evento
   desde la tablet, y **lee los tres lados**: servidor (`TerminalPaymentRequest`, log con `correlationId`),
   terminal (bandeja, libreta y logcat) y POS (llave durable, log `💳`). Las tablas de la terminal se
   consultan con Room/SQLite o con una copia consistente obtenida mediante backup, o con la base cerrada y
   sus archivos necesarios preservados. **No inferir ausencia de filas leyendo únicamente el archivo
   principal mientras el WAL pueda contener cambios.**
5. Registrar los bloqueadores **antes y después** de cada celda y compararlos por solicitud e intento. Un
   desenlace resuelto no debe añadir bloqueadores injustificados. Una ejecución activa, incertidumbre o
   contradicción debe conservar la protección esperada (el predicado `UNRESOLVED_FINANCIAL_OUTCOME`
   conserva a propósito los desenlaces no acreditados; una contradicción con ACK se mantiene). Comprobar
   quién conserva la obligación y cómo se consulta o recupera. **Nunca borrar filas, inventar evidencia ni
   liberar reservas para conseguir un conteo cero.**
6. La mayoría de las celdas **no necesitan tarjeta** (mostrar / encolar / rechazar / cancelar). Las que
   sí, se piden al founder **EN MAYÚSCULAS y con la acción exacta** («PORFAVOR ACERCA LA TARJETA POR
   CONTACTLESS A LA PAX», «PORFAVOR NO PASES TARJETA»).
7. En el reporte se declaran **celdas ejecutadas, no ejecutadas y no aplicables, con justificación**. Se
   separan los resultados observados en hardware de los comportamientos inferidos del código; **nunca se
   presenta como comprobada una celda no ejercitada**. «Probé el camino feliz» no cubre esta regla.

Evidencia y detalle de todo lo anterior: `avoqado-server/docs/investigations/testarudo-relevo-2026-09-10/README.md` §2-ter.
