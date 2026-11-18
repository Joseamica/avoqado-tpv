package com.jaac.avoqado_tpv.features.remote_command.domain

import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase

/**
 * D4 (encargo dd4185): BAJAR de versión borra la base entera (`fallbackToDestructiveMigrationOnDowngrade`), y con ella el
 * efectivo y las rondas de Mesas hechas sin red y las cajas que el servidor no tiene. Es lo ÚNICO que la borra: actualizar
 * hacia arriba migra, y reiniciar, apagar, reactivar y el borrado de fábrica la conservan. Por eso este candado sólo frena
 * un retroceso: el de `INSTALL_VERSION` ([CommandExecutor]) y el «Actualizar» del diálogo de `REQUEST_UPDATE`
 * (`UpdateRequestManager.acceptUpdate`), que puede traer una versión anterior.
 *
 * Cuenta todo lo que se perdería, de todas las sucursales: también un rechazo en cuarentena o una caja en revisión, que
 * esperan a una persona. Frenar sólo el retroceso no deja la terminal sin actualizaciones: las de subida siguen pasando
 * (Codex r1–r2: contar eso en TODOS los comandos podía frenar la terminal para siempre).
 *
 * hz:nube0000:naaa0db5: también las fotos y números de vinculación (PlayTelecom) sin subir o por corregir y los cobros cripto
 * sin desenlace del SERVIDOR. Que el retroceso quede frenado mientras haya algo de eso es A PROPÓSITO, aunque una fila no tenga
 * salida (p. ej. un cripto que nunca llegó al SERVIDOR): la misma filosofía de D4, sólo se frena el retroceso y las subidas siguen
 * pasando. En producción el retroceso sólo lo manda el SUPERADMIN.
 */
suspend fun AvoqadoDatabase.hayAlgoQueBajarDeVersionBorraria(): Boolean =
    REVISIONES_ANTES_DE_BAJAR_DE_VERSION.any { cuenta -> cuenta(this) > 0 }

/**
 * Aquí se agrega cada cola que no debe perderse al bajar de versión: una línea por cola, con lo que el SERVIDOR todavía no
 * tiene o espera a una persona (D27 agregará la suya).
 */
private val REVISIONES_ANTES_DE_BAJAR_DE_VERSION: List<suspend AvoqadoDatabase.() -> Int> = listOf(
    { syncIntentDao().contarQueBajarDeVersionBorraria() },            // Mesas: por subir o rechazadas
    { localShiftSessionDao().contarQueBajarDeVersionBorraria() },     // cajas que el servidor no tiene
    { proofOfSaleQueueDao().contarQueBajarDeVersionBorraria() },      // fotos y números de vinculación (PlayTelecom)
    { localCryptoOperationDao().contarQueBajarDeVersionBorraria() },  // cobros cripto sin desenlace del servidor
)

/** ¿[versionCode] es anterior a la app instalada? Lo mismo que `executeInstallVersion` llama `isDowngrade`. */
fun esBajarDeVersion(versionCode: Int?): Boolean =
    versionCode != null && versionCode < com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE

const val MENSAJE_BAJAR_DE_VERSION_BORRARIA =
    "No se bajó de versión: borraría ventas de mesas, cortes de caja, cobros cripto o fotos y números de vinculación que no " +
        "han subido o esperan revisión. Resuélvelos y vuelve a mandarla."
