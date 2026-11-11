package com.jaac.avoqado_campo.identidad

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class VerificadorPin(val algoritmo: String, val iteraciones: Int, val sal: String, val hash: String)
/** [seguimientoDeUbicacion]: si la sucursal sigue la ubicación del turno (H-A1); null = SERVIDOR viejo, como siempre. */
data class Sucursal(
    val venueId: String, val nombre: String, val verificadorPin: VerificadorPin?, val seguimientoDeUbicacion: Boolean? = null,
)
data class Sesion(val accessToken: String, val refreshToken: String, val venueId: String) {
    override fun toString() = "Sesion(venueId=$venueId, tokens=***)"
}

enum class EstadoDelCelular { SIN_ACTIVAR, VINCULADO, DESVINCULADO, DESCONOCIDO, DADO_DE_BAJA }

data class Identidad(
    val estado: EstadoDelCelular,
    val staffId: String?,
    val nombre: String?,
    val sucursales: List<Sucursal>,
    val venueIdDeLaSesion: String?,
    /** Decide si la pantalla del PIN se muestra. */
    val bloqueada: Boolean,
    /** La sesión del servidor murió: no se sube nada hasta un `entrar` con señal. */
    val necesitaPin: Boolean,
    val graciaHasta: String?,
)

// Los DTO de red (RespuestaActivar/Entrar/Latido) son de la capa de red; ésta los mapea a estos.
data class DatosDeActivacion(
    val celularId: String, val credencial: String, val staffId: String, val nombre: String,
    val sesion: Sesion, val sucursales: List<Sucursal>,
)
data class DatosDeEntrada(val sesion: Sesion, val sucursales: List<Sucursal>)
data class DatosDeLatido(val estado: String, val graciaHasta: String?, val nombre: String?, val sucursales: List<Sucursal>)

/** El cuerpo de `activar` que espera saber su resultado (B14); lo escribe `RepositorioDeEntrada`, lo borra `borrarTodo`. */
internal const val ACTIVACION_PENDIENTE = "activacion.pendiente"

/** Quién es este celular. Todo vive en el almacén cifrado; cada escritura publica una `Identidad` nueva. */
class QuienSoy(private val almacen: AlmacenSeguro) {
    private val gson = Gson()
    private val candado = Any()
    private fun nueva() = UUID.randomUUID().toString()
    private val tipoSucursales = object : TypeToken<List<Sucursal>>() {}.type

    private val _identidad = MutableStateFlow(leerIdentidad())
    val identidad: StateFlow<Identidad> = _identidad

    val aparatoId: String
        get() = synchronized(candado) {
            almacen.leer(APARATO_ID) ?: UUID.randomUUID().toString().also { almacen.escribir(mapOf(APARATO_ID to it)) }
        }
    /** Cambia con cada activación y con `borrarTodo`: una respuesta tardía de la anterior se descarta con `guardarSi`. */
    val generacion: String
        get() = synchronized(candado) {
            almacen.leer(GENERACION) ?: nueva().also { almacen.escribir(mapOf(GENERACION to it)) }
        }
    val celularId: String? get() = almacen.leer(CELULAR_ID)
    val credencial: String? get() = almacen.leer(CREDENCIAL)
    val staffId: String? get() = almacen.leer(STAFF_ID)
    val nombre: String? get() = almacen.leer(NOMBRE)
    val sucursales: List<Sucursal> get() = leerSucursales()
    val sesion: Sesion? get() = almacen.leer(SESION)?.let { gson.fromJson(it, Sesion::class.java) }
    val estado: EstadoDelCelular get() = leerEstado()
    val graciaHasta: String? get() = almacen.leer(GRACIA)
    val bloqueada: Boolean get() = almacen.leer(BLOQUEADA) == "true"
    val necesitaPin: Boolean get() = almacen.leer(NECESITA_PIN) == "true"
    /** I2: el borrado del celular está en curso; nadie escribe datos de la persona (lo maneja `BorradoDelCelular`). */
    val limpiando: Boolean get() = synchronized(candado) { almacen.leer(LIMPIANDO) == "true" }

