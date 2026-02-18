package com.jaac.avoqado_tpv.features.training.presentation

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.training.data.dto.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var apiService: ApiService
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        apiService = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        every { secureStorage.getStaffId() } returns "staff-1"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): TrainingViewModel {
        return TrainingViewModel(
            apiService = apiService,
            secureStorage = secureStorage
        )
    }

    private fun createTraining(
        quizPassThreshold: Int = 70,
        quizMaxAttempts: Int = 0,
        questions: List<TrainingQuizQuestionDto> = emptyList()
    ) = TrainingModuleDto(
        id = "training-1",
        title = "Test Training",
        description = "Description",
        category = "GENERAL",
        difficulty = "BASIC",
        estimatedMinutes = 10,
        isRequired = false,
        position = 0,
        status = "PUBLISHED",
        quizPassThreshold = quizPassThreshold,
        quizMaxAttempts = quizMaxAttempts,
        steps = listOf(
            TrainingStepDto(
                id = "step-1",
                stepNumber = 1,
                title = "Step 1",
                instruction = "Do this",
                mediaType = "IMAGE"
            )
        ),
        quizQuestions = questions
    )

    private fun createQuestion(
        id: String = "q1",
        questionType: String = "MULTIPLE_CHOICE",
        question: String = "What is 1+1?",
        options: List<String> = listOf("1", "2", "3"),
        correctIndex: Int = 1,
        correctIndices: List<Int> = emptyList(),
        explanation: String? = null
    ) = TrainingQuizQuestionDto(
        id = id,
        questionType = questionType,
        question = question,
        options = options,
        correctIndex = correctIndex,
        correctIndices = correctIndices,
        position = 0,
        explanation = explanation
    )

    // ===== QUIZ PAGINATION =====

    @Test
    fun `nextQuestion advances currentQuestionIndex`() {
        val vm = createViewModel()
        val questions = listOf(createQuestion("q1"), createQuestion("q2"), createQuestion("q3"))
        val training = createTraining(questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            currentQuestionIndex = 0
        ))

        vm.nextQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(1)

        vm.nextQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(2)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `nextQuestion does not exceed question count`() {
        val vm = createViewModel()
        val questions = listOf(createQuestion("q1"), createQuestion("q2"))
        val training = createTraining(questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            currentQuestionIndex = 1 // Already at last question
        ))

        vm.nextQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(1) // Stays at 1

        vm.viewModelScope.cancel()
    }

    @Test
    fun `previousQuestion decrements currentQuestionIndex`() {
        val vm = createViewModel()
        val questions = listOf(createQuestion("q1"), createQuestion("q2"), createQuestion("q3"))
        val training = createTraining(questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            currentQuestionIndex = 2
        ))

        vm.previousQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(1)

        vm.previousQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(0)

        // At 0, should stay at 0
        vm.previousQuestion()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(0)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `selectQuizAnswer updates quizAnswers map with set`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = listOf(createQuestion())),
            showQuiz = true
        ))

        vm.selectQuizAnswer(0, 2)
        assertThat(vm.detailState.value.quizAnswers[0]).isEqualTo(setOf(2))

        // Change answer — replaces the set
        vm.selectQuizAnswer(0, 1)
        assertThat(vm.detailState.value.quizAnswers[0]).isEqualTo(setOf(1))

        vm.viewModelScope.cancel()
    }

    // ===== MULTI-SELECT (toggleQuizAnswer) =====

    @Test
    fun `toggleQuizAnswer adds and removes options`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = listOf(
                createQuestion(questionType = "MULTI_SELECT", correctIndices = listOf(0, 2))
            )),
            showQuiz = true
        ))

        // Add first option
        vm.toggleQuizAnswer(0, 0)
        assertThat(vm.detailState.value.quizAnswers[0]).isEqualTo(setOf(0))

        // Add second option
        vm.toggleQuizAnswer(0, 2)
        assertThat(vm.detailState.value.quizAnswers[0]).isEqualTo(setOf(0, 2))

        // Remove first option
        vm.toggleQuizAnswer(0, 0)
        assertThat(vm.detailState.value.quizAnswers[0]).isEqualTo(setOf(2))

        vm.viewModelScope.cancel()
    }

    // ===== CONFIGURABLE THRESHOLD =====

    @Test
    fun `submitQuiz uses training quizPassThreshold`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion("q1", correctIndex = 0),
            createQuestion("q2", correctIndex = 1),
            createQuestion("q3", correctIndex = 2),
            createQuestion("q4", correctIndex = 0)
        )
        // 70% threshold: need 3/4 (75%) to pass
        val training = createTraining(quizPassThreshold = 70, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0), 1 to setOf(1), 2 to setOf(2), 3 to setOf(1)) // 3 correct, 1 wrong = 75%
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizPassed).isTrue()
        assertThat(vm.detailState.value.quizScore).isEqualTo(3)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz with 80 threshold fails at 75 percent`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion("q1", correctIndex = 0),
            createQuestion("q2", correctIndex = 1),
            createQuestion("q3", correctIndex = 2),
            createQuestion("q4", correctIndex = 0)
        )
        val training = createTraining(quizPassThreshold = 80, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0), 1 to setOf(1), 2 to setOf(2), 3 to setOf(1)) // 3/4 = 75%, below 80%
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizPassed).isFalse()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz with 60 threshold passes at 60 percent`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion("q1", correctIndex = 0),
            createQuestion("q2", correctIndex = 1),
            createQuestion("q3", correctIndex = 2),
            createQuestion("q4", correctIndex = 0),
            createQuestion("q5", correctIndex = 1)
        )
        val training = createTraining(quizPassThreshold = 60, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            // 3 correct out of 5 = 60%
            quizAnswers = mapOf(0 to setOf(0), 1 to setOf(1), 2 to setOf(2), 3 to setOf(1), 4 to setOf(0))
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizPassed).isTrue()
        assertThat(vm.detailState.value.quizScore).isEqualTo(3)

        vm.viewModelScope.cancel()
    }

    // ===== MULTI-SELECT SCORING =====

    @Test
    fun `submitQuiz multi-select all-or-nothing correct`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion(
                "q1",
                questionType = "MULTI_SELECT",
                options = listOf("A", "B", "C", "D"),
                correctIndices = listOf(0, 2) // A and C are correct
            )
        )
        val training = createTraining(quizPassThreshold = 70, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0, 2)) // Exact match
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizScore).isEqualTo(1)
        assertThat(vm.detailState.value.quizPassed).isTrue()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz multi-select partial answer is wrong`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion(
                "q1",
                questionType = "MULTI_SELECT",
                options = listOf("A", "B", "C", "D"),
                correctIndices = listOf(0, 2) // A and C are correct
            )
        )
        val training = createTraining(quizPassThreshold = 70, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0)) // Only selected A, missing C
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizScore).isEqualTo(0)
        assertThat(vm.detailState.value.quizPassed).isFalse()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz multi-select extra answer is wrong`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion(
                "q1",
                questionType = "MULTI_SELECT",
                options = listOf("A", "B", "C", "D"),
                correctIndices = listOf(0, 2) // A and C are correct
            )
        )
        val training = createTraining(quizPassThreshold = 70, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0, 1, 2)) // Selected A, B, C — extra B
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizScore).isEqualTo(0)
        assertThat(vm.detailState.value.quizPassed).isFalse()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz true-false uses correctIndex`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion(
                "q1",
                questionType = "TRUE_FALSE",
                options = listOf("Verdadero", "Falso"),
                correctIndex = 0 // True is correct
            )
        )
        val training = createTraining(quizPassThreshold = 70, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(0 to setOf(0)) // Selected "Verdadero"
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizScore).isEqualTo(1)
        assertThat(vm.detailState.value.quizPassed).isTrue()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `submitQuiz mixed question types scores correctly`() = runTest {
        val vm = createViewModel()
        val questions = listOf(
            createQuestion("q1", questionType = "MULTIPLE_CHOICE", correctIndex = 0),
            createQuestion("q2", questionType = "TRUE_FALSE",
                options = listOf("Verdadero", "Falso"), correctIndex = 1),
            createQuestion("q3", questionType = "MULTI_SELECT",
                options = listOf("A", "B", "C"), correctIndices = listOf(0, 2))
        )
        val training = createTraining(quizPassThreshold = 60, questions = questions)

        setDetailState(vm, TrainingDetailState(
            training = training,
            showQuiz = true,
            quizAnswers = mapOf(
                0 to setOf(0),     // MC: correct
                1 to setOf(0),     // TF: wrong (chose Verdadero, answer is Falso)
                2 to setOf(0, 2)   // MS: correct (exact match)
            )
        ))

        vm.submitQuiz()
        assertThat(vm.detailState.value.quizScore).isEqualTo(2) // 2 out of 3
        assertThat(vm.detailState.value.quizPassed).isTrue() // 66% >= 60%

        vm.viewModelScope.cancel()
    }

    // ===== ATTEMPT LIMITS =====

    @Test
    fun `retryQuiz increments attemptNumber`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(quizMaxAttempts = 0, questions = listOf(createQuestion())),
            isQuizSubmitted = true,
            attemptNumber = 1
        ))

        vm.retryQuiz()
        assertThat(vm.detailState.value.attemptNumber).isEqualTo(2)
        assertThat(vm.detailState.value.isQuizSubmitted).isFalse()
        assertThat(vm.detailState.value.quizAnswers).isEmpty()
        assertThat(vm.detailState.value.currentQuestionIndex).isEqualTo(0)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `canRetryQuiz false when maxAttempts reached`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(quizMaxAttempts = 2, questions = listOf(createQuestion())),
            attemptNumber = 2 // Already at max
        ))

        assertThat(vm.canRetryQuiz()).isFalse()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `canRetryQuiz true when under maxAttempts`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(quizMaxAttempts = 3, questions = listOf(createQuestion())),
            attemptNumber = 2
        ))

        assertThat(vm.canRetryQuiz()).isTrue()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `canRetryQuiz true when unlimited (0)`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(quizMaxAttempts = 0, questions = listOf(createQuestion())),
            attemptNumber = 99
        ))

        assertThat(vm.canRetryQuiz()).isTrue()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `retryQuiz does nothing when maxAttempts reached`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(quizMaxAttempts = 1, questions = listOf(createQuestion())),
            isQuizSubmitted = true,
            attemptNumber = 1
        ))

        vm.retryQuiz()
        // Should NOT have reset because canRetryQuiz is false
        assertThat(vm.detailState.value.isQuizSubmitted).isTrue()
        assertThat(vm.detailState.value.attemptNumber).isEqualTo(1)

        vm.viewModelScope.cancel()
    }

    // ===== REVIEW MODE =====

    @Test
    fun `showReview sets showQuizReview state`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = listOf(createQuestion())),
            isQuizSubmitted = true
        ))

        vm.showReview()
        assertThat(vm.detailState.value.showQuizReview).isTrue()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(0)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `exitReview clears showQuizReview`() {
        val vm = createViewModel()

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = listOf(createQuestion())),
            showQuizReview = true,
            reviewQuestionIndex = 2
        ))

        vm.exitReview()
        assertThat(vm.detailState.value.showQuizReview).isFalse()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `nextReviewQuestion advances reviewQuestionIndex`() {
        val vm = createViewModel()
        val questions = listOf(createQuestion("q1"), createQuestion("q2"), createQuestion("q3"))

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = questions),
            showQuizReview = true,
            reviewQuestionIndex = 0
        ))

        vm.nextReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(1)

        vm.nextReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(2)

        // At last, should stay
        vm.nextReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(2)

        vm.viewModelScope.cancel()
    }

    @Test
    fun `previousReviewQuestion decrements reviewQuestionIndex`() {
        val vm = createViewModel()
        val questions = listOf(createQuestion("q1"), createQuestion("q2"), createQuestion("q3"))

        setDetailState(vm, TrainingDetailState(
            training = createTraining(questions = questions),
            showQuizReview = true,
            reviewQuestionIndex = 2
        ))

        vm.previousReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(1)

        vm.previousReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(0)

        // At 0, should stay
        vm.previousReviewQuestion()
        assertThat(vm.detailState.value.reviewQuestionIndex).isEqualTo(0)

        vm.viewModelScope.cancel()
    }

    // ===== API INTEGRATION =====

    @Test
    fun `fetchTrainings populates listState`() = runTest {
        val vm = createViewModel()
        val trainings = listOf(createTraining())

        coEvery { apiService.getTrainings() } returns Response.success(
            TrainingsListResponse(success = true, data = trainings)
        )
        coEvery { apiService.getTrainingProgress(any()) } returns Response.success(
            TrainingProgressListResponse(success = true, data = emptyList())
        )

        vm.fetchTrainings()
        // Dispatchers.IO coroutine — wait for it
        Thread.sleep(500)

        assertThat(vm.listState.value.trainings).hasSize(1)
        assertThat(vm.listState.value.isLoading).isFalse()
        assertThat(vm.listState.value.error).isNull()

        vm.viewModelScope.cancel()
    }

    @Test
    fun `selectTraining loads detail and sets attemptNumber`() = runTest {
        val vm = createViewModel()
        val training = createTraining(questions = listOf(createQuestion()))

        coEvery { apiService.getTrainingDetail("training-1") } returns Response.success(
            TrainingDetailResponse(success = true, data = training)
        )
        coEvery { apiService.getTrainingProgress("staff-1") } returns Response.success(
            TrainingProgressListResponse(success = true, data = listOf(
                TrainingProgressDto(
                    id = "progress-1",
                    trainingModuleId = "training-1",
                    staffId = "staff-1",
                    lastStepViewed = 0,
                    isCompleted = false,
                    attemptNumber = 3
                )
            ))
        )
        // Also mock updateTrainingProgress (called by saveProgress)
        coEvery { apiService.updateTrainingProgress(any(), any()) } returns Response.success(
            TrainingProgressResponse(
                success = true,
                data = TrainingProgressDto(
                    id = "progress-1",
                    trainingModuleId = "training-1",
                    staffId = "staff-1",
                    lastStepViewed = 0,
                    isCompleted = false
                )
            )
        )

        vm.selectTraining("training-1")
        // Dispatchers.IO coroutine — wait for it
        Thread.sleep(500)

        assertThat(vm.detailState.value.training).isNotNull()
        assertThat(vm.detailState.value.attemptNumber).isEqualTo(3)
        assertThat(vm.detailState.value.isLoading).isFalse()

        vm.viewModelScope.cancel()
    }

    // ===== HELPERS =====

    private fun setDetailState(vm: TrainingViewModel, state: TrainingDetailState) {
        val field = TrainingViewModel::class.java.getDeclaredField("_detailState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<TrainingDetailState>
        stateFlow.value = state
    }
}
