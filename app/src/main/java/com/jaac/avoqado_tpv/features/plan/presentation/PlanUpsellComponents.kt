package com.jaac.avoqado_tpv.features.plan.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier

/**
 * Plan upsell components — visible teasers for plan-gated features.
 *
 * Copy is INSTRUCTIONAL ONLY (where to activate the plan), never a payment
 * link: plans are activated from the web dashboard, not from the terminal.
 * Hardcoded Spanish, matching the app's copy convention.
 */

/** Upsell body copy for a feature gated behind [tier]. */
fun planUpsellMessage(tier: PlanTier): String =
    "Incluido en el Plan ${tier.displayName} — Actívalo desde tu dashboard web."

/**
 * Small tier pill, e.g. "Plan Pro". Use next to the title of a gated section
 * so the operator knows WHY the feature is locked and which plan unlocks it.
 */
@Composable
fun PlanTierBadge(
    tier: PlanTier,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "Plan ${tier.displayName}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Inline teaser card shown IN PLACE of a gated feature's UI. The feature stays
 * visible (title + badge) so the operator discovers it, with instructional
 * copy on how to unlock it.
 */
@Composable
fun PlanUpsellCard(
    featureTitle: String,
    tier: PlanTier,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = featureTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                PlanTierBadge(tier = tier)
            }
            Text(
                text = planUpsellMessage(tier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Plan gate WRAPPER — blur-preview paywall, mirror of the web dashboard's
 * FeatureGate.
 *
 * - **Unlocked** → renders [content] exactly as-is (zero extra layout).
 * - **Locked** → renders [content] as a dimmed, NON-INTERACTIVE preview with
 *   the instructional [PlanUpsellCard] centered on top. The operator previews
 *   what they're missing instead of seeing a replacement screen.
 *
 * PAX A910S runs Android 10 / API 29 where `Modifier.blur` is a no-op, so the
 * preview ALWAYS uses the dim (alpha) + translucent scrim fallback — never a
 * real blur.
 *
 * The locked preview is:
 * - non-interactive: a top-most scrim consumes every pointer event on the
 *   Initial pass before it can reach the content underneath;
 * - hidden from accessibility / screen readers via `clearAndSetSemantics {}`.
 *
 * PRESENTATION ONLY: the [locked] decision stays at the call site (PlanManager,
 * fail-open, exempt venues) — this composable never decides entitlement.
 * Whatever the screen naturally renders (loading / empty states included) is a
 * valid backdrop; no data-fetch suppression happens here.
 */
@Composable
fun PlanGate(
    locked: Boolean,
    featureTitle: String,
    tier: PlanTier,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!locked) {
        content()
        return
    }

    Box(modifier = modifier) {
        // 1. Real content as preview — dimmed (API 29: no blur available) and
        //    hidden from accessibility services.
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = 0.35f }
                .clearAndSetSemantics {},
        ) {
            content()
        }

        // 2. Translucent scrim + interaction blocker: consumes ALL pointer
        //    events (Initial pass) so nothing reaches the preview below.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                                .changes
                                .forEach { it.consume() }
                        }
                    }
                },
        )

        // 3. Instructional upsell card centered on top (opaque surface so the
        //    copy stays legible over the dimmed preview).
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            PlanUpsellCard(
                featureTitle = featureTitle,
                tier = tier,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/**
 * Upsell dialog for gated taps (e.g. a locked home tile or report filter).
 * Instructional only — the single button just dismisses.
 */
@Composable
fun PlanUpsellDialog(
    featureTitle: String,
    tier: PlanTier,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(featureTitle)
                PlanTierBadge(tier = tier)
            }
        },
        text = {
            Text(planUpsellMessage(tier))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido")
            }
        },
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Previews
// ──────────────────────────────────────────────────────────────────────────

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

@Preview(name = "Upsell card - Pro", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewUpsellCardPro() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            PlanUpsellCard(
                featureTitle = "Programa de referidos",
                tier = PlanTier.PRO,
            )
        }
    }
}

@Preview(name = "Upsell card - Premium", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewUpsellCardPremium() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            PlanUpsellCard(
                featureTitle = "Inventario serializado",
                tier = PlanTier.PREMIUM,
            )
        }
    }
}

@Preview(name = "PlanGate - locked preview", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewPlanGateLocked() {
    AvoqadoTheme {
        PlanGate(
            locked = true,
            featureTitle = "Historial de reportes",
            tier = PlanTier.PRO,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(6) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(20.dp),
                    ) {
                        Text(
                            text = "Periodo de ejemplo ${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
