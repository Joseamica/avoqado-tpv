package com.jaac.avoqado_tpv.features.referrals.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanTier
import com.jaac.avoqado_tpv.features.plan.presentation.PlanTierBadge
import com.jaac.avoqado_tpv.features.plan.presentation.planUpsellMessage
import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult

/**
 * UI states the section can be in. Kept separate from
 * [ValidationResult] so the section can also surface in-flight + idle
 * states without expanding the domain enum.
 */
sealed class ReferralCaptureUiState {
    data object Idle : ReferralCaptureUiState()
    data object Validating : ReferralCaptureUiState()
    data class Valid(val referrerName: String, val discountPercent: Int) : ReferralCaptureUiState()
    data class Invalid(val reason: ValidationResult.Reason) : ReferralCaptureUiState()
}

/**
 * "¿Te recomendó alguien?" capture section, embedded in the cart details
 * sheet of the Cobrar flow.
 *
 * **States:**
 * - [ReferralCaptureUiState.Idle] — empty input + "Validar" button (disabled
 *   until a code is typed).
 * - [ReferralCaptureUiState.Validating] — spinner overlay on the button.
 * - [ReferralCaptureUiState.Valid] — green confirmation + clear CTA.
 * - [ReferralCaptureUiState.Invalid] — red copy + reason. The EXISTING_CUSTOMER
 *   case adds a "Forzar atribución" outlined button.
 *
 * The composable is stateless — all data flows down via [code] +
 * [uiState], all actions bubble up via the lambdas. Hosted by
 * `CartDetailsSheet`.
 *
 * @param customerSelected required for validation. When false, the section
 *   shows a hint to pick a customer first and disables the input.
 * @param planLocked plan-tier gate (REFERRAL_PROGRAM requires Plan Pro). When
 *   true the CAPTURE UI is replaced by a visible teaser (tier badge +
 *   instructional upsell). Default false → fail open, behaves as today.
 */
@Composable
fun ReferralCaptureSection(
    code: String,
    uiState: ReferralCaptureUiState,
    customerSelected: Boolean,
    onCodeChange: (String) -> Unit,
    onValidate: () -> Unit,
    onClear: () -> Unit,
    onForceOverride: () -> Unit,
    modifier: Modifier = Modifier,
    planLocked: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header — gift icon + label (+ tier badge when plan-locked)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CardGiftcard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "¿Te recomendó alguien?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (planLocked) {
                Spacer(modifier = Modifier.width(8.dp))
                PlanTierBadge(tier = PlanTier.PRO)
            }
        }

        // Plan gate — visible teaser instead of the capture UI. Only the
        // CAPTURE UI is gated; nothing else in the checkout flow changes.
        if (planLocked) {
            Text(
                text = planUpsellMessage(PlanTier.PRO),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (!customerSelected) {
            Text(
                text = "Selecciona un cliente primero para usar un código de referido.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        when (val state = uiState) {
            ReferralCaptureUiState.Idle,
            ReferralCaptureUiState.Validating -> InputRow(
                code = code,
                onCodeChange = onCodeChange,
                onValidate = {
                    keyboard?.hide()
                    onValidate()
                },
                isValidating = state is ReferralCaptureUiState.Validating,
            )

            is ReferralCaptureUiState.Valid -> ValidBanner(
                referrerName = state.referrerName,
                discountPercent = state.discountPercent,
                onClear = onClear,
            )

            is ReferralCaptureUiState.Invalid -> InvalidBanner(
                reason = state.reason,
                onTryAgain = onClear,
                onForceOverride = onForceOverride,
            )
        }
    }
}

@Composable
private fun InputRow(
    code: String,
    onCodeChange: (String) -> Unit,
    onValidate: () -> Unit,
    isValidating: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { raw ->
                onCodeChange(raw.uppercase().take(64))
            },
            placeholder = { Text("CÓDIGO") },
            singleLine = true,
            enabled = !isValidating,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (code.isNotBlank()) onValidate() },
            ),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onValidate,
            enabled = !isValidating && code.isNotBlank(),
            modifier = Modifier.height(52.dp),
        ) {
            if (isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Validar")
            }
        }
    }
}

