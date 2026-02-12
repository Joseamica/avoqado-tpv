package com.jaac.avoqado_tpv.features.modules.domain.model

/**
 * VenueModule represents a feature module enabled for a venue.
 *
 * Modules are configuration-driven features that allow the TPV to adapt
 * to different industries (telecom, jewelry, retail) without hardcoded checks.
 *
 * Example: SERIALIZED_INVENTORY module for PlayTelecom
 * - Labels: "SIM" instead of "Product", "ICCID" instead of "Barcode"
 * - UI: Simplified flow (single "Vender" button)
 * - Attendance: GPS + Photo required at clock-in
 */
data class VenueModule(
    val id: String,
    val moduleCode: String,          // e.g., "SERIALIZED_INVENTORY"
    val moduleName: String,          // e.g., "Inventario Serializado"
    val config: ModuleConfig,        // Merged config (default + preset + custom)
    val active: Boolean = true
)

/**
 * ModuleConfig contains all configurable settings for a module.
 * This is the merged result of defaultConfig + presetConfig + customConfig from backend.
 */
data class ModuleConfig(
    val labels: ModuleLabels = ModuleLabels(),
    val features: ModuleFeatures = ModuleFeatures(),
    val ui: ModuleUi = ModuleUi(),
    val attendance: ModuleAttendance = ModuleAttendance(),
    val salesGoal: ModuleSalesGoal? = null  // Optional: Staff sales goals
)

/**
 * Industry-specific labels for UI elements.
 * Allows adapting terminology per business type.
 */
data class ModuleLabels(
    val item: String = "Producto",           // "SIM", "Pieza", "Dispositivo"
    val barcode: String = "Código de Barras", // "ICCID", "Certificado", "Número de Serie"
    val category: String = "Categoría",       // "Tipo de SIM", "Tipo de Piedra"
    val scan: String = "Escanear",            // "Escanear SIM"
    val register: String = "Registrar"        // "Alta de SIM"
)

/**
 * Feature toggles for module behavior.
 */
data class ModuleFeatures(
    val allowUnregisteredSale: Boolean = true,
    val requireCategorySelection: Boolean = true,
    val showStockCounts: Boolean = true,
    val enablePortabilidad: Boolean = false
)

/**
 * UI configuration for payment/order flow.
 * Controls which screens are shown/skipped.
 */
data class ModuleUi(
    val simplifiedOrderFlow: Boolean = false,  // Single "Vender" button on welcome
    val skipTipScreen: Boolean = false,        // No tips for telecom
    val skipReviewScreen: Boolean = false,     // Skip order review, go direct to payment
    val enableShifts: Boolean = true           // Shift system on/off
)

/**
 * Attendance/timeclock configuration.
 * Controls anti-fraud measures for clock-in/out.
 */
data class ModuleAttendance(
    val requireClockInPhoto: Boolean = false,  // Photo capture at clock-in
    val requireClockInGps: Boolean = false,    // GPS location at clock-in
    val requireClockOutPhoto: Boolean = false, // Photo capture at clock-out
    val requireClockOutGps: Boolean = false    // GPS location at clock-out
)

/**
 * Sales goal configuration for staff performance tracking.
 * Allows setting sales targets per staff member with configurable periods.
 *
 * @property goal Target sales amount (AMOUNT) or unit count (QUANTITY) for the period
 * @property goalType Type of goal: AMOUNT (currency) or QUANTITY (units sold)
 * @property period Time period for the goal (DAILY, WEEKLY, MONTHLY)
 * @property currentSales Current sales achieved (amount or count depending on goalType)
 * @property staffId Optional staff ID if goal is per-staff (null = same for all)
 */
data class ModuleSalesGoal(
    val goal: java.math.BigDecimal,
    val goalType: SalesGoalType = SalesGoalType.AMOUNT,
    val period: SalesGoalPeriod = SalesGoalPeriod.DAILY,
    val currentSales: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val staffId: String? = null
)

/**
 * Type of sales goal target.
 */
enum class SalesGoalType {
    AMOUNT,    // Currency target (e.g., $10,000)
    QUANTITY   // Unit count target (e.g., 50 products sold)
}

/**
 * Time period for sales goals.
 */
enum class SalesGoalPeriod {
    DAILY,    // Reset every day
    WEEKLY,   // Reset every week (Monday)
    MONTHLY   // Reset every month (1st)
}
