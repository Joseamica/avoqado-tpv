package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import com.jaac.avoqado_tpv.features.shift.domain.ShiftStatus
import com.jaac.avoqado_tpv.features.shift.presentation.CachedShiftInfo

/**
 * Prioridad de la franja superior del Home (WelcomeScreen). Solo se muestra UNA a la vez.
 *
 * El orden importa: sin conexión gana siempre porque explica por qué todo lo demás está
 * fallando — mientras no hay conexión, tanto el estado del venue como el del turno pueden
 * estar desactualizados (son la última copia que se pudo confirmar con el servidor). Un
 * banner tapando a otro deja al cajero adivinando cuál es el problema real.
 */
enum class BannerPriority { OFFLINE, VENUE_SUSPENDED, SHIFT_CLOSED }

/** Función pura para poder testear la prioridad sin levantar Compose. */
fun resolveVisibleBanner(
    offline: Boolean,
    venueSuspended: Boolean,
    shiftClosed: Boolean,
): BannerPriority? = when {
    offline -> BannerPriority.OFFLINE
    venueSuspended -> BannerPriority.VENUE_SUSPENDED
    shiftClosed -> BannerPriority.SHIFT_CLOSED
    else -> null
}

/**
 * Pure decision: does [VenueStatusBanner] win the slot? (Fix round 1.)
 *
 * Normally this just mirrors [resolveVisibleBanner] returning [BannerPriority.VENUE_SUSPENDED].
 * But when there is no shift banner in this screen's config (`showShiftBanner == false` —
 * shifts disabled or simplified/serialized-inventory mode), there is nothing in this slot for
 * [VenueStatusBanner] to overlap with, so a non-`ACTIVE` venue status must keep showing
 * regardless of [priority] — exactly like it did unconditionally before [ConnectionBannerHost]
 * existed.
 *
 * **The bug this fixes:** without the second clause, `offline=true` + `venueSuspended=true` +
 * `showShiftBanner=false` resolved `priority = OFFLINE` (not `VENUE_SUSPENDED`), so the first
 * `when` branch was false — and with no shift banner to fall back to either, NOTHING rendered.
 * A real, silent regression: [VenueStatusBanner] never depended on `showShiftBanner` before this
 * host existed. It hits hardest exactly where it matters most: `showShiftBanner=false` is the
 * PlayTelecom BAE (serialized-inventory / `isSimplifiedMode`) configuration — terminals on flaky
 * in-store WiFi that go offline constantly, which is precisely when venue status (suspended,
 * trial, etc.) is most likely to also be true and most needs to stay visible.
 *
 * **`LIVE_DEMO` is deliberately NOT special-cased.** It follows the exact same rule as every
 * other non-`ACTIVE` [VenueStatus]: when `showShiftBanner=true` (the normal, non-simplified
 * flow most demo venues run), OFFLINE still outranks it — going offline mid-demo is itself
 * something worth surfacing immediately (a prospect watching odd behavior wants to know the
 * terminal lost connection, not see "Modo Demo" silently keep looking normal); when
 * `showShiftBanner=false`, the LIVE_DEMO banner keeps showing unconditionally, same as any
 * other status, via the clause above.
 */
fun shouldShowVenueStatusBanner(
    priority: BannerPriority?,
    venueSuspended: Boolean,
    showShiftBanner: Boolean,
): Boolean = priority == BannerPriority.VENUE_SUSPENDED || (venueSuspended && !showShiftBanner)