@Composable
private fun ValidBanner(
    referrerName: String,
    discountPercent: Int,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF22C55E).copy(alpha = 0.12f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF15803D),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Referido por $referrerName",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Se aplicó $discountPercent% descuento",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onClear,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("Quitar")
        }
    }
}

@Composable
private fun InvalidBanner(
    reason: ValidationResult.Reason,
    onTryAgain: () -> Unit,
    onForceOverride: () -> Unit,
) {
    val errorColor = Color(0xFFDC2626)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(errorColor.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = errorColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reasonHeadline(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = errorColor,
                )
                Text(
                    text = reasonExplanation(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTryAgain, modifier = Modifier.weight(1f)) {
                Text("Intentar otro código")
            }
            if (reason == ValidationResult.Reason.EXISTING_CUSTOMER) {
                Button(
                    onClick = onForceOverride,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Forzar atribución")
                }
            }
        }
        if (reason == ValidationResult.Reason.EXISTING_CUSTOMER) {
            Text(
                text = "Requiere autorización de un manager.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun reasonHeadline(reason: ValidationResult.Reason): String = when (reason) {
    ValidationResult.Reason.PROGRAM_INACTIVE -> "Programa inactivo"
    ValidationResult.Reason.CODE_NOT_FOUND -> "Código no encontrado"
    ValidationResult.Reason.SELF_REFERRAL -> "No puedes referirte a ti mismo"
    ValidationResult.Reason.EXISTING_CUSTOMER -> "Cliente recurrente"
    ValidationResult.Reason.UNKNOWN -> "No se pudo validar el código"
}

private fun reasonExplanation(reason: ValidationResult.Reason): String = when (reason) {
    ValidationResult.Reason.PROGRAM_INACTIVE -> "Este venue no tiene el programa de referidos activo."
    ValidationResult.Reason.CODE_NOT_FOUND -> "No encontramos a quién pertenece este código."
    ValidationResult.Reason.SELF_REFERRAL -> "El código pertenece al mismo cliente seleccionado."
    ValidationResult.Reason.EXISTING_CUSTOMER -> "Este cliente ya compró aquí antes. Necesitas a un manager para forzar la atribución."
    ValidationResult.Reason.UNKNOWN -> "Intenta de nuevo o usa otro código."
}

// ──────────────────────────────────────────────────────────────────────────
// Previews
// ──────────────────────────────────────────────────────────────────────────

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

@Preview(name = "Referral - Idle", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewIdle() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ReferralCaptureSection(
                code = "",
                uiState = ReferralCaptureUiState.Idle,
                customerSelected = true,
                onCodeChange = {},
                onValidate = {},
                onClear = {},
                onForceOverride = {},
            )
        }
    }
}

@Preview(name = "Referral - Valid", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewValid() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ReferralCaptureSection(
                code = "ANA-2026",
                uiState = ReferralCaptureUiState.Valid(
                    referrerName = "Ana López",
                    discountPercent = 10,
                ),
                customerSelected = true,
                onCodeChange = {},
                onValidate = {},
                onClear = {},
                onForceOverride = {},
            )
        }
    }
}

@Preview(name = "Referral - Existing customer", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewExistingCustomer() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ReferralCaptureSection(
                code = "ANA-2026",
                uiState = ReferralCaptureUiState.Invalid(
                    ValidationResult.Reason.EXISTING_CUSTOMER,
                ),
                customerSelected = true,
                onCodeChange = {},
                onValidate = {},
                onClear = {},
                onForceOverride = {},
            )
        }
    }
}

@Preview(name = "Referral - No customer", device = PAX_A910S, showSystemUi = false)
@Composable
private fun PreviewNoCustomer() {
    AvoqadoTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ReferralCaptureSection(
                code = "",
                uiState = ReferralCaptureUiState.Idle,
                customerSelected = false,
                onCodeChange = {},
                onValidate = {},
                onClear = {},
                onForceOverride = {},
            )
        }
    }
}
