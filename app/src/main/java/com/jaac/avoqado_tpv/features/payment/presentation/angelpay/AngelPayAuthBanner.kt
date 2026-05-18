package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthState

/**
 * AngelPay auth status banner — color-coded chip mirroring the 7 [AngelPayAuthState]
 * variants exposed by `AngelPayAuthRepository` (spec §6.5, §6.8, §18.1, §18.4).
 *
 * Color mapping:
 * - Green  ([avoqadoColors.statusSuccess]) → [AngelPayAuthState.Authenticated]
 * - Yellow ([avoqadoColors.statusWarning]) → transient/operator-actionable states
 *   ([AngelPayAuthState.Authenticating], [AngelPayAuthState.SelectingMerchant],
 *    [AngelPayAuthState.ConfigMismatchBanner])
 * - Red    ([avoqadoColors.statusError])   → blocking errors
 *   ([AngelPayAuthState.AuthError], [AngelPayAuthState.AccountSuspended],
 *    [AngelPayAuthState.Unauthenticated])
 *
 * Mounted at the top of [AngelPayPaymentScreen] (Task 34) so the cashier always
 * sees whether AngelPay is ready before initiating a charge.
 */
@Composable
fun AngelPayAuthBanner(
    state: AngelPayAuthState,
    activeMerchantName: String?,
    modifier: Modifier = Modifier,
) {
    val cfg = bannerConfig(state, activeMerchantName)

    Surface(
        color = cfg.backgroundColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (cfg.showSpinner) {
                CircularProgressIndicator(
                    color = cfg.textColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = cfg.text,
                color = cfg.textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONFIG HELPER
// ═══════════════════════════════════════════════════════════════════════════════

private data class BannerConfig(
    val backgroundColor: Color,
    val textColor: Color,
    val text: String,
    val showSpinner: Boolean,
)

@Composable
private fun bannerConfig(
    state: AngelPayAuthState,
    activeMerchantName: String?,
): BannerConfig {
    val green = MaterialTheme.avoqadoColors.statusSuccess
    val yellow = MaterialTheme.avoqadoColors.statusWarning
    val red = MaterialTheme.avoqadoColors.statusError

    return when (state) {
        is AngelPayAuthState.Authenticated -> BannerConfig(
            backgroundColor = green.copy(alpha = 0.15f),
            textColor = green,
            text = "AngelPay: ${activeMerchantName ?: "—"}",
            showSpinner = false,
        )
        is AngelPayAuthState.Authenticating -> BannerConfig(
            backgroundColor = yellow.copy(alpha = 0.15f),
            textColor = yellow,
            text = "AngelPay: autenticando...",
            showSpinner = true,
        )
        is AngelPayAuthState.SelectingMerchant -> BannerConfig(
            backgroundColor = yellow.copy(alpha = 0.15f),
            textColor = yellow,
            text = "AngelPay: selecciona merchant",
            showSpinner = false,
        )
        is AngelPayAuthState.AuthError -> BannerConfig(
            backgroundColor = red.copy(alpha = 0.15f),
            textColor = red,
            text = "AngelPay: ${truncate(state.message, 60)}",
            showSpinner = false,
        )
        is AngelPayAuthState.AccountSuspended -> BannerConfig(
            backgroundColor = red.copy(alpha = 0.15f),
            textColor = red,
            text = "AngelPay: cuenta ${state.statusFromBackend}",
            showSpinner = false,
        )
        is AngelPayAuthState.ConfigMismatchBanner -> {
            val drifted = state.onlyInSdk.size + state.onlyInAvoqado.size
            BannerConfig(
                backgroundColor = yellow.copy(alpha = 0.15f),
                textColor = yellow,
                text = "AngelPay: $drifted merchants desincronizados",
                showSpinner = false,
            )
        }
        is AngelPayAuthState.Unauthenticated -> BannerConfig(
            backgroundColor = red.copy(alpha = 0.15f),
            textColor = red,
            text = "AngelPay: requiere autenticación",
            showSpinner = false,
        )
    }
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max - 1) + "…"

// ═══════════════════════════════════════════════════════════════════════════════
// PREVIEWS (PAX A910S — 360x640dp)
// ═══════════════════════════════════════════════════════════════════════════════

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerAuthenticatedPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.Authenticated,
            activeMerchantName = "Doña Simona Polanco",
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerAuthenticatingPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.Authenticating,
            activeMerchantName = null,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerSelectingMerchantPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.SelectingMerchant(
                merchants = emptyList(),
                temporaryToken = "tmp",
            ),
            activeMerchantName = null,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerAuthErrorPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.AuthError(
                message = "Credenciales inválidas tras 3 reintentos",
            ),
            activeMerchantName = null,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerAccountSuspendedPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.AccountSuspended(
                statusFromBackend = "SUSPENDED",
                reason = "billing",
            ),
            activeMerchantName = null,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerConfigMismatchPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.ConfigMismatchBanner(
                onlyInSdk = setOf(101, 102),
                onlyInAvoqado = setOf(999),
            ),
            activeMerchantName = "Doña Simona Polanco",
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayAuthBannerUnauthenticatedPreview() {
    AvoqadoTheme {
        AngelPayAuthBanner(
            state = AngelPayAuthState.Unauthenticated,
            activeMerchantName = null,
        )
    }
}