/**
 * Dueño único de la franja superior del Home (WelcomeScreen).
 *
 * **El bug que resuelve:** [VenueStatusBanner] se dibujaba como overlay absoluto
 * (`Modifier.align(Alignment.TopCenter)`) sobre un `Box` cuyo contenido de scroll empezaba
 * con [ShiftStatusBanner]. Ambos ocupan el mismo rectángulo — el ancho completo, pegados al
 * borde superior — así que cuando el venue no estaba `ACTIVE`, [VenueStatusBanner] tapaba
 * literalmente la tarjeta de turno que estaba debajo. Este host resuelve una única prioridad
 * y renderiza SOLO ese banner, en flujo (sin overlay), eliminando el solape por construcción.
 *
 * Reusa el contenido existente en vez de duplicarlo:
 * - `VENUE_SUSPENDED` → [VenueStatusBanner] (ya sabe pintar cada [VenueStatus] no-`ACTIVE`).
 * - `OFFLINE` / `SHIFT_CLOSED` / nada que avisar → [ShiftStatusBanner], que YA maneja
 *   internamente sus 4 estados (abierto, sin turno, offline-con-caché, offline-sin-caché) —
 *   incluido el caso "nada que avisar" (turno abierto, todo normal), donde sigue mostrando
 *   el estado del turno: es información útil, no una advertencia, así que no desaparece.
 *
 * **Por qué offline gana incluso sobre "venue suspendido":** mientras no hay conexión no se
 * puede confirmar si `venueStatus` sigue siendo el mismo (puede ser una copia vieja), y
 * [ShiftStatusBanner] YA sabe explicarlo (`isOffline` → "Sin conexión" + último estado
 * conocido) — mostrar en su lugar un [VenueStatusBanner] ajeno a la conectividad sería
 * priorizar un dato potencialmente viejo sobre la explicación correcta de por qué todo lo
 * demás puede estar desactualizado.
 *
 * **Ámbito deliberadamente LOCAL a WelcomeScreen** — no se monta globalmente en
 * `AppNavigation.kt`. `venueStatus` y el estado de turno solo existen hoy en esta pantalla
 * (`HomeViewModel`/`ShiftViewModel`, con scope al back-stack entry de Home); no hay una
 * fuente global equivalente sin cambiar el scope de esos ViewModels (riesgo no pedido por
 * esta tarea). `DeviceAlertBanner` (conectividad + salud de dispositivo + cola de pagos) ya
 * es dueño único de SU franja global, montada una sola vez arriba del NavHost en
 * `AppNavigation.kt` — ese mecanismo no se toca aquí.
 *
 * @param showShiftBanner Replica el gate existente (`isShiftSystemEnabled && moduleEnableShifts
 *   && !isSimplifiedMode`) que ya traía el llamador de [ShiftStatusBanner]. Si es `false`,
 *   [VenueStatusBanner] se sigue mostrando igual que antes (nunca dependió de este flag) pero
 *   no hay banner de turno con el que pueda solaparse — ver [shouldShowVenueStatusBanner]
 *   (fix round 1: esta franja podía quedar completamente vacía en ese caso).
 */
@Composable
fun ConnectionBannerHost(
    offline: Boolean,
    venueStatus: VenueStatus,
    shift: Shift?,
    cachedShiftInfo: CachedShiftInfo?,
    showShiftBanner: Boolean,
    onShiftClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val venueSuspended = venueStatus != VenueStatus.ACTIVE
    val priority = resolveVisibleBanner(
        offline = offline,
        venueSuspended = venueSuspended,
        shiftClosed = shift == null || shift.status != ShiftStatus.OPEN,
    )
    val showVenueBanner = shouldShowVenueStatusBanner(
        priority = priority,
        venueSuspended = venueSuspended,
        showShiftBanner = showShiftBanner,
    )

    when {
        showVenueBanner -> VenueStatusBanner(
            status = venueStatus,
            modifier = modifier,
        )

        showShiftBanner -> ShiftStatusBanner(
            shift = shift,
            isOffline = offline,
            cachedInfo = cachedShiftInfo,
            onClick = onShiftClick,
            modifier = modifier,
        )

        // Venue ACTIVE y el banner de turno está deshabilitado (módulo apagado / modo
        // simplificado) — nada que mostrar en esta franja, igual que antes de este cambio.
        else -> Unit
    }
}
