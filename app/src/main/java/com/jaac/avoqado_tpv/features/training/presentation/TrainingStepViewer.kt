package com.jaac.avoqado_tpv.features.training.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.training.presentation.components.FullscreenMediaDialog
import com.jaac.avoqado_tpv.features.training.presentation.components.TrainingVideoPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingStepViewer(
    viewModel: TrainingViewModel,
    trainingId: String,
    onBack: () -> Unit
) {
    val state by viewModel.detailState.collectAsStateWithLifecycle()

    // Fullscreen media state
    var showFullscreenMedia by remember { mutableStateOf(false) }
    var fullscreenMediaUrl by remember { mutableStateOf("") }
    var fullscreenMediaType by remember { mutableStateOf("IMAGE") }

    LaunchedEffect(trainingId) {
        viewModel.selectTraining(trainingId)
    }

    val training = state.training
    val steps = training?.steps ?: emptyList()
    val currentStep = steps.getOrNull(state.currentStepIndex)
    val totalSteps = steps.size

    // Fullscreen media dialog
    if (showFullscreenMedia) {
        FullscreenMediaDialog(
            mediaUrl = fullscreenMediaUrl,
            mediaType = fullscreenMediaType,
            onDismiss = { showFullscreenMedia = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = training?.title ?: "Cargando...",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        when {
                            state.showQuizReview -> {
                                Text(
                                    text = "Revisión del quiz",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.showQuiz -> {
                                Text(
                                    text = "Quiz",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            !state.isQuizSubmitted && totalSteps > 0 -> {
                                Text(
                                    text = "Paso ${state.currentStepIndex + 1} de $totalSteps",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.error ?: "Error",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Quiz review mode
            state.showQuizReview -> {
                QuizReviewScreen(
                    questions = training?.quizQuestions ?: emptyList(),
                    answers = state.quizAnswers,
                    reviewQuestionIndex = state.reviewQuestionIndex,
                    onNext = { viewModel.nextReviewQuestion() },
                    onPrevious = { viewModel.previousReviewQuestion() },
                    onFinish = { viewModel.exitReview() },
                    modifier = Modifier.padding(paddingValues)
                )
            }

            // Quiz submitted -> results screen
            state.isQuizSubmitted -> {
                CompletionScreen(
                    state = state,
                    hasQuiz = !training?.quizQuestions.isNullOrEmpty(),
                    passThreshold = training?.quizPassThreshold ?: 70,
                    maxAttempts = training?.quizMaxAttempts ?: 0,
                    canRetry = viewModel.canRetryQuiz(),
                    onRetry = { viewModel.retryQuiz() },
                    onReview = { viewModel.showReview() },
                    onFinish = onBack,
                    modifier = Modifier.padding(paddingValues)
                )
            }

            // Show quiz (paginated)
            state.showQuiz -> {
                TrainingQuizScreen(
                    questions = training?.quizQuestions ?: emptyList(),
                    answers = state.quizAnswers,
                    currentQuestionIndex = state.currentQuestionIndex,
                    passThreshold = training?.quizPassThreshold ?: 70,
                    onSelectAnswer = { qIndex, optIndex -> viewModel.selectQuizAnswer(qIndex, optIndex) },
                    onToggleAnswer = { qIndex, optIndex -> viewModel.toggleQuizAnswer(qIndex, optIndex) },
                    onNext = { viewModel.nextQuestion() },
                    onPrevious = { viewModel.previousQuestion() },
                    onSubmit = { viewModel.submitQuiz() },
                    onBackToSteps = { viewModel.previousStep() },
                    modifier = Modifier.padding(paddingValues)
                )
            }

            // Show step
            currentStep != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Step indicator dots
                    StepIndicator(
                        currentStep = state.currentStepIndex,
                        totalSteps = totalSteps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // Scrollable content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        // Media area - tappable for fullscreen
                        if (currentStep.mediaUrl != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        fullscreenMediaUrl = currentStep.mediaUrl
                                        fullscreenMediaType = currentStep.mediaType ?: "IMAGE"
                                        showFullscreenMedia = true
                                    }
                            ) {
                                if (currentStep.mediaType == "VIDEO") {
                                    TrainingVideoPlayer(
                                        videoUrl = currentStep.mediaUrl,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                    )
                                } else {
                                    AsyncImage(
                                        model = currentStep.mediaUrl,
                                        contentDescription = currentStep.title,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f),
                                        contentScale = ContentScale.Fit
                                    )
                                }

                                // Fullscreen hint overlay
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.Black.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_fullscreen),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Ampliar",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Title
                        Text(
                            text = currentStep.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Instruction text
                        Text(
                            text = currentStep.instruction,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp
                        )

                        // Tip callout
                        if (currentStep.tipText != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.avoqadoColors.statusInfo.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.avoqadoColors.statusInfo,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = currentStep.tipText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.avoqadoColors.statusInfo
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Navigation buttons (large touch targets)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Previous button
                        OutlinedButton(
                            onClick = { viewModel.previousStep() },
                            enabled = state.currentStepIndex > 0,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Anterior")
                        }

                        // Next button
                        Button(
                            onClick = { viewModel.nextStep() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                if (state.currentStepIndex == totalSteps - 1) {
                                    if (!training?.quizQuestions.isNullOrEmpty()) "Quiz" else "Finalizar"
                                } else {
                                    "Siguiente"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == currentStep) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentStep) MaterialTheme.colorScheme.primary
                        else if (index < currentStep) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

@Composable
private fun CompletionScreen(
    state: TrainingDetailState,
    hasQuiz: Boolean,
    passThreshold: Int,
    maxAttempts: Int,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onReview: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val passed = state.quizPassed
    val successColor = MaterialTheme.avoqadoColors.statusSuccess
    val errorColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (passed) successColor else errorColor,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (passed) "Completado!" else "No aprobado",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        if (hasQuiz) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Resultado: ${state.quizScore}/${state.quizTotal}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val thresholdText = if (passed) {
                "Has aprobado el quiz"
            } else {
                "Necesitas $passThreshold% para aprobar"
            }
            Text(
                text = thresholdText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Attempt counter
            Spacer(modifier = Modifier.height(4.dp))
            val attemptText = if (maxAttempts == 0) {
                "Intento ${state.attemptNumber}"
            } else {
                "Intento ${state.attemptNumber} de $maxAttempts"
            }
            Text(
                text = attemptText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Has completado este entrenamiento",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Volver a entrenamientos")
        }

        // Review answers button (always visible when quiz exists)
        if (hasQuiz) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onReview,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Revisar respuestas")
            }
        }

        // Retry button (only when failed and can retry)
        if (hasQuiz && !passed) {
            Spacer(modifier = Modifier.height(12.dp))
            if (canRetry) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Intentar de nuevo")
                }
            } else {
                Text(
                    text = "Intentos agotados",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
