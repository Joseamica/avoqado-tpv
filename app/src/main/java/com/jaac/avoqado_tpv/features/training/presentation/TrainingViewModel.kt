package com.jaac.avoqado_tpv.features.training.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingModuleDto
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingProgressDto
import com.jaac.avoqado_tpv.features.training.data.dto.TrainingQuizQuestionDto
import com.jaac.avoqado_tpv.features.training.data.dto.UpdateProgressRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Training List State
 */
data class TrainingListState(
    val trainings: List<TrainingModuleDto> = emptyList(),
    val progressMap: Map<String, TrainingProgressDto> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Training Detail/Viewer State
 */
data class TrainingDetailState(
    val training: TrainingModuleDto? = null,
    val currentStepIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Quiz state
    val quizAnswers: Map<Int, Int> = emptyMap(), // questionIndex -> selectedOptionIndex
    val isQuizSubmitted: Boolean = false,
    val quizScore: Int = 0,
    val quizTotal: Int = 0,
    val quizPassed: Boolean = false,
    val showQuiz: Boolean = false
)

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) : ViewModel() {

    private val _listState = MutableStateFlow(TrainingListState())
    val listState: StateFlow<TrainingListState> = _listState.asStateFlow()

    private val _detailState = MutableStateFlow(TrainingDetailState())
    val detailState: StateFlow<TrainingDetailState> = _detailState.asStateFlow()

    /**
     * Fetch all available trainings and progress
     */
    fun fetchTrainings() {
        viewModelScope.launch(Dispatchers.IO) {
            _listState.value = _listState.value.copy(isLoading = true, error = null)
            try {
                val response = apiService.getTrainings()
                if (response.isSuccessful && response.body()?.success == true) {
                    val trainings = response.body()?.data ?: emptyList()

                    // Fetch progress
                    val staffId = secureStorage.getStaffId()
                    var progressMap = emptyMap<String, TrainingProgressDto>()
                    if (staffId != null) {
                        try {
                            val progressResponse = apiService.getTrainingProgress(staffId)
                            if (progressResponse.isSuccessful && progressResponse.body()?.success == true) {
                                progressMap = (progressResponse.body()?.data ?: emptyList())
                                    .associateBy { it.trainingModuleId }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to fetch training progress")
                        }
                    }

                    _listState.value = _listState.value.copy(
                        trainings = trainings,
                        progressMap = progressMap,
                        isLoading = false
                    )
                } else {
                    _listState.value = _listState.value.copy(
                        isLoading = false,
                        error = "Error al cargar entrenamientos"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch trainings")
                _listState.value = _listState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Error de conexión"
                )
            }
        }
    }

    /**
     * Load a specific training module for the step viewer
     */
    fun selectTraining(trainingId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _detailState.value = TrainingDetailState(isLoading = true)
            try {
                val response = apiService.getTrainingDetail(trainingId)
                if (response.isSuccessful && response.body()?.success == true) {
                    val training = response.body()?.data
                    _detailState.value = TrainingDetailState(
                        training = training,
                        isLoading = false
                    )

                    // Update progress: started
                    saveProgress(trainingId, lastStepViewed = 0)
                } else {
                    _detailState.value = TrainingDetailState(
                        isLoading = false,
                        error = "Entrenamiento no encontrado"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load training detail")
                _detailState.value = TrainingDetailState(
                    isLoading = false,
                    error = e.message ?: "Error de conexión"
                )
            }
        }
    }

    /**
     * Navigate to next step
     */
    fun nextStep() {
        val state = _detailState.value
        val training = state.training ?: return
        val steps = training.steps ?: return
        val nextIndex = state.currentStepIndex + 1

        if (nextIndex < steps.size) {
            _detailState.value = state.copy(currentStepIndex = nextIndex)
            saveProgress(training.id, lastStepViewed = nextIndex)
        } else {
            // After last step → show quiz if exists, otherwise complete
            val hasQuiz = !training.quizQuestions.isNullOrEmpty()
            if (hasQuiz) {
                _detailState.value = state.copy(showQuiz = true)
            } else {
                completeTraining(training.id)
            }
        }
    }

    /**
     * Navigate to previous step
     */
    fun previousStep() {
        val state = _detailState.value
        if (state.showQuiz) {
            _detailState.value = state.copy(showQuiz = false)
            return
        }
        if (state.currentStepIndex > 0) {
            _detailState.value = state.copy(currentStepIndex = state.currentStepIndex - 1)
        }
    }

    /**
     * Select a quiz answer
     */
    fun selectQuizAnswer(questionIndex: Int, optionIndex: Int) {
        val state = _detailState.value
        val newAnswers = state.quizAnswers.toMutableMap()
        newAnswers[questionIndex] = optionIndex
        _detailState.value = state.copy(quizAnswers = newAnswers)
    }

    /**
     * Submit quiz answers
     */
    fun submitQuiz() {
        val state = _detailState.value
        val training = state.training ?: return
        val questions = training.quizQuestions ?: return

        var correct = 0
        questions.forEachIndexed { index, question ->
            val selectedAnswer = state.quizAnswers[index]
            if (selectedAnswer == question.correctIndex) {
                correct++
            }
        }

        val total = questions.size
        val passed = total > 0 && (correct.toFloat() / total) >= 0.7f

        _detailState.value = state.copy(
            isQuizSubmitted = true,
            quizScore = correct,
            quizTotal = total,
            quizPassed = passed
        )

        // Save progress with quiz results
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.updateTrainingProgress(
                    training.id,
                    UpdateProgressRequest(
                        staffId = secureStorage.getStaffId(),
                        isCompleted = passed,
                        quizScore = correct,
                        quizTotal = total,
                        quizPassed = passed
                    )
                )
                // Refresh progress map
                fetchTrainings()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save quiz progress")
            }
        }
    }

    /**
     * Retry the quiz (reset quiz state)
     */
    fun retryQuiz() {
        val state = _detailState.value
        _detailState.value = state.copy(
            quizAnswers = emptyMap(),
            isQuizSubmitted = false,
            quizScore = 0,
            quizTotal = 0,
            quizPassed = false
        )
    }

    /**
     * Mark training as completed (no quiz path)
     */
    private fun completeTraining(trainingId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.updateTrainingProgress(
                    trainingId,
                    UpdateProgressRequest(
                        staffId = secureStorage.getStaffId(),
                        isCompleted = true
                    )
                )
                _detailState.value = _detailState.value.copy(
                    isQuizSubmitted = true, // Reuse as "completed" flag
                    quizPassed = true
                )
                // Refresh list progress
                fetchTrainings()
            } catch (e: Exception) {
                Timber.e(e, "Failed to mark training complete")
            }
        }
    }

    /**
     * Save step progress in background
     */
    private fun saveProgress(trainingId: String, lastStepViewed: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.updateTrainingProgress(
                    trainingId,
                    UpdateProgressRequest(
                        staffId = secureStorage.getStaffId(),
                        lastStepViewed = lastStepViewed
                    )
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to save step progress")
            }
        }
    }
}
