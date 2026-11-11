package com.jaac.avoqado_campo.version

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.jaac.avoqado_campo.CampoApplication

/**
 * Lo que Android contesta a una sesión de [Instalador] (`exported=false`: sólo le llega nuestro PendingIntent). Pide
 * confirmación → se abre el diálogo del sistema; falla → el aviso del Inicio dice el texto del sistema. El éxito no llega
 * aquí: Android reemplaza la app.
 */
class ReceptorDeInstalacion : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        atender(context, intent) { (context.applicationContext as? CampoApplication)?.actualizador?.alFallarLaInstalacion(it) }
}

internal fun atender(context: Context, intent: Intent, alFallar: (ResultadoInstalacion.Fallo) -> Unit) {
    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
        PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Android instaló la versión nueva")
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            @Suppress("DEPRECATION") val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmar == null) return alFallar(ResultadoInstalacion.Fallo("Android no mandó su pantalla para confirmar"))
            try {
                context.startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                alFallar(ResultadoInstalacion.Fallo("Android no pudo abrir su pantalla para confirmar"))
            }
        }
        else -> {
            val texto = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
            Log.w(TAG, "Android no la instaló: status=$status $texto")
            alFallar(ResultadoInstalacion.Fallo(texto ?: "Android no la instaló (código $status)"))
        }
    }
}

private const val TAG = "ReceptorDeInstalacion"
