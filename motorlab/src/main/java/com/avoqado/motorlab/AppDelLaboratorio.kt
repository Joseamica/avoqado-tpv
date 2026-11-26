package com.avoqado.motorlab

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.SaludDeLaLibreta
import com.avoqado.cobropropio.motor.MotorDeCobro
import com.avoqado.cobropropio.motor.ResultadoDelCorte
import com.avoqado.cobropropio.motor.crearMotorDeCobro
import com.avoqado.cobropropio.servidor.CredencialDelServidor
import com.example.clean_lib_services.shared_tools.constants.ApiConstants
import com.pax.dal.entity.ETermInfoKey
import com.pax.neptunelite.api.NeptuneLiteUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.Executor
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * El dueño de todo en el proceso de la app de laboratorio (bloque 4-L · L-d1): el lector, el respondedor local y el laboratorio,
 * cuyo carril es el hilo de la pantalla. Nunca habla con un SERVIDOR de Avoqado: el motor le pregunta al respondedor de 127.0.0.1.
 */
class AppDelLaboratorio : Application() {
    internal lateinit var laboratorio: Laboratorio
        private set
    private lateinit var respondedor: RespondedorLocal
    private lateinit var red: RedDelLaboratorio

    /** La serie medida al arrancar (bloque P0): «midiendo…» hasta que su hilo termina. */
    @Volatile private var serie: String = "Serie del aparato: midiendo…"
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val archivo = ArchivoDeIdentidad(noBackupFilesDir)
        // Bloque P0: la serie desde el arranque en frío, ANTES del lector y de todo `AppManager.init` (que carga los parámetros
        // EMV; el DAL no los necesita). En su propio hilo y cada vía con plazo: un DAL colgado no detiene la pantalla ni la vía
        // `Build`. El DAL directo, no `Sdk.getDal()`, que imprime la traza al fallar (`Sdk.java:40-47`): aquí sólo sale la clase.
        // Sin `READ_PHONE_STATE`: si `Build` no puede, eso es el dato.
        thread(isDaemon = true, name = "motorlab-serie") {
            serie = lineaDeLaSerie(
                listOf(
                    "DAL" to conPlazo(PLAZO_DE_SERIE_MS) {
                        NeptuneLiteUser.getInstance().getDal(this)?.sys?.termInfo?.get(ETermInfoKey.SN)
                    },
                    "Build" to conPlazo(PLAZO_DE_SERIE_MS) { Build.getSerial() },
                ),
                archivo.leer().getOrNull()?.serieFisica,
            )
        }
        respondedor = RespondedorLocal(ahora = SystemClock::elapsedRealtime) // el reloj de las notas de los folios
        red = RedDelLaboratorio(this)
        val lector = LectorPax(PaxDeLaTerminal(this), red)
        val principal = Handler(Looper.getMainLooper())
        laboratorio = Laboratorio(
            historial = HistorialLocal(File(noBackupFilesDir, "historial")),
            lector = lector,
            archivo = archivo,
            fabrica = { identidad ->
                val motor = crearMotorDeCobro(
                    context = this, identidad = identidad, lector = lector, red = red,
                    credencialBlumon = CredencialDelCliente(ApiConstants.USER_NAME_CORE, ApiConstants.USER_PASSWORD_CORE),
                    urlDelServidor = "http://127.0.0.1:${respondedor.puerto}/", credencialDelServidor = SinServidorDeAvoqado,
                    pinDelServidor = null, llaveDeHuellas = llaveDeHuellas(noBackupFilesDir),
                )
                MotorDelProceso(motor) to FilasDeLaLibreta(Libreta.abrir(this))
            },
            lecturaSeca = { leerEnSeco(lector) },
            alcance = alcance,
            carril = Executor { principal.post(it) },
            ahora = SystemClock::elapsedRealtime,
        )
        laboratorio.arrancar()
    }

    /** Si el respondedor está mudo: el botón «Servidor mudo» lo lee al crearse la pantalla. */
    internal val mudo: Boolean get() = respondedor.mudo

    /** El botón «Servidor mudo» (bloque P0): lo prende o lo apaga y da el estado nuevo. */
    internal fun alternarMudo(): Boolean = (!respondedor.mudo).also { respondedor.mudo = it }

    /**
     * Lo del proceso para la cabeza de la pantalla: con el mudo, «(mudo)» y la retención en ms del mismo reloj de los folios; y
     * debajo, la serie medida al arrancar.
     */
    internal fun cabecera(): String =
        "Proceso ${Process.myPid()} · red: ${if (red.hayRed()) "sí" else "no"} · Respondedor 127.0.0.1: ${respondedor.resumen()}" +
            (if (respondedor.mudo) " (mudo)" else "") + (respondedor.retencion()?.let { " · $it" } ?: "") + "\n" + serie
}

