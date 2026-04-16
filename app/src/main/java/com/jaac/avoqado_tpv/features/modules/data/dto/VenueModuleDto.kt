package com.jaac.avoqado_tpv.features.modules.data.dto

import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.features.modules.domain.model.*

/**
 * DTO for VenueModule from backend API.
 *
 * Backend endpoint: GET /tpv/modules
 * Response format:
 * ```json
 * {
 *   "modules": [
 *     {
 *       "code": "SERIALIZED_INVENTORY",
 *       "config": {
 *         "labels": { "item": "SIM", ... },
 *         "ui": { "simplifiedOrderFlow": true, ... },
 *         "attendance": { "requireClockInPhoto": true, ... }
 *       }
 *     }
 *   ]
 * }
 * ```
 */
data class VenueModuleDto(
    @SerializedName("code")
    val code: String,

    @SerializedName("config")
    val config: ModuleConfigDto
)

data class ModuleConfigDto(
    @SerializedName("labels")
    val labels: ModuleLabelsDto? = null,

    @SerializedName("features")
    val features: ModuleFeaturesDto? = null,

    @SerializedName("ui")
    val ui: ModuleUiDto? = null,

    @SerializedName("attendance")
    val attendance: ModuleAttendanceDto? = null,

    @SerializedName("salesGoal")
    val salesGoal: ModuleSalesGoalDto? = null
)

data class ModuleLabelsDto(
    @SerializedName("item")
    val item: String? = null,

    @SerializedName("barcode")
    val barcode: String? = null,

    @SerializedName("category")
    val category: String? = null,

    @SerializedName("scan")
    val scan: String? = null,

    @SerializedName("register")
    val register: String? = null
)

data class ModuleFeaturesDto(
    @SerializedName("allowUnregisteredSale")
    val allowUnregisteredSale: Boolean? = null,

    @SerializedName("requireCategorySelection")
    val requireCategorySelection: Boolean? = null,

    @SerializedName("showStockCounts")
    val showStockCounts: Boolean? = null,

    @SerializedName("enablePortabilidad")
    val enablePortabilidad: Boolean? = null
)

data class ModuleUiDto(
    @SerializedName("simplifiedOrderFlow")
    val simplifiedOrderFlow: Boolean? = null,

    @SerializedName("skipTipScreen")
    val skipTipScreen: Boolean? = null,

    @SerializedName("skipReviewScreen")
    val skipReviewScreen: Boolean? = null,

    @SerializedName("enableShifts")
    val enableShifts: Boolean? = null
)

data class ModuleAttendanceDto(
    @SerializedName("requireClockInPhoto")
    val requireClockInPhoto: Boolean? = null,

    @SerializedName("requireClockInGps")
    val requireClockInGps: Boolean? = null,

    @SerializedName("requireClockOutPhoto")
    val requireClockOutPhoto: Boolean? = null,

    @SerializedName("requireClockOutGps")
    val requireClockOutGps: Boolean? = null
)

/**
 * DTO for sales goal configuration.
 * Backend sends this when a client has configured sales targets for staff.
 */
data class ModuleSalesGoalDto(
    @SerializedName("goal")
    val goal: String? = null,  // BigDecimal as string from backend

    @SerializedName("goalType")
    val goalType: String? = null,  // "AMOUNT" or "QUANTITY" (defaults to AMOUNT)

    @SerializedName("period")
    val period: String? = null,  // "DAILY", "WEEKLY", "MONTHLY"

    @SerializedName("currentSales")
    val currentSales: String? = null,  // BigDecimal as string, pre-calculated by backend

    @SerializedName("staffId")
    val staffId: String? = null
)

/**
 * API Response wrapper for modules endpoint.
 */
data class ModulesApiResponse(
    @SerializedName("modules")
    val modules: List<VenueModuleDto>
)

/**
 * Request body for toggling a module ON/OFF (SUPERADMIN only).
 */
data class ToggleModuleRequest(
    @SerializedName("moduleCode")
    val moduleCode: String,

    @SerializedName("enabled")
    val enabled: Boolean
)

/**
 * Response from toggle module endpoint.
 */
data class ToggleModuleResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String? = null
)

// ===== Mappers: DTO → Domain =====

fun VenueModuleDto.toDomain(): VenueModule = VenueModule(
    id = code, // Use code as ID since backend doesn't return separate ID
    moduleCode = code,
    moduleName = code, // Use code as name
    config = config.toDomain(),
    active = true
)

