package com.jaac.avoqado_campo.red

import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.DatosDeEntrada
import com.jaac.avoqado_campo.identidad.DatosDeLatido
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.identidad.VerificadorPin

// ── Cuerpos que manda el celular. Gson omite los null: el servidor valida con `.strict()`. ──

/** Los textos van recortados a 60 (el servidor rechaza más). Créalo con [Aparato.de]. */
data class Aparato(val aparatoId: String, val marca: String?, val modelo: String?, val versionAndroid: String?, val versionApp: String?) {
    companion object {
        fun de(aparatoId: String, marca: String?, modelo: String?, versionAndroid: String?, versionApp: String?) =
            Aparato(aparatoId, marca?.take(60), modelo?.take(60), versionAndroid?.take(60), versionApp?.take(60))
    }
}
data class AparatoSinId(val marca: String?, val modelo: String?, val versionAndroid: String?, val versionApp: String?) {
    companion object {
        fun de(marca: String?, modelo: String?, versionAndroid: String?, versionApp: String?) =
            AparatoSinId(marca?.take(60), modelo?.take(60), versionAndroid?.take(60), versionApp?.take(60))
    }
}
data class ActivarBody(val codigo: String, val pin: String, val aparato: Aparato) {
    override fun toString() = "ActivarBody(codigo=***, pin=***, aparato=$aparato)"
}
data class EntrarBody(val pin: String, val venueId: String? = null) {
    override fun toString() = "EntrarBody(pin=***, venueId=$venueId)"
}
data class RenovarBody(val refreshToken: String) {
    override fun toString() = "RenovarBody(***)"
}
data class PorSubir(val total: Int, val ventas: Int? = null, val fotos: Int? = null, val checadas: Int? = null, val custodia: Int? = null)
data class LatidoBody(val porSubir: PorSubir, val huellas: List<String>, val aparato: AparatoSinId? = null)
data class DesvincularBody(val porSubir: Int)
data class GraciaBody(val tipo: String, val venueId: String, val cuerpoJson: String)
data class PingBody(
    val latitude: Double, val longitude: Double, val accuracy: Double?, val capturedAt: String, val source: String = "PERIODIC",
)

// ── Respuestas (Gson: todo nulable). Una respuesta 2xx que no pasa `valida()` es `Llamada.Ilegible` (R2-6). ──

interface Validable { fun valida(): Boolean }

private val HEX64 = Regex("^[a-f0-9]{64}$")
private fun String?.esHex64() = this != null && HEX64.matches(this)

data class Persona(val staffId: String?, val nombre: String?)
data class VerificadorPinDto(val algoritmo: String?, val iteraciones: Int?, val sal: String?, val hash: String?)
data class SucursalDto(
    val venueId: String?, val nombre: String?, val verificadorPin: VerificadorPinDto?, val seguimientoDeUbicacion: Boolean? = null,
)
data class SesionDto(val accessToken: String?, val refreshToken: String?, val venueId: String?, val expiraEn: Long?) {
    fun valida() = !accessToken.isNullOrBlank() && refreshToken.esHex64() && !venueId.isNullOrBlank()
    override fun toString() = "SesionDto(venueId=$venueId, tokens=***)"
}

private fun List<SucursalDto?>?.validas() = this != null && all { it?.venueId != null && it.nombre != null }

data class RespuestaActivar(
    val celularId: String?, val credencial: String?, val persona: Persona?, val sesion: SesionDto?,
    val sucursales: List<SucursalDto?>?, val horaDelServidor: String?, val repetida: Boolean?,
) : Validable {
    override fun valida() = celularId != null && credencial.esHex64() && persona?.staffId != null &&
        sesion?.valida() == true && sucursales.validas() && horaDelServidor != null
    override fun toString() = "RespuestaActivar(celularId=$celularId, credencial=***, sesion=$sesion)"
}

data class RespuestaEntrar(val sesion: SesionDto?, val sucursales: List<SucursalDto?>?, val horaDelServidor: String?) : Validable {
    // Las sucursales también: `guardarEntrada` las reemplaza y una lista nula las borraría.
    override fun valida() = sesion?.valida() == true && sucursales.validas() && horaDelServidor != null
}

