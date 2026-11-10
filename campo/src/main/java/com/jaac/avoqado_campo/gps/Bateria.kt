package com.jaac.avoqado_campo.gps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** El aviso de batería: con la optimización puesta, Android puede dormir el GPS del turno. */
object Bateria {
    fun estaOptimizada(context: Context): Boolean =
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** La ventana directa («¿Permitir que se ejecute en segundo plano?») y, si el teléfono no la tiene, la lista de ajustes. */
    @SuppressLint("BatteryLife") // app instalada a mano, no de la Play Store
    fun intentParaArreglar(context: Context): Intent {
        val directo = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return if (directo.resolveActivity(context.packageManager) != null) directo
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
