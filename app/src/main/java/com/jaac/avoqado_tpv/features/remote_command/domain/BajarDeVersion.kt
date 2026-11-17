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
 */
suspend fun AvoqadoDatabase.hayAlgoQueBajarDeVersionBorraria(): Boolean =
    syncIntentDao().contarQueBajarDeVersionBorraria() > 0 || localShiftSessionDao().contarQueBajarDeVersionBorraria() > 0

/** ¿[versionCode] es anterior a la app instalada? Lo mismo que `executeInstallVersion` llama `isDowngrade`. */
fun esBajarDeVersion(versionCode: Int?): Boolean =
    versionCode != null && versionCode < com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE

const val MENSAJE_BAJAR_DE_VERSION_BORRARIA =
    "No se bajó de versión: borraría ventas de mesas o cortes de caja que no han subido o esperan revisión. Resuélvelos y vuelve a mandarla."
