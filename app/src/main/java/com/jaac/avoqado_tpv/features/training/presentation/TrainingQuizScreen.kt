package com.jaac.avoqado_tpv.features.training.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingQuizQuestionDto

@Composable
fun TrainingQuizScreen(
    questions: List<TrainingQuizQuestionDto>,
    answers: Map<Int, Int>,
    onSelectAnswer: (questionIndex: Int, optionIndex: Int) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allAnswered = questions.indices.all { answers.containsKey(it) }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrollable quiz content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Quiz",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Responde todas las preguntas para completar el entrenamiento. Necesitas 70% para aprobar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            questions.forEachIndexed { qIndex, question ->
                QuizQuestionCard(
                    questionNumber = qIndex + 1,
                    question = question,
                    selectedAnswer = answers[qIndex],
                    onSelectAnswer = { optIndex -> onSelectAnswer(qIndex, optIndex) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Anterior")
            }

            Button(
                onClick = onSubmit,
                enabled = allAnswered,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Enviar respuestas")
            }
        }
    }
}

@Composable
private fun QuizQuestionCard(
    questionNumber: Int,
    question: TrainingQuizQuestionDto,
    selectedAnswer: Int?,
    onSelectAnswer: (Int) -> Unit
) {
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

            question.options.forEachIndexed { optIndex, option ->
                val isSelected = selectedAnswer == optIndex

                Surface(
                    onClick = { onSelectAnswer(optIndex) },
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
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectAnswer(optIndex) }
                        )
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