fun ModuleConfigDto.toDomain(): ModuleConfig = ModuleConfig(
    labels = labels?.toDomain() ?: ModuleLabels(),
    features = features?.toDomain() ?: ModuleFeatures(),
    ui = ui?.toDomain() ?: ModuleUi(),
    attendance = attendance?.toDomain() ?: ModuleAttendance(),
    salesGoal = salesGoal?.toDomain()
)

fun ModuleLabelsDto.toDomain(): ModuleLabels = ModuleLabels(
    item = item ?: "Producto",
    barcode = barcode ?: "Código de Barras",
    category = category ?: "Categoría",
    scan = scan ?: "Escanear",
    register = register ?: "Registrar"
)

fun ModuleFeaturesDto.toDomain(): ModuleFeatures = ModuleFeatures(
    allowUnregisteredSale = allowUnregisteredSale ?: true,
    requireCategorySelection = requireCategorySelection ?: true,
    showStockCounts = showStockCounts ?: true,
    enablePortabilidad = enablePortabilidad ?: false
)

fun ModuleUiDto.toDomain(): ModuleUi = ModuleUi(
    simplifiedOrderFlow = simplifiedOrderFlow ?: false,
    skipTipScreen = skipTipScreen ?: false,
    skipReviewScreen = skipReviewScreen ?: false,
    enableShifts = enableShifts ?: true
)

fun ModuleAttendanceDto.toDomain(): ModuleAttendance = ModuleAttendance(
    requireClockInPhoto = requireClockInPhoto ?: false,
    requireClockInGps = requireClockInGps ?: false,
    requireClockOutPhoto = requireClockOutPhoto ?: false,
    requireClockOutGps = requireClockOutGps ?: false
)

fun ModuleSalesGoalDto.toDomain(): ModuleSalesGoal? {
    // Goal is required, if not present return null
    val goalAmount = goal?.toBigDecimalOrNull() ?: return null

    return ModuleSalesGoal(
        goal = goalAmount,
        goalType = when (goalType?.uppercase()) {
            "QUANTITY" -> SalesGoalType.QUANTITY
            else -> SalesGoalType.AMOUNT
        },
        period = when (period?.uppercase()) {
            "WEEKLY" -> SalesGoalPeriod.WEEKLY
            "MONTHLY" -> SalesGoalPeriod.MONTHLY
            else -> SalesGoalPeriod.DAILY
        },
        currentSales = currentSales?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
        staffId = staffId
    )
}

// ===== Mappers: Domain → DTO (for caching) =====

fun VenueModule.toDto(): VenueModuleDto = VenueModuleDto(
    code = moduleCode,
    config = config.toDto()
)

fun ModuleConfig.toDto(): ModuleConfigDto = ModuleConfigDto(
    labels = labels.toDto(),
    features = features.toDto(),
    ui = ui.toDto(),
    attendance = attendance.toDto(),
    salesGoal = salesGoal?.toDto()
)

fun ModuleLabels.toDto(): ModuleLabelsDto = ModuleLabelsDto(
    item = item,
    barcode = barcode,
    category = category,
    scan = scan,
    register = register
)

fun ModuleFeatures.toDto(): ModuleFeaturesDto = ModuleFeaturesDto(
    allowUnregisteredSale = allowUnregisteredSale,
    requireCategorySelection = requireCategorySelection,
    showStockCounts = showStockCounts,
    enablePortabilidad = enablePortabilidad
)

fun ModuleUi.toDto(): ModuleUiDto = ModuleUiDto(
    simplifiedOrderFlow = simplifiedOrderFlow,
    skipTipScreen = skipTipScreen,
    skipReviewScreen = skipReviewScreen,
    enableShifts = enableShifts
)

fun ModuleAttendance.toDto(): ModuleAttendanceDto = ModuleAttendanceDto(
    requireClockInPhoto = requireClockInPhoto,
    requireClockInGps = requireClockInGps,
    requireClockOutPhoto = requireClockOutPhoto,
    requireClockOutGps = requireClockOutGps
)

fun ModuleSalesGoal.toDto(): ModuleSalesGoalDto = ModuleSalesGoalDto(
    goal = goal.toPlainString(),
    goalType = goalType.name,
    period = period.name,
    currentSales = currentSales.toPlainString(),
    staffId = staffId
)
