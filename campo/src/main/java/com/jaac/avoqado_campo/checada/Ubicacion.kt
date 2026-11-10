package com.jaac.avoqado_campo.checada

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Una lectura para la checada: la actual con alta precisión y, si no llega, la última de menos de 2 min; todo dentro de
 * [leer]`(esperaMs)`. Peor que 1 000 m no sirve (como `LocationService.kt:79` de la PAX). Sin permiso → null.
 */
class Ubicacion(
    private val context: Context,
    private val fused: FusedLocationProviderClient,
    private val permiso: () -> Boolean = { tienePermiso(context) },
    private val ahoraNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, LocationServices.getFusedLocationProviderClient(context))

    data class Lectura(val lat: Double, val lon: Double, val precision: Float, val falsa: Boolean)

    @SuppressLint("MissingPermission")
    suspend fun leer(esperaMs: Long = 10_000): Lectura? {
        if (!permiso()) return null
        val ultimaMs = minOf(500L, esperaMs / 2)
        return coroutineScope {
            val ultima = async { esperar(fused.lastLocation)?.takeIf { ahoraNanos() - it.elapsedRealtimeNanos < DOS_MIN_NANOS } }
            val token = CancellationTokenSource()
            val actual = withTimeoutOrNull(esperaMs - ultimaMs) {
                esperar(fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token))?.let(::util)
            }
            token.cancel()
            val l = actual ?: withTimeoutOrNull(ultimaMs) { ultima.await()?.let(::util) }
            ultima.cancel()
            l
        }
    }

    private fun util(l: Location): Lectura? = l.takeIf { it.accuracy <= MAX_PRECISION_M }?.let {
        @Suppress("DEPRECATION")
        Lectura(it.latitude, it.longitude, it.accuracy, if (Build.VERSION.SDK_INT >= 31) it.isMock else it.isFromMockProvider)
    }

    private suspend fun esperar(t: Task<Location>): Location? = suspendCancellableCoroutine { c ->
        t.addOnCompleteListener({ it.run() }) { r -> if (c.isActive) c.resume(if (r.isSuccessful) r.result else null) }
    }

    private companion object {
        const val MAX_PRECISION_M = 1_000f
        const val DOS_MIN_NANOS = 120_000_000_000L

        fun tienePermiso(c: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
    }
}
