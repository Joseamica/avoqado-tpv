# App de campo (`:campo`, com.jaac.avoqado_tpv.campo) — cambios por versión

Esta app tiene su propia versión y su propio release firmado (`.github/workflows/campo-release.yml`), aparte de la app de
las terminales: por eso sus cambios van aquí y no en el `CHANGELOG.md` de la raíz, que es el de las notas de versión de la
TPV (`/avoqado:release-production`).

## Sin publicar

## 0.6.0 (código 6) — lo que pidió el cliente en sus pruebas del 26-nov (Asana 1220750328814319)
- «Mis ventas» por mes: dentro de «Por revisar», «Exitosas» y «Rechazadas», las ventas van por mes, del más reciente al más
  viejo («noviembre 2026 (45)»), en la zona del celular. Va abierto el mes actual (o el más reciente) y los demás cerrados: un
  toque los abre. La fecha es la hora real de la venta (`saleAt`); si el SERVIDOR no la manda y la venta se hizo en este
  celular, la hora del toque (también la que la lista todavía no trae; corregir el precio no la cambia); si no, `soldAt`; y al
  final, la hora local de su fila. Lo que no trae fecha va al final, en «Sin fecha».
  «Por subir» no cambia. La copia guardada por la 0.5.0 se sigue leyendo sin red: al actualizar no se pierden «Mis ventas» ni
  las SIMs del escáner.
- Foto de la galería: junto a «📷 Tomar la foto» está «Elegir de la galería» (selector del sistema, sin permisos) en Vender
  (vinculación y portabilidad), en «Corregir evidencia» de «Mis ventas» y en «Ventas pendientes por subir». La imagen se copia
  al celular antes de nada y pasa por la misma compresión que la de la cámara, también sin red; si no se puede leer, lo dice.
  La copia la hace la pantalla de fondo, así que abrir la cámara de la otra foto no la corta. Mientras se copia («Guardando…»)
  ese espacio no tiene foto (nunca se registra la anterior), y «Registrar venta», «Enviar corrección» y «Guardar corrección»
  esperan; con dos fotos copiándose a la vez, «Registrar venta» espera a las dos.
- No se vende sin entrada: sin turno abierto en el celular (también uno checado sin red), el mosaico «Vender SIM» dice «Checa tu
  entrada para vender» y lleva a «Checar entrada». Lo mismo con una entrada sin red que el SERVIDOR rechazó, también tras
  reabrir la app, y con la entrada de otra sucursal. Con la entrada de una sucursal y la sesión en otra, «Checar» ofrece la
  entrada de ésta (nunca la salida de la otra); esa entrada se guarda normal y, si el SERVIDOR ya tenía una, se usa con
  «Usar esa entrada». El celular guarda un solo turno: el de la otra sucursal se reemplaza en el celular sin mandarle salida. Al usar la entrada que ya tenía el SERVIDOR («Usar esa entrada»), la hora
  del turno es la de esa entrada, no la del intento rechazado. «Registrar venta» lo revisa en la misma escritura de la venta y con la misma hora que la fecha: si el turno
  venció con la pantalla abierta, no se guarda. Subir fotos pendientes y corregir evidencia no piden entrada.
- La hora de corte de la salida olvidada viene de la sucursal (`asistencia` de `GET tpv/modules`), en la zona de la sucursal
  (`timezone`; sin ella, la del celular): con «22:00», el turno vence en el siguiente 22:00 que caiga al menos 1 h después de
  la entrada, armado por fecha como en el SERVIDOR (el cambio de horario no lo mueve). Sin hora, como antes: 04:00, nunca antes
  de 12 h. En los dos casos el celular da su margen de 3 h (con corte a las 22:00, se vende hasta la 01:00): decisión del
  founder del 26-nov («B»), la app sólo exige una entrada válida (de esa sucursal, no rechazada y vigente) y la hora exacta la
  aplica el SERVIDOR con la salida olvidada. Con la salida olvidada apagada, no vence. Se guarda con la configuración de la
  sucursal: sin red se usa la última que bajó (también con señal, hasta que la copia se actualiza).
- Límite conocido (igual que la cámara desde la 0.5.0): la foto de una venta o corrección que todavía no se registra vive en
  la pantalla; si Android cierra la app antes de «Registrar venta» o «Enviar», hay que tomarla o elegirla otra vez.
