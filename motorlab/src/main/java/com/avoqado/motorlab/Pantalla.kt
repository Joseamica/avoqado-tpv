package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.libreta.IntentoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import java.util.Locale

/** Cuándo se pinta: en cada cambio del carril o de los folios, y en cada latido. La cabeza lee la red AL PINTAR (T5 de L-d2). */
internal fun momentosDePintar(situacion: Flow<*>, folios: Flow<*>, latido: Flow<Unit>): Flow<Unit> =
    merge(combine(situacion, folios) { _, _ -> }, latido)

internal fun pesos(centavos: Long): String = String.format(Locale.ROOT, "\$%d.%02d", centavos / 100, centavos % 100)

/** «25.37» → 2537, sólo de $0.01 a $100.00 (Ruling 11); si no, null. */
internal fun centavosDe(texto: String): Long? {
    val m = Regex("^([0-9]{1,3})(?:\\.([0-9]{1,2}))?$").matchEntire(texto.trim()) ?: return null
    return (m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()).takeIf { it in 1..10_000 }
}

/** Cada evento TAL CUAL, campo por campo (su `toString` trae la tarjeta, C10). */
internal fun textoDelEvento(e: EventoDeCobro): String = when (e) {
    is EventoDeCobro.Iniciado -> "Iniciado ${pesos(e.importeCentavos)}"
    EventoDeCobro.PideTarjeta -> "PideTarjeta"
    EventoDeCobro.Autorizando -> "Autorizando"
    EventoDeCobro.RevisandoConElBanco -> "RevisandoConElBanco"
    is EventoDeCobro.Aprobado ->
        "Aprobado · operación ${e.operationNumber ?: "—"} · autorización ${e.autorizacion ?: "—"} · ${e.marca ?: "—"} · ${e.medio ?: "—"}"
    is EventoDeCobro.Cancelada -> "Cancelada · operación ${e.operationNumber}"
    is EventoDeCobro.Rechazado -> "Rechazado · ${e.codigo} · ${e.leyenda}"
    is EventoDeCobro.NoSalioAlBanco -> "NoSalioAlBanco · ${e.motivo}" + (e.admision?.let { " · $it" } ?: "")
    EventoDeCobro.NoSeCobro -> "NoSeCobro"
    is EventoDeCobro.PorConfirmar -> "PorConfirmar ${pesos(e.importeCentavos)}"
    EventoDeCobro.ContratoDistinto -> "ContratoDistinto"
}

/** El informe de L-c TAL CUAL: la app no afirma nada del cierre del lector (Ruling 8). */
internal fun textoDelInforme(i: Informe): String = buildString {
    append("lector: ${i.causa}")
    i.codigo?.let { append(" · código $it") }
    i.marca?.let { append(" · $it") }
    i.importeDelKernel?.let { append(" · 9F02 $it") }
    append(
        when (i.cierre) {
            Cierre.NO_SE_ABRIO -> " · este intento no abrió el lector"
            Cierre.ACREDITADO -> " · lector cerrado"
            Cierre.SIN_ACREDITAR -> " · cierre del lector sin acreditar: " + i.fallasDelCierre.joinToString()
        },
    )
    i.error?.let { append(" · error $it") }
}

/** Lo que dio leer la libreta para un folio. `Ilegible` = `buscar` lanzó: por confirmar, nunca un desenlace. */
internal sealed interface LecturaDeFila {
    data object SinLibreta : LecturaDeFila
    data object SinFila : LecturaDeFila
    data class Ilegible(val clase: String) : LecturaDeFila
    data class Fila(val f: IntentoEntity) : LecturaDeFila
}

internal fun textoDeLaFila(l: LecturaDeFila): String = when (l) {
    LecturaDeFila.SinLibreta -> "la libreta se lee cuando el motor esté armado"
    LecturaDeFila.SinFila -> "sin fila guardada por el motor"
    is LecturaDeFila.Ilegible -> "fila ILEGIBLE (${l.clase}): por confirmar"
    is LecturaDeFila.Fila -> with(l.f) {
        buildString {
            append("fila $estado")
            motivoNoSalio?.let { append(" ($it)") }
            append(" · reverso $reverso")
            if (pudoEntrar) append(" (pudo entrar)")
            ultimoCodigoReverso?.let { append(" $it") }
            append(" · reporte $reporte")
            resultadoParaReportar?.let { append(" $it") }
            operationNumber?.let { append(" · operación $it") }
            autorizacion?.let { append(" · autorización $it") }
            evidenciaTardiaOperacion?.let { append(" · evidencia tardía: operación $it") }
            evidenciaTardiaAutorizacion?.let { append(" autorización $it") }
            parcialOperacion?.let { append(" · parcial: operación $it") }
            parcialImporteCentavos?.let { append(" ${pesos(it)}") }
            codigo?.let { append(" · código $it") }
            leyenda?.let { append(" · $it") }
            marca?.let { append(" · $it") }
            medio?.let { append(" $it") }
            append(if (enviadoEnMs != null) " · envío anotado en la libreta" else " · sin envío anotado")
        }
    }
}