data class RespuestaRenovar(val accessToken: String?, val refreshToken: String?, val venueId: String?) : Validable {
    override fun valida() = !accessToken.isNullOrBlank() && refreshToken.esHex64() && !venueId.isNullOrBlank()
    override fun toString() = "RespuestaRenovar(venueId=$venueId, tokens=***)"
}

data class RespuestaLatido(
    val estado: String?, val graciaHasta: String?, val persona: Persona?, val sucursales: List<SucursalDto?>?,
    val horaDelServidor: String?,
) : Validable {
    override fun valida() = estado in ESTADOS && horaDelServidor != null &&
        (estado != "VINCULADO" || sucursales.validas()) // vinculado reemplaza las sucursales
    private companion object { val ESTADOS = setOf("VINCULADO", "DESVINCULADO", "DADO_DE_BAJA") }
}

data class CategoriaDto(val id: String?, val name: String?, val suggestedPrice: String?)
data class MiSimDto(
    val id: String?, val serialNumber: String?, val custodyState: String?, val custodyVersion: Int?, val category: CategoriaDto?,
    val verificationStatus: String?, val verificationId: String?, val paymentId: String?,
    val rejectionReasons: List<String>?, val reviewNotes: String?,
)
data class MisSimsDto(val items: List<MiSimDto?>?) : Validable {
    override fun valida() = items != null && items.all { it?.serialNumber != null && it.custodyState != null }
}

// `GET tpv/modules`: copia de `VenueModuleDto.kt` (app), sólo lo que lee el celular.
data class EtiquetasDto(val item: String?, val barcode: String?, val category: String?, val scan: String?, val register: String?)
data class FuncionesDto(
    val allowUnregisteredSale: Boolean?, val requireCategorySelection: Boolean?, val showStockCounts: Boolean?,
    val enablePortabilidad: Boolean?, val linkingNumber: Boolean?,
)
data class AsistenciaDto(
    val requireClockInPhoto: Boolean?, val requireClockInGps: Boolean?, val requireClockOutPhoto: Boolean?, val requireClockOutGps: Boolean?,
)
data class ConfigModuloDto(val labels: EtiquetasDto?, val features: FuncionesDto?, val attendance: AsistenciaDto?)
data class ModuloDto(val code: String?, val config: ConfigModuloDto?)
data class RespuestaModulos(val modules: List<ModuloDto?>?) : Validable {
    override fun valida() = modules != null
}

// `GET tpv/check-update?platform=ANDROID_CAMPO`: `fileSize` llega como texto (BigInt del SERVIDOR).
data class ActualizacionDto(
    val versionCode: Int?, val versionName: String?, val downloadUrl: String?, val fileSize: String?, val checksum: String?,
    val releaseNotes: String?,
)
data class RespuestaCheckUpdate(val hasUpdate: Boolean?, val minVersionCode: Int?, val update: ActualizacionDto?) : Validable {
    // Sin `checksum` sigue siendo legible: el Comprobador la rechaza (sha vacío ⇒ Mala), no se confunde con «sin red».
    override fun valida() = hasUpdate == false ||
        (hasUpdate == true && update?.versionCode != null && update.versionName != null && !update.downloadUrl.isNullOrBlank())
}

// ── De la red a «Quién soy». Sólo se llaman sobre respuestas que pasaron `valida()`. ──

private fun SesionDto.aSesion() = Sesion(accessToken!!, refreshToken!!, venueId!!)
private fun VerificadorPinDto.aVerificador(): VerificadorPin? =
    if (algoritmo != null && iteraciones != null && sal != null && hash != null) VerificadorPin(algoritmo, iteraciones, sal, hash) else null
private fun List<SucursalDto?>.aSucursales() =
    filterNotNull().map { Sucursal(it.venueId!!, it.nombre!!, it.verificadorPin?.aVerificador(), it.seguimientoDeUbicacion) }

fun RespuestaActivar.aDatos() = DatosDeActivacion(
    celularId!!, credencial!!, persona!!.staffId!!, persona.nombre.orEmpty(), sesion!!.aSesion(), sucursales!!.aSucursales(),
)
fun RespuestaEntrar.aDatos() = DatosDeEntrada(sesion!!.aSesion(), sucursales!!.aSucursales())
fun RespuestaLatido.aDatos() = DatosDeLatido(estado!!, graciaHasta, persona?.nombre, sucursales?.aSucursales().orEmpty())
