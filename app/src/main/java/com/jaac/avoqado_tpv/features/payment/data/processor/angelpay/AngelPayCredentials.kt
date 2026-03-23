package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

data class AngelPayCredentials(
    val email: String,
    val password: String,
    val affiliation: String,
    val commerceToken: String
)
