package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/**
 * Square-style numeric keypad for entering custom amounts.
 *
 * Layout (PAX A910S compact):
 * - Large amount display ($0.00 by default)
 * - Quick amount pills ($50, $100, $200, $500)
 * - 4x3 keypad: 1–9, then C, 0, +
 *
 * The "+ Nota" button that used to live above the quick amounts was moved
 * to the cart details modal's kebab menu — it kept stealing vertical space
 * and pushing the bottom keypad row off-screen on 640dp devices.
 *
 * The `+` key calls [onAddToCart], "C" clears the amount, each digit
 * appends right-to-left in cent precision so "1" → "0.01", "10" → "0.10",
 * "100" → "1.00".
 */
@Composable
fun NumericKeypadView(
    amountCents: Int,
    onAmountChange: (Int) -> Unit,
    onAddToCart: () -> Unit,
    modifier: Modifier = Modifier,
    useCompactSizing: Boolean = false,
) {
    val formattedAmount = String.format("$%.2f", amountCents / 100.0)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val isCompact = useCompactSizing || screenHeight < 700

    val amountFontSize = if (isCompact) 36.sp else 60.sp
    val keypadFontSize = if (isCompact) 24.sp else 36.sp
    val topPadding = if (isCompact) 8.dp else 16.dp
    val horizontalPadding = 24.dp
    val rowPadding = 16.dp

    Column(modifier = modifier.fillMaxSize()) {
        // Amount display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        ) {
            Text(
                text = formattedAmount,
                fontSize = amountFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = topPadding),
            )
        }

        Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

        // Quick amount pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = rowPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QUICK_AMOUNTS.forEach { amount ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(50),
                        )
                        .clickable { onAmountChange(amount * 100) }
                        .padding(vertical = if (isCompact) 4.dp else 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$$amount",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

        // Keypad grid — fills remaining space
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = rowPadding)
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .background(MaterialTheme.colorScheme.outlineVariant),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            KEYPAD_ROWS.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    row.forEach { key ->
                        KeypadButton(
                            label = key,
                            modifier = Modifier.weight(1f),
                            isAction = key == "+",
                            fontSize = keypadFontSize,
                            onClick = {
                                when (key) {
                                    "C" -> onAmountChange(0)
                                    "+" -> {
                                        if (amountCents > 0) onAddToCart()
                                    }
                                    else -> {
                                        val digit = key.toIntOrNull() ?: return@KeypadButton
                                        if (amountCents < 100_000_000) {
                                            onAmountChange(amountCents * 10 + digit)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    modifier: Modifier = Modifier,
    isAction: Boolean = false,
    fontSize: TextUnit = 36.sp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = if (isAction) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isAction) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private val QUICK_AMOUNTS = listOf(50, 100, 200, 500)

private val KEYPAD_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("C", "0", "+"),
)

@Preview(name = "Keypad - Empty", device = PAX_A910S, showSystemUi = true)
@Composable
private fun KeypadEmptyPreview() {
    AvoqadoTheme {
        NumericKeypadView(amountCents = 0, onAmountChange = {}, onAddToCart = {})
    }
}

@Preview(name = "Keypad - With Amount", device = PAX_A910S, showSystemUi = true)
@Composable
private fun KeypadWithAmountPreview() {
    AvoqadoTheme {
        NumericKeypadView(amountCents = 15050, onAmountChange = {}, onAddToCart = {})
    }
}
