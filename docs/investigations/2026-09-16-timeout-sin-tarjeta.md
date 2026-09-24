# Nexgo: timeout sin presentar tarjeta

## Alcance acordado y revisión de la documentación vigente

El founder pidió evaluar primero una solución pequeña y soportada, sin convertir este caso en un desarrollo grande de conciliación. **La alternativa manual de abajo queda como exploración, no como recomendación ni trabajo a implementar.** No se cambió lógica de pagos durante esta revisión.

El 16-sep-2026 se consultó el portal oficial autenticado, además del PDF local de junio:

- [Novedades](https://developers.angelpay-qa.com.mx/novedades): documentación SDK v1.14.0 del 26-ago; añade rechazos controlados para QA. La entrada anterior promete devolución de PaymentResult en errores y timeouts, sin modificar la API pública.
- [SDK](https://developers.angelpay-qa.com.mx/docs/sdk): U101 sigue documentado como `CANCELLED`, categoría USER, con reintento «Tras corregir». El catálogo recomienda tomar decisiones por CallResult.code. No especifica si U101 garantiza ausencia definitiva de autorización en todas sus rutas, ni un campo que distinga el timeout sin tarjeta. PaymentRequest no publica un timeout configurable del lector; los dos DisplayMillis controlan la pantalla de resultado.
- La descarga anunciada conserva fecha 19-ago y prefijo SHA-256 `ae7d0e063be7`, coincidente con el AAR local 1.0.18. El portal lo rotula 1.0.8 y el enlace contiene 1.0.17; no usar esos nombres como prueba de una versión distinta. No se acreditó un reemplazo binario nuevo.
- [Webhooks](https://developers.angelpay-qa.com.mx/docs/webhooks): describe eventos de transacciones procesadas, offline y canceladas; no garantiza un evento definitivo de no cargo cuando vence el lector sin tarjeta. El [Swagger de integraciones QA](https://integrations-api.angelpay-qa.com.mx/docs) no publica una consulta de desenlace financiero por integratorReference.

**Conclusión acotada:** no se encontró una opción documentada que permita automatizar con seguridad este cierre, pero tampoco se demostró que AngelPay necesite cambiar el SDK. Primero corresponde aclarar si U101 garantiza no cargo y ejecución terminada en todas las rutas, o qué combinación pública identifica exclusivamente el caso sin lectura. Una garantía existente podría permitir un ajuste acotado de clasificación y sus pruebas; no implica crear una conciliación manual.

## Hecho de la prueba

El founder confirmó que **no acercó ni insertó tarjeta**. En este intento no se espera un cargo con tarjeta ni un webhook de pago aprobado. No se debe pedir al founder que concilie un cargo como si se hubiera observado uno.

Entorno: AngelPay QA, comercio 107, Nexgo N860W173397, SDK embebido 1.0.18, importe $1.00 sin propina. Solicitud `3bdbc884-a830-4ffb-92dd-47fa13d01aaf`; intento/integratorReference `1e75a73a-554d-49ad-9193-f87b5344d9e8`.

El callback observado devolvió `approved=false`, `status=TIMEOUT`, `code=U101`. Avoqado conservó INDETERMINADO / PROCESSING. La consulta local del 16-sep encontró servidor TIMED_OUT / AUTO_RELEASED, sin Payment ni ProviderEventLog para el intento. El estado del servidor libera la ranura, no constituye una resolución financiera.

## Causa técnica comprobada

El resultado público del SDK no permite distinguir estos casos:

1. Venció el lector sin tarjeta.
2. Venció el plazo de la transacción, que pudo avanzar.

El bytecode de `b0.s` reutiliza `TIMEOUT`, `U101`, el mismo mensaje y el mismo helper. La ruta posterior al gateway puede pasar datos de su respuesta al helper; sin embargo, `b0.j` también produce U101 en el catch general de TimeoutCancellationException sin conservar esos datos. Por eso su ausencia tampoco identifica exclusivamente el caso sin tarjeta. `PaymentActivity.finishWith` escribe únicamente el JSON `angelpay_res` y usa RESULT_OK para todos los resultados serializados; el resultCode de Android no distingue las fases. `CallResult` sólo contiene code/status/category/message. No se encontró una API pública de fase/progreso en `AngelPaySDK`.

Evidencia de bytecode: `/Users/amieva/.codex/artifacts/tpv-qa-20260916/sdk-inspection/`, `b0.s.txt` líneas 960–981, 1198–1211 y 3229–3253; `b0.j.txt` líneas 628 y 681–689; `com.angelpay.angelpaysdk.ui.PaymentActivity.txt` líneas 1260–1289. La inspección independiente confirmó que no existe un discriminador público fiable.

El catálogo local y el vigente enumeran U101 como CANCELLED/USER, mientras PaymentResult.status puede ser TIMEOUT: son dos niveles del resultado, no una contradicción por sí misma. La incertidumbre técnica surge de que el AAR reutiliza U101 en varias fases y no se encontró la garantía de finalización financiera. `b0.s.a()` (líneas 328–379 del dump) intenta cancelar EMV y cerrar el lector, capturando errores; esa función no espera una reversa confirmada por el procesador. Esto no demuestra que alguna de esas rutas haya cobrado: limita lo que puede deducirse automáticamente del callback.

## Qué está corregido y qué sigue abierto

El aviso de incertidumbre que SocketManager descartaba ya tiene un arreglo instalado en QA, con 109 pruebas del módulo en verde y builds Nexgo/production verificados. Eso corrige el envío del aviso; **no corrige la clasificación de un timeout sin tarjeta**. Falta repetir la medición física con ese APK.

No hay una ruta existente que aplique una declaración del encargado de «no se presentó tarjeta»: `releaseUnknownRequest` no libera sin un Payment acreditado y, para esta fila TIMED_OUT, retorna antes de conciliar. `OPERATOR_RECONCILED_NO_CHARGE` sólo existe en el clasificador del servidor, sin escritor. S6 y la recuperación local actuales sólo aplican desenlaces positivos con Payment; escribir sólo una resolución en el servidor dejaría la obligación local sin resolver.

No se cambió U101 globalmente a no cobrado, no se fabricó PRE_AUTHORIZATION/PROCESSOR_DECLINED, no se eliminaron filas ni se inició otro cobro. La observación humana se registra aquí como tal; no se presenta como evidencia emitida por el SDK. Un camino de conciliación manual sería una capacidad nueva en servidor y TPV que requiere diseño y pruebas, no un UPDATE de QA. El contrato B actual (`avoqado-server/docs/investigations/testarudo-relevo-2026-09-10/diseno-A-B-seccion8-2026-09-11.md`, sección desde línea 194) exige revisión del procesador y cese acreditado; todavía no incluye la declaración presencial «nunca se presentó tarjeta» como categoría propia.

## Alternativa propia de Avoqado: resolución supervisada

**No es obligatorio esperar una modificación de AngelPay para construir una salida operativa.** La conclusión anterior sólo abarca la clasificación automática usando exclusivamente el resultado público actual del SDK. «No está implementado» y «el diseño B anterior no lo contempla» no significan que sea imposible.

Propuesta pendiente de diseño detallado y validación: categoría de evidencia `NO_CARD_PRESENTED`, declarada por OWNER/ADMIN presencial, distinta de una declinación del procesador. Exige confirmar que ninguna persona presentó tarjeta, teléfono, reloj u otro medio de pago durante ninguna ejecución del intento. «No sé» conserva la incertidumbre. Confiar en esta observación introduce riesgo de error humano; ni un permiso ni un registro de auditoría eliminan ese riesgo.

Antes de publicar NOT_CHARGED, la terminal debe acreditar que la ejecución terminó y guardar una barrera durable que impida reabrir ese mismo intento/solicitud tras duplicados, reconexión o reinicio. El cambio necesita una operación de servidor con actor y evidencia, sincronización idempotente de la libreta/bandeja y un resultado que el POS entienda. Mantener el resultado TIMEOUT original y `host_approved=null`: no fabricar un rechazo del host. La evidencia positiva tardía prevalece o genera una contradicción visible; no se descarta.

El callback de Activity, volver al Inicio o `isCharging=false` no bastan por sí solos para acreditar el cese de toda ejecución. La inspección adicional de `PaymentActivity.onDestroy` muestra que cancela su scope y llama `u.c0.h`, que intenta `stopSearch`/`close`; esos errores se capturan. Esto no expone al integrador un acuse fiable de que la limpieza terminó. El diseño debe resolver y verificar esa frontera antes de habilitar la liberación.

Estado: alternativa viable de diseño, **no implementada ni acreditada en hardware**. No cambia la clasificación automática de U101 ni libera el intento actual. Aumentar esperas, consultar un historial vacío o borrar datos no implementa esta conciliación.

Referencia de integración: Adyen distingue explícitamente timeouts de dispositivo, procesamiento y solicitud, y prescribe acciones distintas según la clase (https://docs.adyen.com/point-of-sale/error-scenarios/pos-timeouts). Esa documentación respalda separar las situaciones; **no demuestra que AngelPay ofrezca las mismas garantías ni avala esta propuesta manual**.

## Señal requerida para una solución automática, si no existe ya una garantía

Sólo si AngelPay confirma que el contrato actual no permite distinguirlo ni garantiza el no cargo, haría falta una señal adicional: un resultado inequívoco, correlacionado con integratorReference, que acredite que la sesión del lector terminó **antes de cualquier operación capaz de autorizar**, incluyendo autorización offline del kernel. Puede ser un código exclusivo o un campo de fase con semántica documentada. Los campos ausentes de tarjeta, la duración, un historial vacío y approved=false no sustituyen esa garantía. No se concluye todavía que sea necesario modificar el SDK.

Con esa garantía, Avoqado puede cerrar durablemente el intento y la bandeja, comunicar no cobrado al POS y permitir un nuevo intento. TIMEOUT después de comenzar una operación capaz de autorizar conservará la incertidumbre. Compatibilidad: SDK viejo o campo ausente conserva el comportamiento prudente actual.

Pruebas de aceptación: sin presentar tarjeta; tarjeta presentada sin respuesta del host; devolución tardía de aprobación; evento duplicado; proceso muerto antes/después de guardar el resultado; offline y reconexión; un único cierre en servidor y una única identidad en POS/libreta/bandeja. No declarar la solución terminada sin prueba real sin tarjeta y otra con tarjeta.

## Texto preparado para AngelPay (no enviado)

> Rafael: en SDK 1.0.18, ¿`approved=false`, `status=TIMEOUT`, `callResult.code=U101` garantiza que no hubo cargo y que ninguna ejecución puede autorizar después, también cuando vence la espera del resultado final posterior al gateway? El catálogo vigente indica CANCELLED/USER y permite reintentar. Si esa garantía no aplica a todo U101, ¿qué campo y valor público identifica exclusivamente el timeout sin lectura de tarjeta? Buscamos consumir correctamente el contrato existente antes de pedir cambios. Nuestro caso QA no involucró tarjeta; no estamos solicitando confirmar un cargo. Comercio 107, terminal N860W173397, intento `1e75a73a-554d-49ad-9193-f87b5344d9e8`, 16-sep-2026 alrededor de 08:49 CDMX.
