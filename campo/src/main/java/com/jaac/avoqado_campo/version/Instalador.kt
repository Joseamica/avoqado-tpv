package com.jaac.avoqado_campo.version

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** `Enviado` sólo dice que Android ya la tiene: el éxito es que reemplaza la app; un fallo llega a [ReceptorDeInstalacion]. */
sealed interface ResultadoInstalacion {
    object FaltaPermiso : ResultadoInstalacion
    object Enviado : ResultadoInstalacion
    data class Fallo(val motivo: String) : ResultadoInstalacion
}

/**
 * Instala con `PackageInstaller` (sesión completa, del paquete propio). Un celular personal confirma con el diálogo normal
 * de Android: nada de `setRequireUserAction` (Codex r1 SOBRA). Sin «Permitir de esta fuente» lleva a ese ajuste.
 */
@Singleton
class Instalador @Inject constructor(@ApplicationContext private val context: Context) {
    private val instalador get() = context.packageManager.packageInstaller

    fun puedeInstalar(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun instalar(archivo: File): ResultadoInstalacion = withContext(Dispatchers.IO) {
        if (!puedeInstalar()) {
            abrirElPermiso()
            return@withContext ResultadoInstalacion.FaltaPermiso
        }
        var id = -1
        try {
            id = instalador.createSession(parametrosDeSesion(context.packageName, archivo.length()))
            instalador.openSession(id).use { s ->
                archivo.inputStream().use { e -> s.openWrite("campo.apk", 0, archivo.length()).use { out -> e.copyTo(out); s.fsync(out) } }
                s.commit(alTerminar(id).intentSender)
            }
            ResultadoInstalacion.Enviado
        } catch (e: IOException) {
            abandonar(id)
            ResultadoInstalacion.Fallo(e.message ?: "no se pudo preparar la instalación")
        } catch (e: SecurityException) {
            abandonar(id)
            ResultadoInstalacion.Fallo(e.message ?: "Android no dejó instalar")
        }
    }

    /** Al arrancar: una sesión que quedó abierta (la app murió a media instalación) se abandona; «Actualizar» empieza otra. */
    fun abandonarLasAbiertas() = instalador.mySessions.forEach { abandonar(it.sessionId) }

    private fun abandonar(id: Int) {
        if (id < 0) return
        try {
            instalador.abandonSession(id)
        } catch (e: SecurityException) {
            Log.w(TAG, "no se pudo abandonar la sesión $id", e)
        }
    }

    private fun abrirElPermiso() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "este Android no tiene el ajuste «Permitir de esta fuente»", e)
        }
    }

    // Mutable: Android le pone el resultado (EXTRA_STATUS). Explícito: sólo nuestro receptor lo recibe.
    private fun alTerminar(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id, Intent(context, ReceptorDeInstalacion::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    private companion object { const val TAG = "Instalador" }
}

internal fun parametrosDeSesion(paquete: String, tamano: Long) =
    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(paquete)
        setSize(tamano)
    }
