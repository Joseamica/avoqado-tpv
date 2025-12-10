package com.jaac.avoqado_tpv.features.authentication.domain.models

/**
 * Venue Status Enum
 *
 * Represents the operational status of a venue.
 * Maps to backend VenueStatus enum in avoqado-server.
 *
 * Status Lifecycle:
 * - LIVE_DEMO → Demo venue for sales presentations
 * - TRIAL → Trial period (limited features)
 * - ONBOARDING → Initial setup phase
 * - PENDING_ACTIVATION → Awaiting KYC/payment processor setup
 * - ACTIVE → Fully operational venue
 * - SUSPENDED → Temporarily suspended (payment issues, policy violations)
 * - ADMIN_SUSPENDED → Suspended by Avoqado admin
 * - CLOSED → Permanently closed venue
 *
 * @see OPERATIONAL - Set of statuses where venue can process payments
 * @see DEMO - Set of demo/trial statuses
 */
enum class VenueStatus {
    LIVE_DEMO,
    TRIAL,
    ONBOARDING,
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    ADMIN_SUSPENDED,
    CLOSED;

    companion object {
        /**
         * Parse status from backend string
         *
         * @param value Backend status string (nullable)
         * @return VenueStatus enum, defaults to ACTIVE if unrecognized
         */
        fun fromString(value: String?): VenueStatus {
            return entries.find { it.name == value } ?: ACTIVE
        }

        /**
         * Operational statuses - venue can process payments
         */
        val OPERATIONAL = setOf(LIVE_DEMO, TRIAL, ONBOARDING, PENDING_ACTIVATION, ACTIVE)

        /**
         * Demo statuses - venue is in demo/trial mode
         */
        val DEMO = setOf(LIVE_DEMO, TRIAL)

        /**
         * Check if status allows venue operations
         *
         * @param status VenueStatus to check
         * @return true if venue can process payments
         */
        fun isOperational(status: VenueStatus): Boolean = status in OPERATIONAL

        /**
         * Check if status is demo/trial
         *
         * @param status VenueStatus to check
         * @return true if venue is in demo or trial mode
         */
        fun isDemo(status: VenueStatus): Boolean = status in DEMO

        /**
         * Get user-friendly status label (Spanish)
         *
         * @param status VenueStatus to label
         * @return Localized status label
         */
        fun getLabel(status: VenueStatus): String = when (status) {
            LIVE_DEMO -> "Demo"
            TRIAL -> "Prueba"
            ONBOARDING -> "Configurando"
            PENDING_ACTIVATION -> "Pendiente"
            ACTIVE -> "Activo"
            SUSPENDED -> "Suspendido"
            ADMIN_SUSPENDED -> "Suspendido"
            CLOSED -> "Cerrado"
        }
    }
}
