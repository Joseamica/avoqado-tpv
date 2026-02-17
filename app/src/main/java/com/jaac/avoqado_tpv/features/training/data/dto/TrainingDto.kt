package com.jaac.avoqado_tpv.features.training.data.dto

import com.google.gson.annotations.SerializedName

/**
 * Training Module DTO
 *
 * Matches backend TrainingModule model in:
 * `avoqado-server/prisma/schema.prisma` → TrainingModule
 */
data class TrainingModuleDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("description")
    val description: String,

    @SerializedName("coverImageUrl")
    val coverImageUrl: String? = null,

    @SerializedName("category")
    val category: String, // VENTAS | INVENTARIO | PAGOS | ATENCION_CLIENTE | GENERAL

    @SerializedName("difficulty")
    val difficulty: String, // BASIC | INTERMEDIATE

    @SerializedName("estimatedMinutes")
    val estimatedMinutes: Int,

    @SerializedName("isRequired")
    val isRequired: Boolean,

    @SerializedName("position")
    val position: Int,

    @SerializedName("status")
    val status: String, // DRAFT | PUBLISHED

    @SerializedName("featureTags")
    val featureTags: List<String> = emptyList(),

    @SerializedName("steps")
    val steps: List<TrainingStepDto>? = null,

    @SerializedName("quizQuestions")
    val quizQuestions: List<TrainingQuizQuestionDto>? = null,

    @SerializedName("_count")
    val count: TrainingCountDto? = null,

    @SerializedName("createdAt")
    val createdAt: String? = null
)

data class TrainingStepDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("stepNumber")
    val stepNumber: Int,

    @SerializedName("title")
    val title: String,

    @SerializedName("instruction")
    val instruction: String,

    @SerializedName("mediaType")
    val mediaType: String, // IMAGE | VIDEO

    @SerializedName("mediaUrl")
    val mediaUrl: String? = null,

    @SerializedName("thumbnailUrl")
    val thumbnailUrl: String? = null,

    @SerializedName("tipText")
    val tipText: String? = null
)

data class TrainingQuizQuestionDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("question")
    val question: String,

    @SerializedName("options")
    val options: List<String>,

    @SerializedName("correctIndex")
    val correctIndex: Int,

    @SerializedName("position")
    val position: Int
)

data class TrainingCountDto(
    @SerializedName("steps")
    val steps: Int = 0,

    @SerializedName("quizQuestions")
    val quizQuestions: Int = 0
)

data class TrainingProgressDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("trainingModuleId")
    val trainingModuleId: String,

    @SerializedName("staffId")
    val staffId: String,

    @SerializedName("lastStepViewed")
    val lastStepViewed: Int,

    @SerializedName("isCompleted")
    val isCompleted: Boolean,

    @SerializedName("quizScore")
    val quizScore: Int? = null,

    @SerializedName("quizTotal")
    val quizTotal: Int? = null,

    @SerializedName("quizPassed")
    val quizPassed: Boolean? = null,

    @SerializedName("startedAt")
    val startedAt: String? = null,

    @SerializedName("completedAt")
    val completedAt: String? = null,

    @SerializedName("trainingModule")
    val trainingModule: TrainingModuleSummaryDto? = null
)

data class TrainingModuleSummaryDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("category")
    val category: String,

    @SerializedName("estimatedMinutes")
    val estimatedMinutes: Int
)

// ===== RESPONSE WRAPPERS =====

data class TrainingsListResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<TrainingModuleDto>
)

data class TrainingDetailResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: TrainingModuleDto
)

data class TrainingProgressListResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: List<TrainingProgressDto>
)

data class TrainingProgressResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: TrainingProgressDto
)

// ===== REQUEST =====

data class UpdateProgressRequest(
    @SerializedName("staffId")
    val staffId: String? = null,

    @SerializedName("lastStepViewed")
    val lastStepViewed: Int? = null,

    @SerializedName("isCompleted")
    val isCompleted: Boolean? = null,

    @SerializedName("quizScore")
    val quizScore: Int? = null,

    @SerializedName("quizTotal")
    val quizTotal: Int? = null,

    @SerializedName("quizPassed")
    val quizPassed: Boolean? = null
)