- GPS del turno: si la persona no marcó salida y el turno ya es de un día terminado («salida olvidada»), el celular deja de
  guardar y mandar su ubicación: ninguna lectura de después del límite se guarda (tampoco la que lo cruza mientras busca la
  señal). El servicio se para en su siguiente lectura (cada 30 min con el celular despierto) o al abrir la app, y se quita
  el aviso fijo «Turno activo»; si Android revive el servicio solo, se para de inmediato. Antes el GPS seguía a nombre de un
  turno que el servidor ya había cerrado (hz:nube0000:na1c5a48).

## 0.5.0 (código 5) — lo que pidió PlayTelecom en sus pruebas (Asana 1220750328814319, 20-nov)
- Venta sin costo: si la SIM no trae precio fijo, el importe puede ser $0 (como «Regalar (Gratis)» en la terminal); la
  confirmación dice «Sin costo».
- «Mis ventas», aparte de «Mis SIMs»: Por revisar (con «Corregir evidencia»), Exitosas y Rechazadas. Una venta rechazada ya
  no sale como «Vendida». El Inicio dice cuántas hay por revisar.
- «Mis pendientes» ahora se llama «Ventas pendientes por subir», y una venta sin señal sale en una sola línea: su foto y su
  número viajan con ella.
- Sin señal, «Mis ventas», «Mis SIMs», «N en tu custodia» y el escáner ya no se equivocan cuando la lista del celular va
  atrasada: cada SIM se decide con lo último que hizo el celular y cómo terminó. Una venta quitada o descartada no sale en
  «Exitosas», una que ya subió no vuelve a «En tu custodia» ni se deja escanear otra vez, una SIM ya aceptada no regresa a
  «Por aceptar» al quitar su venta, y la venta de una SIM que te reasignaron sigue en «Mis ventas».

## 0.4.0 (código 4) — la primera que se reparte
- Pantallas rehechas sobre los mockups aprobados el 8-nov: Inicio en mosaicos (Vender SIM, Mis SIMs con «N en tu custodia»,
  Checar con «Entraste HH:MM», Mis pendientes con contador) y chip de turno; Activar con el código en 6 cuadros; PIN con
  iniciales y teclado; Vender con zona de escáner, tarjeta de la SIM y «Registrar venta» fijo; venta guardada con palomita;
  Checar con una sola acción y la selfie con óvalo; Mis SIMs, Mis pendientes y Ayuda con el mismo estilo. Tema claro
  completo con los colores de la TPV (sin el lila por defecto de Material3) y ancho máximo en pantallas anchas.
- Lo que se lee, se lee: textos de aviso y verdes con contraste de 4.5:1 o más (lo cuida una prueba), avisos con fondo sólido
  aunque vayan encima de la cámara, y la banda «Sin señal» también en el escáner y las fotos, sin reiniciar la cámara al ir y
  venir la señal. Checar ya no afirma «Ubicación activada» con sólo el permiso; «N en tu custodia» cuenta sólo la sucursal en
  la que entraste, y «Entraste HH:MM» sólo sale si la entrada se marcó en este celular.
- Detalles de pantalla del full-testing del bloque 2 (D2): «Actualizar» en Mis SIMs dice «No se pudo actualizar, revisa tu
  señal.»; la banda «Sin señal» sale una sola vez; el GPS se revisa al volver al frente y Checar dice su estado actual;
  «Subiendo N» cuenta sólo lo que de verdad sube.

## 0.3.0 (código 3) — bloque 3, distribución (no se repartió)
- Se actualiza sola por su carril `ANDROID_CAMPO`: revisa, baja, comprueba (SHA-256, paquete, versión y firma) e instala con
  la confirmación de Android; una versión obligatoria frena lo nuevo y deja subir la cola y marcar salida.
- Release firmado con la llave propia de la app de campo (huella `d9d5375f…1079`).

## 0.2.0 (código 2) — bloque 2, la app del celular (no se repartió)
- Entrada con código y PIN, venta de SIM, custodia, evidencia, checada con GPS en primer plano, «Mis pendientes» y ayuda,
  todo con la cola sin red (lo hecho se guarda antes de tocar la red y sube solo).
