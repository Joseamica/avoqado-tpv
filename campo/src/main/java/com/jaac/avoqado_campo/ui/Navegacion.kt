package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaac.avoqado_campo.ayuda.AyudaScreen
import com.jaac.avoqado_campo.checada.ChecadaScreen
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.custodia.MisSimsScreen
import com.jaac.avoqado_campo.entrada.ActivarScreen
import com.jaac.avoqado_campo.entrada.PinScreen
import com.jaac.avoqado_campo.gps.EstadoDelGps
import com.jaac.avoqado_campo.identidad.BorradoDelCelular
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.pendientes.PendientesScreen
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.venta.VentaScreen

/** La primera pantalla, según «Quién soy» y la cola. */
sealed interface Destino {
    val raiz: String
    object Activar : Destino { override val raiz = "activar" }
    object Pin : Destino { override val raiz = "pin" }
    data class Gracia(val puedeActivarDeNuevo: Boolean) : Destino { override val raiz get() = "gracia" }
    object Desconocido : Destino { override val raiz = "desconocido" }
    data class Inicio(val necesitaPin: Boolean) : Destino { override val raiz get() = "inicio" }
}

/** [colaVacia]: nada PENDIENTE, RETENIDA, RECHAZADA ni AVISO (B6). `bloqueada` decide el PIN; `necesitaPin` sólo pone la banda (R3-2). */
fun destinoDe(identidad: Identidad, colaVacia: Boolean): Destino = when (identidad.estado) {
    EstadoDelCelular.SIN_ACTIVAR -> Destino.Activar
    EstadoDelCelular.DESVINCULADO, EstadoDelCelular.DADO_DE_BAJA -> Destino.Gracia(colaVacia)
    EstadoDelCelular.DESCONOCIDO -> Destino.Desconocido
    EstadoDelCelular.VINCULADO ->
        if (identidad.bloqueada || identidad.venueIdDeLaSesion == null) Destino.Pin else Destino.Inicio(identidad.necesitaPin)
}

/**
 * Todas las pantallas. El NavHost vive dentro de `key(raíz, estado, generación)`: al cambiar de persona, de estado o al borrar
 * el celular se vuelve a la raíz y ninguna pantalla vieja sigue abierta para encolar a nombre de otro.
 */
@Composable
fun Navegacion(quienSoy: QuienSoy, dao: PiezaDao, conectividad: Conectividad, borrado: BorradoDelCelular, estadoDelGps: EstadoDelGps) {
    val identidadS = quienSoy.identidad.collectAsStateWithLifecycle()
    val piezasS = remember { dao.pendientesDeVer() }.collectAsStateWithLifecycle(emptyList())
    val conRedS = remember { conectividad.cambios }.collectAsStateWithLifecycle(conectividad.hayRed())
    val turnoS = remember { dao.turno() }.collectAsStateWithLifecycle(null)
    val gpsS = estadoDelGps.estado.collectAsStateWithLifecycle()
    val identidad by identidadS
    val piezas by piezasS
    val destino = destinoDe(identidad, piezas.isEmpty())
    val generacion = remember(identidad) { quienSoy.generacion }
    fun porSubir() = piezasS.value.count { it.estado != EstadoPieza.AVISO }

    Column(Modifier.fillMaxSize()) {
        BandaDeSenal(sinRed = !conRedS.value, subiendo = piezas.count { it.estado == EstadoPieza.PENDIENTE })
        key(destino.raiz, identidad.estado, generacion) {
            val nav = rememberNavController()
            val regresar: () -> Unit = { nav.popBackStack() }
            NavHost(nav, startDestination = destino.raiz, modifier = Modifier.weight(1f)) {
                composable("activar") { ActivarScreen(hiltViewModel()) {} }
                composable("pin") {
                    PinScreen(hiltViewModel(), sinSenal = !conRedS.value) { if (nav.previousBackStackEntry != null) nav.popBackStack() }
                }
                composable("gracia") {
                    PantallaDeGracia(
                        pendientes = piezasS.value.size,
                        puedeActivarDeNuevo = piezasS.value.isEmpty(),
                        onPendientes = { nav.navigate("pendientes") },
                        onActivarDeNuevo = { borrado.activarDeNuevo(confirmado = false) },
                    )
                }
                composable("desconocido") {
                    PantallaDesconocido(
                        porSubir = porSubir(),
                        onPendientes = { nav.navigate("pendientes") },
                        onBorrar = { borrado.activarDeNuevo(confirmado = true) },
                    )
                }
                composable("inicio") {
                    val yo = identidadS.value
                    val t = turnoS.value
                    val esperan = piezasS.value.filter { it.estado == EstadoPieza.PENDIENTE && it.venueId != yo.venueIdDeLaSesion }
                        .mapNotNull { p -> yo.sucursales.firstOrNull { it.venueId == p.venueId }?.nombre }.distinct()
                    InicioScreen(
                        DatosDeInicio(
                            nombre = yo.nombre, pendientes = piezasS.value.size, necesitaPin = yo.necesitaPin, esperanSucursal = esperan,
                            turnoAbierto = t != null && t.abierto && t.staffId == yo.staffId, gps = gpsS.value,
                        ),
                        AccionesDeInicio(
                            vender = { nav.navigate("venta") }, sims = { nav.navigate("sims") }, checar = { nav.navigate("checada") },
                            pendientes = { nav.navigate("pendientes") }, ayuda = { nav.navigate("ayuda") }, entrar = { nav.navigate("pin") },
                        ),
                    )
                }
                composable("venta") { VentaScreen(hiltViewModel(), sinSenal = !conRedS.value, onRegresar = regresar) }
                composable("sims") { MisSimsScreen(hiltViewModel(), onRegresar = regresar) }
                composable("checada") { ChecadaScreen(hiltViewModel(), onRegresar = regresar) }
                composable("pendientes") {
                    PendientesScreen(hiltViewModel(), onRegresar = regresar, onMisSims = {
                        if (identidadS.value.estado == EstadoDelCelular.VINCULADO) nav.navigate("sims")
                    })
                }
                composable("ayuda") { AyudaScreen(hiltViewModel(), onRegresar = regresar) }
            }
        }
    }
}
