package com.jaac.avoqado_tpv.features.training.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingQuizQuestionDto

/**
 * Paginated quiz screen — shows one question at a time
 */
@Composable
fun TrainingQuizScreen(
    questions: List<TrainingQuizQuestionDto>,
    answers: Map<Int, Set<Int>>,
    currentQuestionIndex: Int,
    passThreshold: Int,
    onSelectAnswer: (questionIndex: Int, optionIndex: Int) -> Unit,
    onToggleAnswer: (questionIndex: Int, optionIndex: Int) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSubmit: () -> Unit,
    onBackToSteps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalQuestions = questions.size
    val currentQuestion = questions.getOrNull(currentQuestionIndex) ?: return
    val isLastQuestion = currentQuestionIndex == totalQuestions - 1
    val allAnswered = questions.indices.all { idx ->
        val a = answers[idx]
        a != null && a.isNotEmpty()
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Progress header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pregunta ${currentQuestionIndex + 1} de $totalQuestions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            QuizStepDots(
                currentIndex = currentQuestionIndex,
                total = totalQuestions,
                answeredIndices = answers.filter { it.value.isNotEmpty() }.keys
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Necesitas $passThreshold% para aprobar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Scrollable question content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            QuizQuestionCard(
                questionNumber = currentQuestionIndex + 1,
                question = currentQuestion,
                selectedAnswers = answers[currentQuestionIndex] ?: emptySet(),
                onSelectAnswer = { optIndex -> onSelectAnswer(currentQuestionIndex, optIndex) },
                onToggleAnswer = { optIndex -> onToggleAnswer(currentQuestionIndex, optIndex) }
            )
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = if (currentQuestionIndex == 0) onBackToSteps else onPrevious,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Anterior")
            }

            if (isLastQuestion) {
                Button(
                    onClick = onSubmit,
                    enabled = allAnswered,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Enviar")
                }
            } else {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Siguiente")
                }
            }
        }
    }
}

/**
 * Quiz review screen — shows one question at a time with correct/wrong feedback
 */
@Composable
fun QuizReviewScreen(
    questions: List<TrainingQuizQuestionDto>,
    answers: Map<Int, Set<Int>>,
    reviewQuestionIndex: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalQuestions = questions.size
    val currentQuestion = questions.getOrNull(reviewQuestionIndex) ?: return
    val isLastQuestion = reviewQuestionIndex == totalQuestions - 1
    val userAnswers = answers[reviewQuestionIndex] ?: emptySet()
    val isMultiSelect = currentQuestion.questionType == "MULTI_SELECT"

    val successColor = MaterialTheme.avoqadoColors.statusSuccess
    val errorColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Progress header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Revisión - Pregunta ${reviewQuestionIndex + 1} de $totalQuestions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            QuizStepDots(
                currentIndex = reviewQuestionIndex,
                total = totalQuestions,
                answeredIndices = answers.filter { it.value.isNotEmpty() }.keys
            )
        }

        // Scrollable review content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Question text with type indicator for multi-select
            Text(
                text = "${reviewQuestionIndex + 1}. ${currentQuestion.question}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (isMultiSelect) {
                Text(
                    text = "(Selección múltiple)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Options with correct/wrong indicators
            currentQuestion.options.forEachIndexed { optIndex, option ->
                val isCorrectAnswer = if (isMultiSelect) {
                    currentQuestion.correctIndices.contains(optIndex)
                } else {
                    optIndex == currentQuestion.correctIndex
                }
                val isUserPick = userAnswers.contains(optIndex)
                val isWrongPick = isUserPick && !isCorrectAnswer

                val borderColor = when {
                    isCorrectAnswer -> successColor
                    isWrongPick -> errorColor
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val bgColor = when {
                    isCorrectAnswer -> successColor.copy(alpha = 0.08f)
                    isWrongPick -> errorColor.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surface
                }
                val borderWidth = if (isCorrectAnswer || isWrongPick) 2.dp else 1.dp

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    border = BorderStroke(borderWidth, borderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when {
                            isCorrectAnswer -> Icon(
                                Icons.Default.Check,
                                contentDescription = "Correcto",
                                tint = successColor,
                                modifier = Modifier.size(20.dp)
                            )
                            isWrongPick -> Icon(
                                Icons.Default.Close,
                                contentDescription = "Incorrecto",
                                tint = errorColor,
                                modifier = Modifier.size(20.dp)
                            )
                            else -> Spacer(modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCorrectAnswer) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            // Explanation (if available)
            if (!currentQuestion.explanation.isNullOrBlank()) {
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
                            text = currentQuestion.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.avoqadoColors.statusInfo
                        )
                    }
                }
            }
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (reviewQuestionIndex > 0) {
                OutlinedButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Anterior")
                }
            }

            Button(
                onClick = if (isLastQuestion) onFinish else onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(if (isLastQuestion) "Ver resultado" else "Siguiente")
            }
        }
    }
}

@Composable
private fun QuizStepDots(
    currentIndex: Int,
    total: Int,
    answeredIndices: Set<Int>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val isActive = index == currentIndex
            val isAnswered = answeredIndices.contains(index)
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (isActive) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isAnswered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun QuizQuestionCard(
    questionNumber: Int,
    question: TrainingQuizQuestionDto,
    selectedAnswers: Set<Int>,
    onSelectAnswer: (Int) -> Unit,
    onToggleAnswer: (Int) -> Unit
) {
    val isMultiSelect = question.questionType == "MULTI_SELECT"

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "$questionNumber. ${question.question}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (isMultiSelect) {
                Text(
                    text = "Selecciona todas las respuestas correctas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            question.options.forEachIndexed { optIndex, option ->
                val isSelected = selectedAnswers.contains(optIndex)

                Surface(
                    onClick = {
                        if (isMultiSelect) onToggleAnswer(optIndex) else onSelectAnswer(optIndex)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isMultiSelect) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleAnswer(optIndex) }
                            )
                        } else {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectAnswer(optIndex) }
                            )
                        }
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
