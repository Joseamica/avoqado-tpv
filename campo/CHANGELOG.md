# App de campo (`:campo`, com.jaac.avoqado_tpv.campo) — cambios por versión

Esta app tiene su propia versión y su propio release firmado (`.github/workflows/campo-release.yml`), aparte de la app de
las terminales: por eso sus cambios van aquí y no en el `CHANGELOG.md` de la raíz, que es el de las notas de versión de la
TPV (`/avoqado:release-production`).

## 0.4.0 (código 4) — la primera que se reparte
- Pantallas rehechas sobre los mockups aprobados el 8-nov: Inicio en mosaicos (Vender SIM, Mis SIMs con «N en tu custodia»,
  Checar con «Entraste HH:MM», Mis pendientes con contador) y chip de turno; Activar con el código en 6 cuadros; PIN con
  iniciales y teclado; Vender con zona de escáner, tarjeta de la SIM y «Registrar venta» fijo; venta guardada con palomita;
  Checar con una sola acción y la selfie con óvalo; Mis SIMs, Mis pendientes y Ayuda con el mismo estilo. Tema claro
  completo con los colores de la TPV (sin el lila por defecto de Material3) y ancho máximo en pantallas anchas.
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
