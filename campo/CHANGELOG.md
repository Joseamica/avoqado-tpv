# App de campo (`:campo`, com.jaac.avoqado_tpv.campo) — cambios por versión

Esta app tiene su propia versión y su propio release firmado (`.github/workflows/campo-release.yml`), aparte de la app de
las terminales: por eso sus cambios van aquí y no en el `CHANGELOG.md` de la raíz, que es el de las notas de versión de la
TPV (`/avoqado:release-production`).

## Sin publicar
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
