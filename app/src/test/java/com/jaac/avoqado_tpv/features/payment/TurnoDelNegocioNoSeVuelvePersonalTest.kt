package com.jaac.avoqado_tpv.features.payment

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El turno de caja (`Shift`) es del NEGOCIO, no de la persona (decisión del founder, 2-sep-2026:
 * plan `2026-09-03-turno-de-caja-plan-b-fases-2-y-3`). El servidor ya no resuelve "el turno abierto"
 * filtrando por `staffId` — `getCurrentShift` responde por VENUE (ver `turnoDeCaja.guard.test.ts` en
 * avoqado-server) — y la PAX tampoco compara `shift.staffId` contra quien está cobrando antes de
 * dejarlo cobrar. Nunca lo hizo (verificado: cero comparaciones de esa forma en todo
 * `features/payment/`, en los tres source sets, antes de escribir esta prueba).
 *
 * Esta prueba es ESTÁTICA a propósito, igual que su hermana del servidor: no ejercita el flujo de
 * cobro (regla del repo: 8 features comparten `PaymentViewModel`/`PaymentScreen`, y esta tarea no
 * lo toca), comprueba que NADIE vuelva a introducir esa comparación. Es lo que impide que alguien
 * "arregle" el turno del negocio volviéndolo personal desde la PAX — un test de comportamiento no
 * cazaría ese tipo de regresión sin ejercitar cada camino de cobro uno por uno.
 *
 * 🔴 Es un TRIPWIRE, no una demostración (mismo límite que el guard del servidor): caza el patrón
 * textual `algo.staffId <comparación> <fuente del usuario actual>` (en cualquier orden). No sigue
 * variables intermedias (`val x = shift; x.staffId == …` lo evade) ni entiende el AST. Cerrar esos
 * huecos pediría un parser; mientras tanto, la verdad de verdad la fija el comportamiento probado
 * en el servidor (`turnoDeCaja.guard.test.ts` y sus hermanas de comportamiento).
 */
class TurnoDelNegocioNoSeVuelvePersonalTest {

    /** Los tres source sets donde vive código real de `features/payment/` en este repo. */
    private val raices = listOf(
        File("src/main/java/com/jaac/avoqado_tpv/features/payment"),
        File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment"),
        File("src/production/java/com/jaac/avoqado_tpv/features/payment"),
    )

    private fun archivosKotlin(raiz: File): List<File> =
        raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Quita comentarios ANTES de buscar, mismo motivo que en el guard del servidor: un comentario
     * que sólo DESCRIBE el patrón prohibido no debe disparar la prueba — sólo código real.
     */
    private fun sinComentarios(codigo: String): String {
        val sinBloques = codigo.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return sinBloques.lineSequence().joinToString("\n") { linea ->
            val corte = Regex("""(?<!:)//""").find(linea)?.range?.first
            if (corte == null) linea else linea.substring(0, corte)
        }
    }

    @Test
    fun `P1 el guard apunta a rutas reales con archivos kt`() {
        raices.forEach { raiz ->
            assertTrue("No existe $raiz — la ruta del guard está mal y no vigila nada.", raiz.exists())
            assertTrue(
                "$raiz no tiene ningún archivo .kt — el guard no está vigilando nada de verdad.",
                archivosKotlin(raiz).isNotEmpty(),
            )
        }
    }

    @Test
    fun `P1 ningun archivo de features slash payment compara shift staffId contra el usuario actual`() {
        val violaciones = raices.flatMap { raiz ->
            archivosKotlin(raiz).mapNotNull { archivo ->
                val codigo = sinComentarios(archivo.readText())
                val lineasHostiles = codigo.lineSequence()
                    .filter { PATRON_COMPARACION_PERSONAL.containsMatchIn(it) }
                    .map { it.trim() }
                    .toList()
                if (lineasHostiles.isEmpty()) null else "${archivo.path} -> ${lineasHostiles.joinToString(" | ")}"
            }
        }

        assertTrue(
            "El turno es del NEGOCIO, no de la persona (decisión del founder, 2-sep-2026). " +
                "El servidor ya no resuelve el turno abierto filtrando por staffId, y la PAX no debe " +
                "empezar a hacerlo comparando shift.staffId contra quien está cobrando. " +
                "Archivos con la comparación prohibida:\n${violaciones.joinToString("\n")}",
            violaciones.isEmpty(),
        )
    }

    // ---- El patrón se prueba contra fixtures de texto, SIN tocar ningún archivo de producción ----

    @Test
    fun `P1 el patron SI detecta una comparacion hostil de staffId contra el usuario actual`() {
        val hostiles = listOf(
            "val puedeCobrar = shift.staffId == authRepository.getStaffId()",
            "if (currentShift?.staffId != authContext.userId) { bloquear() }",
            "val autorizado = getAuthContext().userId == currentShift.staffId",
            "return currentStaffId == shift.staffId",
        )
        hostiles.forEach { linea ->
            assertTrue(
                "El patrón debería detectar esta comparación hostil de ejemplo: $linea",
                PATRON_COMPARACION_PERSONAL.containsMatchIn(linea),
            )
        }
    }

    @Test
    fun `P1 el patron NO dispara con usos inocuos de staffId ya presentes en el repo`() {
        val inocuos = listOf(
            // Atribución (quién cobró/reembolsó), no autorización — patrón real y abundante hoy.
            "OrderPaymentRequest(staffId = context.staffId)",
            "Timber.d(\"valor: \" + shift.staffId)",
            "val nombre = shift.staffName",
            "if (staffId == null) return",
            "val staffId = if (!currentStaffId.isNullOrBlank()) currentStaffId else base.staffId",
            "val staffId = cachedStaffId ?: authRepository.getStaffId() ?: secureStorage.getStaffId()",
        )
        inocuos.forEach { linea ->
            assertFalse(
                "El patrón NO debería dispararse con este uso inocuo (atribución, no autorización): $linea",
                PATRON_COMPARACION_PERSONAL.containsMatchIn(linea),
            )
        }
    }

    companion object {
        /** Identificadores que representan "quién está usando el aparato ahora mismo". */
        private const val FUENTE_USUARIO_ACTUAL =
            """(authContext|getAuthContext|authRepository|getStaffId|secureStorage|currentStaffId|loggedInStaffId|sessionStaffId|currentUser)"""

        /**
         * Dos formas de la misma comparación, en cualquier orden:
         *   1. `algo.staffId <op> ...fuente`   (el campo del turno primero)
         *   2. `fuente... <op> ...algo.staffId` (la fuente del usuario primero)
         * `<op>` es `==` o `!=`. Acotado a la MISMA línea (`[^\n;]*`, sin cruzar `;` ni saltos).
         */
        val PATRON_COMPARACION_PERSONAL = Regex(
            """\.staffId\s*[!=]=\s*[^\n;]*$FUENTE_USUARIO_ACTUAL""" +
                """|$FUENTE_USUARIO_ACTUAL[^\n;]*[!=]=\s*[^\n;]*\.staffId\b""",
        )
    }
}
