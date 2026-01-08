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
    val attendance: ModuleAttendanceDto? = null
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
    val showStockCounts: Boolean? = null
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
 * API Response wrapper for modules endpoint.
 */
data class ModulesApiResponse(
    @SerializedName("modules")
    val modules: List<VenueModuleDto>
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
    attendance = attendance?.toDomain() ?: ModuleAttendance()
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
    showStockCounts = showStockCounts ?: true
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

// ===== Mappers: Domain → DTO (for caching) =====

fun VenueModule.toDto(): VenueModuleDto = VenueModuleDto(
    code = moduleCode,
    config = config.toDto()
)

fun ModuleConfig.toDto(): ModuleConfigDto = ModuleConfigDto(
    labels = labels.toDto(),
    features = features.toDto(),
    ui = ui.toDto(),
    attendance = attendance.toDto()
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
    showStockCounts = showStockCounts
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