    /** Lee [generacion] ANTES de la llamada de red y guarda con esto: si ya cambió, no escribe nada. */
    fun guardarSi(generacion: String, bloque: QuienSoy.() -> Unit): Boolean = synchronized(candado) {
        if (almacen.leer(GENERACION) != generacion) return false
        bloque()
        true
    }

    /** Renovar: escribe sólo si no hubo otra activación Y el refresh guardado sigue siendo el que se gastó. */
    fun guardarTokensSi(generacion: String, refreshUsado: String, access: String, refresh: String, venueId: String): Boolean =
        synchronized(candado) {
            if (almacen.leer(GENERACION) != generacion || sesion?.refreshToken != refreshUsado) return false
            guardarTokens(access, refresh, venueId)
            true
        }

    fun guardarActivacion(d: DatosDeActivacion) = escribir(
        GENERACION to nueva(),
        CELULAR_ID to d.celularId, CREDENCIAL to d.credencial, STAFF_ID to d.staffId, NOMBRE to d.nombre,
        SESION to gson.toJson(d.sesion), SUCURSALES to gson.toJson(d.sucursales),
        ESTADO to EstadoDelCelular.VINCULADO.name, GRACIA to null,
        BLOQUEADA to "false", NECESITA_PIN to "false",
    )

    /** Éxito de `entrar` con señal: la única forma de quitar `necesitaPin`. */
    fun guardarEntrada(d: DatosDeEntrada) = escribir(
        SESION to gson.toJson(d.sesion), SUCURSALES to gson.toJson(d.sucursales),
        ESTADO to EstadoDelCelular.VINCULADO.name, GRACIA to null,
        BLOQUEADA to "false", NECESITA_PIN to "false",
    )

    fun guardarTokens(access: String, refresh: String, venueId: String) =
        escribir(SESION to gson.toJson(Sesion(access, refresh, venueId)))

    fun guardarLatido(d: DatosDeLatido) {
        val e = runCatching { EstadoDelCelular.valueOf(d.estado) }.getOrDefault(EstadoDelCelular.DESCONOCIDO)
        val cambios = mutableListOf<Pair<String, String?>>(ESTADO to e.name, GRACIA to d.graciaHasta)
        if (d.nombre != null) cambios += NOMBRE to d.nombre
        // Desvinculado o dado de baja: el servidor manda [] y no debe borrar lo que ya teníamos.
        if (e == EstadoDelCelular.VINCULADO) cambios += SUCURSALES to gson.toJson(d.sucursales)
        escribir(*cambios.toTypedArray())
    }

    fun marcarDesvinculado(graciaHasta: String?) =
        escribir(ESTADO to EstadoDelCelular.DESVINCULADO.name, GRACIA to graciaHasta)

    fun marcarDesconocido() = escribir(ESTADO to EstadoDelCelular.DESCONOCIDO.name)

    /** 403 `PERSONA_DADA_DE_BAJA` en una ruta de la sucursal; las sucursales guardadas se conservan (R2-1). */
    fun marcarDadoDeBaja() = escribir(ESTADO to EstadoDelCelular.DADO_DE_BAJA.name)

    /** `USA_TU_SESION` de la gracia (R8-2): sólo `DADO_DE_BAJA` vuelve a `VINCULADO` (I1 lo garantiza también en `escribir`). */
    fun volverDeLaBaja(): Boolean = synchronized(candado) {
        if (leerEstado() != EstadoDelCelular.DADO_DE_BAJA) return false
        escribir(ESTADO to EstadoDelCelular.VINCULADO.name, GRACIA to null)
        true
    }

    fun marcarNecesitaPin(v: Boolean) = escribir(NECESITA_PIN to v.toString())

    fun marcarBloqueada(v: Boolean) = escribir(BLOQUEADA to v.toString())

