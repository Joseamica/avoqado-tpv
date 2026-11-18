package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Minimal venue details for receipt/header printing.
 *
 * Maps to GET /tpv/venues/{venueId} (full venue object).
 * We only parse the fields needed by the TPV.
 */
data class VenueDetailsDto(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("legalName")
    val legalName: String? = null,

    @SerializedName("rfc")
    val rfc: String? = null,

    @SerializedName("address")
    val address: String? = null,

    @SerializedName("city")
    val city: String? = null,

    @SerializedName("state")
    val state: String? = null,

    @SerializedName("zipCode")
    val zipCode: String? = null,

    @SerializedName("logo")
    val logo: String? = null
)