private const val IMPORTE = 2_550L // $25.50: distinto de $1 y dentro del tope de la tarjeta del laboratorio ($100)
private const val ESPERA = 30_000L // la misma espera de lectura que el motor
private const val PLAZO_DE_SERIE_MS = 3_000L // por vía: lo que pase de esto sale «ilegible (SinRespuesta)»

/** La lectura seca de L-c, tal cual: no cobra; sólo lo permitido del informe. */
private suspend fun leerEnSeco(lector: LectorPax): String {
    val folio = AttemptId.nuevo()
    if (!lector.siguienteFolio(folio)) return "El lector tiene otro dueño: espera a que termine."
    val resultado = lector.leer(Operacion.VENTA, IMPORTE, ESPERA)
    return describir(resultado, lector.informes.value?.takeIf { it.folio == folio })
}

/** Sólo lo permitido: el resultado, la causa, lo que dijo el kernel, el 9F02, QUÉ tags vinieron y cómo quedó el lector. */
internal fun describir(r: ResultadoDeLectura, i: Informe?): String = buildString {
    appendLine("Lectura seca de \$25.50 (no cobra)")
    appendLine(
        "Resultado: " + when (r) {
            is ResultadoDeLectura.Leida -> "Leída"
            is ResultadoDeLectura.Cortada -> "Cortada (código ${r.codigoKernel})"
            ResultadoDeLectura.Vencida -> "Vencida"
            ResultadoDeLectura.Cancelada -> "Cancelada"
        },
    )
    if (i == null) {
        append("Sin informe del lector")
        return@buildString
    }
    appendLine("Causa: ${i.causa} · código: ${i.codigo ?: "—"}")
    // Sin entrada (nadie acercó la tarjeta) no se menciona, como en los folios (`textoDelInforme`).
    appendLine(
        "Marca: ${i.marca ?: "—"}" + (i.entrada?.let { " · Entrada: $it" } ?: "") +
            " · kernel: ${i.resultado ?: "—"} · CVM: ${i.cvm ?: "—"}",
    )
    appendLine("9F02: ${i.importeDelKernel ?: "—"} (pedido $IMPORTE): " + if (i.importeDelKernel == IMPORTE) "coincide" else "NO coincide")
    appendLine("Tags que vinieron: " + i.tagsPresentes.sorted().joinToString(" ") { "%X".format(it) })
    appendLine(
        when (i.cierre) {
            Cierre.NO_SE_ABRIO -> "Este intento no abrió el lector"
            Cierre.ACREDITADO -> "Lector cerrado"
            Cierre.SIN_ACREDITAR -> "Cierre del lector sin acreditar: " + i.fallasDelCierre.joinToString()
        },
    )
    i.error?.let { appendLine("Error: $it") }
}

private class MotorDelProceso(private val m: MotorDeCobro) : Motor {
    override fun cobrar(s: SolicitudDeVenta): Flow<EventoDeCobro> = m.cobrar(s)
    override suspend fun cortar(s: SolicitudDeVenta): ResultadoDelCorte = m.cortar(s)
    override suspend fun recuperar(): List<AttemptId> = m.recuperar()
    override val resolucionesTardias: SharedFlow<Pair<AttemptId, EventoDeCobro>> get() = m.resolucionesTardias
}

/** La libreta del motor, sólo para leer: `Libreta.abrir` da LA MISMA del proceso (C3). */
private class FilasDeLaLibreta(private val l: Libreta) : Filas {
    override fun buscar(folio: AttemptId): IntentoEntity? = l.buscar(folio)
    override fun salud(): SaludDeLaLibreta = l.salud()
}

/** El laboratorio no tiene SERVIDOR (Ruling 4). */
private object SinServidorDeAvoqado : CredencialDelServidor {
    override fun vigente(): String = "laboratorio"
    override suspend fun renovar(rechazado: String, espera: Duration): String? = null
}