    /** No la toca `borrarTodo`: el borrado la pone al empezar y la quita al terminar. */
    fun marcarLimpiando(v: Boolean) = escribir(LIMPIANDO to if (v) "true" else null)

    /** Primer paso del borrado (R8-1): la marca y una `generacion` nueva en UNA escritura, así toda respuesta en vuelo se descarta. */
    fun empezarLimpieza() = escribir(LIMPIANDO to "true", GENERACION to nueva())

    /** La sesión del servidor murió: `necesitaPin` y `bloqueada` en UNA escritura (R3-2). */
    fun marcarSesionMuerta() = escribir(NECESITA_PIN to "true", BLOQUEADA to "true")

    /** Borra todo, también `aparatoId` (el siguiente que se lea es otro) y la activación pendiente. */
    fun borrarTodo() = escribir(*(TODAS.map { it to null } + (GENERACION to nueva())).toTypedArray())

    private fun escribir(vararg cambios: Pair<String, String?>) {
        synchronized(candado) {
            val m = cambios.toMap()
            if (!permitida(m)) return
            almacen.escribir(m)
            _identidad.value = leerIdentidad()
        }
    }

    /**
     * I1 · el estado no regresa: desde `DESVINCULADO` o `DESCONOCIDO` sólo se sale con `borrarTodo` (deja `SIN_ACTIVAR`), y
     * activar sólo se puede desde `SIN_ACTIVAR`. Una escritura que traiga otro estado (o `DESCONOCIDO` → `DESVINCULADO`) o una
     * sesión se descarta ENTERA: así un `entrar`, un latido, un `renovar` o una activación tardíos no reviven nada.
     */
    private fun permitida(m: Map<String, String?>): Boolean {
        val actual = leerEstado()
        val nuevo = m[ESTADO]
        // Las dos escrituras que estrenan generación: `borrarTodo` (estado → null) siempre; la activación sólo sin activar.
        if (GENERACION in m) return nuevo == null || actual == EstadoDelCelular.SIN_ACTIVAR
        if (actual != EstadoDelCelular.DESVINCULADO && actual != EstadoDelCelular.DESCONOCIDO) return true
        if (SESION in m) return false
        if (nuevo == null) return true
        return nuevo == EstadoDelCelular.DESCONOCIDO.name || nuevo == actual.name
    }

    private fun leerSucursales(): List<Sucursal> =
        almacen.leer(SUCURSALES)?.let { gson.fromJson<List<Sucursal>>(it, tipoSucursales) } ?: emptyList()

    private fun leerEstado(): EstadoDelCelular =
        almacen.leer(ESTADO)?.let { runCatching { EstadoDelCelular.valueOf(it) }.getOrNull() } ?: EstadoDelCelular.SIN_ACTIVAR

    private fun leerIdentidad() = Identidad(
        estado = leerEstado(), staffId = staffId, nombre = nombre, sucursales = leerSucursales(),
        venueIdDeLaSesion = sesion?.venueId, bloqueada = bloqueada, necesitaPin = necesitaPin, graciaHasta = graciaHasta,
    )

    private companion object {
        const val APARATO_ID = "aparatoId"; const val CELULAR_ID = "celularId"; const val CREDENCIAL = "credencial"
        const val STAFF_ID = "staffId"; const val NOMBRE = "nombre"; const val SUCURSALES = "sucursales"
        const val SESION = "sesion"; const val ESTADO = "estado"; const val GRACIA = "graciaHasta"
        const val GENERACION = "generacion"
        const val BLOQUEADA = "bloqueada"; const val NECESITA_PIN = "necesitaPin"
        const val LIMPIANDO = "limpiando"
        val TODAS = CLAVES_DEL_PIN + listOf(ACTIVACION_PENDIENTE, APARATO_ID, CELULAR_ID, CREDENCIAL, STAFF_ID, NOMBRE, SUCURSALES, SESION, ESTADO, GRACIA, BLOQUEADA, NECESITA_PIN)  // sin GENERACION: borrarTodo la renueva
    }
}
