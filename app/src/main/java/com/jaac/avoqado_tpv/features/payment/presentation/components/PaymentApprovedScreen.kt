package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 🎉 Payment Approved Animation Screen
 *
 * Shows a celebratory animation when payment is approved:
 * 1. Circle draws itself (like Square POS)
 * 2. Checkmark appears with scale animation
 * 3. Confetti particles fall from top
 * 4. Auto-dismisses after animation completes
 *
 * @param amount The payment amount to display
 * @param onAnimationComplete Called when animation finishes (after ~2.5s)
 */
@Composable
fun PaymentApprovedScreen(
    amount: String,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation duration constants
    val circleDuration = 600
    val checkmarkDelay = 400
    val checkmarkDuration = 400
    val totalDisplayTime = 3500L  // 3.5 seconds total

    // Circle animation progress (0f to 1f)
    var circleProgress by remember { mutableFloatStateOf(0f) }
    val animatedCircleProgress by animateFloatAsState(
        targetValue = circleProgress,
        animationSpec = tween(durationMillis = circleDuration, easing = FastOutSlowInEasing),
        label = "circle"
    )

    // Checkmark animation progress (0f to 1f)
    var checkmarkProgress by remember { mutableFloatStateOf(0f) }
    val animatedCheckmarkProgress by animateFloatAsState(
        targetValue = checkmarkProgress,
        animationSpec = tween(durationMillis = checkmarkDuration, easing = FastOutSlowInEasing),
        label = "checkmark"
    )

    // Checkmark scale animation
    var checkmarkScale by remember { mutableFloatStateOf(0f) }
    val animatedCheckmarkScale by animateFloatAsState(
        targetValue = checkmarkScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "checkmarkScale"
    )

    // Confetti state
    var showConfetti by remember { mutableStateOf(false) }

    // Start animations
    LaunchedEffect(Unit) {
        // Phase 1: Draw circle
        circleProgress = 1f
        delay(checkmarkDelay.toLong())

        // Phase 2: Show checkmark with bounce
        checkmarkProgress = 1f
        checkmarkScale = 1f

        // Phase 3: Show confetti
        delay(200)
        showConfetti = true

        // Phase 4: Wait and dismiss
        delay(totalDisplayTime - circleDuration - checkmarkDelay - 200)
        onAnimationComplete()
    }

    // Dark theme colors (Avoqado palette)
    val backgroundColor = Color(0xFF1C1C1C)  // Dark background
    val textColor = Color(0xFFFAFAFA)  // Soft white text

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Confetti layer (behind content)
        if (showConfetti) {
            ConfettiAnimation(
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Amount
            Text(
                text = amount,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp
                ),
                color = textColor
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Animated check circle
            AnimatedCheckCircle(
                circleProgress = animatedCircleProgress,
                checkmarkProgress = animatedCheckmarkProgress,
                checkmarkScale = animatedCheckmarkScale,
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // "Aprobado" text
            Text(
                text = "Aprobado",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = textColor
            )
        }
    }
}

/**
 * Animated circle with checkmark inside
 */
@Composable
private fun AnimatedCheckCircle(
    circleProgress: Float,
    checkmarkProgress: Float,
    checkmarkScale: Float,
    modifier: Modifier = Modifier
) {
    val successGreen = Color(0xFF22C55E) // Tailwind green-500
    val strokeWidth = with(LocalDensity.current) { 6.dp.toPx() }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = (size.minDimension - strokeWidth) / 2

        // Draw circle arc (progress animation)
        drawArc(
            color = successGreen,
            startAngle = -90f,
            sweepAngle = 360f * circleProgress,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Draw checkmark (with scale)
        if (checkmarkProgress > 0f && checkmarkScale > 0f) {
            val checkSize = radius * 0.5f * checkmarkScale
            val checkStrokeWidth = strokeWidth * 0.8f

            // Checkmark points (relative to center)
            val startX = center.x - checkSize * 0.5f
            val startY = center.y + checkSize * 0.1f
            val midX = center.x - checkSize * 0.1f
            val midY = center.y + checkSize * 0.5f
            val endX = center.x + checkSize * 0.6f
            val endY = center.y - checkSize * 0.3f

            // Draw first part of checkmark (short line)
            val firstLineProgress = (checkmarkProgress * 2f).coerceIn(0f, 1f)
            if (firstLineProgress > 0f) {
                drawLine(
                    color = successGreen,
                    start = Offset(startX, startY),
                    end = Offset(
                        startX + (midX - startX) * firstLineProgress,
                        startY + (midY - startY) * firstLineProgress
                    ),
                    strokeWidth = checkStrokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // Draw second part of checkmark (long line)
            val secondLineProgress = ((checkmarkProgress - 0.5f) * 2f).coerceIn(0f, 1f)
            if (secondLineProgress > 0f) {
                drawLine(
                    color = successGreen,
                    start = Offset(midX, midY),
                    end = Offset(
                        midX + (endX - midX) * secondLineProgress,
                        midY + (endY - midY) * secondLineProgress
                    ),
                    strokeWidth = checkStrokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Confetti particle animation
 */
@Composable
private fun ConfettiAnimation(
    modifier: Modifier = Modifier
) {
    // Confetti colors (festive palette)
    val colors = listOf(
        Color(0xFFFF6B6B), // Red
        Color(0xFF4ECDC4), // Teal
        Color(0xFFFFE66D), // Yellow
        Color(0xFF95E1D3), // Mint
        Color(0xFFF38181), // Coral
        Color(0xFF7B68EE), // Purple
        Color(0xFF22C55E), // Green
        Color(0xFF3B82F6), // Blue
    )

    // Generate confetti particles
    val particles = remember {
        List(50) { index ->
            ConfettiParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat() * -0.5f, // Start above screen
                velocityX = (Random.nextFloat() - 0.5f) * 0.02f,
                velocityY = Random.nextFloat() * 0.015f + 0.008f,
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 10f,
                size = Random.nextFloat() * 8f + 4f,
                color = colors[index % colors.size],
                shape = if (Random.nextBoolean()) ConfettiShape.RECTANGLE else ConfettiShape.CIRCLE
            )
        }
    }

    // Animation time
    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    Canvas(modifier = modifier) {
        particles.forEach { particle ->
            // Calculate position based on time
            val progress = (time + particle.y + 0.5f) % 1.5f
            val x = (particle.x + particle.velocityX * progress * 100f) * size.width
            val y = progress * size.height * 1.2f - size.height * 0.2f
            val rotation = particle.rotation + particle.rotationSpeed * progress * 100f

            if (y > -50 && y < size.height + 50) {
                rotate(rotation, pivot = Offset(x, y)) {
                    when (particle.shape) {
                        ConfettiShape.RECTANGLE -> {
                            drawRect(
                                color = particle.color,
                                topLeft = Offset(x - particle.size / 2, y - particle.size),
                                size = Size(particle.size, particle.size * 2)
                            )
                        }
                        ConfettiShape.CIRCLE -> {
                            drawCircle(
                                color = particle.color,
                                radius = particle.size / 2,
                                center = Offset(x, y)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val velocityX: Float,
    val velocityY: Float,
    val rotation: Float,
    val rotationSpeed: Float,
    val size: Float,
    val color: Color,
    val shape: ConfettiShape
)

private enum class ConfettiShape {
    RECTANGLE, CIRCLE
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, backgroundColor = 0xFF1C1C1C)
@Composable
private fun PaymentApprovedScreenPreview() {
    PaymentApprovedScreen(
        amount = "$94.21",
        onAnimationComplete = {}
    )
}
