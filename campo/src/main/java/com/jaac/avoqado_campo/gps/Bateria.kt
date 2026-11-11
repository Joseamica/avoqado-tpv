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

    /**
     * La ventana directa («¿Permitir que se ejecute en segundo plano?») y, si el teléfono no la tiene, la lista de ajustes.
     * null = el teléfono no tiene ninguna de las dos: la pantalla esconde «Arreglar».
     */
    @SuppressLint("BatteryLife") // app instalada a mano, no de la Play Store
    fun intentParaArreglar(context: Context): Intent? = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ).firstOrNull { it.resolveActivity(context.packageManager) != null }
}
