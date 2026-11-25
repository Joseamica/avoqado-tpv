package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.IntentoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Bloque 4-L · L-d1: los textos, sin traducir y sin datos de la tarjeta ni de la identidad. */
class PantallaTest {
    private val folio = AttemptId.nuevo()
    private val base = IntentoEntity(folio.valor, Operacion.VENTA, VENUE, 2_537, false, "1", "S1", Ambiente.SANDBOX, "x", Origen.LOCAL_SIN_SOLICITUD)

    /** Centinelas en todo lo que NO debe salir: bin, últimos 4, país, id del pago, huellas, KSN, series, posId y comercio. */
    @Test fun `ni la pantalla ni el historial llevan datos de la tarjeta ni de la identidad`() {
        val secretos = listOf("987654", "4321", "0999", "PAGO-SECRETO", "HUELLA-T", "HUELLA-L", "FFFF9876543210E00000", "SERIEX77", "FISICAX77", "770077", "COMERCIO-X")
        val identidad = Identidad("COMERCIO-X", "770077", "FISICAX77", "SERIEX77", "A910S", "x", Ambiente.SANDBOX)
        val fila = base.copy(
            posId = "770077", serieBlumon = "SERIEX77", estado = EstadoDelIntento.APROBADO, operationNumber = 1, autorizacion = "A1",
            paymentId = "PAGO-SECRETO", marca = Marca.VISA, medio = Medio.CELULAR, bin = "987654", ultimos4 = "4321",
            paisEmisor = "0999", huellaTarjeta = "HUELLA-T", huellaLectura = "HUELLA-L", ksnBase = "FFFF9876543210E00000", enviadoEnMs = 1,
        )
        val textos = listOf(
            textoDelEvento(EventoDeCobro.Iniciado(folio, identidad, Operacion.VENTA, 100)),
            textoDelEvento(EventoDeCobro.Aprobado(1, "A1", "PAGO-SECRETO", Marca.VISA, Medio.CELULAR, "987654", "4321", "0999")),
            textoDeLaFila(LecturaDeFila.Fila(fila)),
            IdentidadTecleada("SERIEX77", "770077", "FISICAX77").toString(),
        )
        for (t in textos) for (s in secretos) assertFalse("«$s» en «$t»", s in t)
    }

    /** Sin libreta, sin fila e ilegible se dicen distinto; ninguno dice «no se cobró». `enviadoEnMs` es la marca de la libreta (C11). */
    @Test fun `sin fila guardada ilegible y fila con estado se dicen distinto`() {
        assertEquals("la libreta se lee cuando el motor esté armado", textoDeLaFila(LecturaDeFila.SinLibreta))
        assertEquals("sin fila guardada por el motor", textoDeLaFila(LecturaDeFila.SinFila))
        assertEquals("fila ILEGIBLE (FilaIlegible): por confirmar", textoDeLaFila(LecturaDeFila.Ilegible("FilaIlegible")))
        assertEquals("fila ANOTADO · reverso NINGUNO · reporte NO_APLICA · sin envío anotado", textoDeLaFila(LecturaDeFila.Fila(base)))
        assertEquals(
            "fila NO_SALIO (INTERRUMPIDO) · reverso NINGUNO · reporte NO_APLICA · envío anotado en la libreta",
            textoDeLaFila(LecturaDeFila.Fila(base.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO, enviadoEnMs = 5))),
        )
    }

    @Test fun `el importe se lee en pesos y solo hasta cien`() {
        assertEquals(2_537L, centavosDe(" 25.37 "))
        assertEquals(10_000L, centavosDe("100.00"))
        assertEquals(1L, centavosDe("0.01"))
        for (malo in listOf("", "0", "100.01", "25,37", "-5", "25.375", "abc")) assertNull(malo, centavosDe(malo))
        assertEquals("\$25.30", pesos(2_530))
    }

    /** Un solo decimal son decenas de centavos: «10.5» es $10.50, nunca $10.05. */
    @Test fun `un decimal es de decenas de centavos`() {
        assertEquals(1_050L, centavosDe("10.5"))
        assertEquals(50L, centavosDe("0.5"))
    }

    /** Cada cierre se dice TAL CUAL y distinto (Ruling 8): el reposo de las tandas exige «lector cerrado», y sólo ACREDITADO lo dice. */
    @Test fun `el informe dice su cierre tal cual`() {
        fun texto(causa: Causa, cierre: Cierre, fallas: List<String> = emptyList(), error: String? = null) =
            textoDelInforme(Informe(folio, causa, null, null, null, null, null, emptySet(), cierre, fallas, error))
        assertEquals("lector: LEIDA · este intento no abrió el lector", texto(Causa.LEIDA, Cierre.NO_SE_ABRIO))
        assertEquals("lector: LEIDA · lector cerrado", texto(Causa.LEIDA, Cierre.ACREDITADO))
        assertEquals(
            "lector: LEIDA · cierre del lector sin acreditar: PICC, KERNEL",
            texto(Causa.LEIDA, Cierre.SIN_ACREDITAR, listOf("PICC", "KERNEL")),
        )
        assertEquals(
            "lector: ERROR · este intento no abrió el lector · error IllegalStateException",
            texto(Causa.ERROR, Cierre.NO_SE_ABRIO, error = "IllegalStateException"),
        )
    }
}
