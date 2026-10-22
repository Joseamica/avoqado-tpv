package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.util.VenueTimeZone
import org.junit.Test

/**
 * «Ronda 1 · 21:08» a las 3:08 pm de la Ciudad de México (Nexgo N86, QA del 21-oct-2026): el título de cada ronda formateaba
 * la hora con el desfase que manda el servidor («…Z», UTC) y nunca la pasaba a la zona del negocio. Desde `43b8b5c7` (3-ago).
 */
class RondaHoraDelNegocioTest {

    @Test
    fun la_hora_de_la_ronda_sale_en_la_zona_del_negocio_y_no_en_UTC() {
        assertThat(roundLabel("2026-10-21T21:08:16.000Z", 1, VenueTimeZone.MEXICO)).isEqualTo("Ronda 1 · 15:08")
    }

    @Test
    fun un_negocio_en_Cancun_ve_su_propia_hora() {
        assertThat(roundLabel("2026-10-21T21:08:16.000Z", 2, VenueTimeZone.zona("America/Cancun"))).isEqualTo("Ronda 2 · 16:08")
    }

    @Test
    fun una_hora_que_ya_trae_desfase_se_lee_como_instante_y_se_pasa_al_negocio() {
        // Tijuana sigue el horario de EE. UU.: el 21-oct todavía es UTC-7.
        assertThat(roundLabel("2026-10-21T15:08:16-06:00", 3, VenueTimeZone.zona("America/Tijuana"))).isEqualTo("Ronda 3 · 14:08")
    }

    @Test
    fun sin_hora_o_con_una_ilegible_queda_solo_el_numero() {
        assertThat(roundLabel(null, 4, VenueTimeZone.MEXICO)).isEqualTo("Ronda 4")
        assertThat(roundLabel("ayer", 4, VenueTimeZone.MEXICO)).isEqualTo("Ronda 4")
    }
}
