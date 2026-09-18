package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.PaymentResult

/**
 * Traduce el `PaymentResult` REAL del SDK de AngelPay a [ResultadoDelSdk], lo único que mira la regla del 1.0.19
 * ([AngelPayOutcomeClassifier.decidirSegunElSdk119]).
 *
 * Vive aparte del clasificador a propósito: ése es puro y sin tipos del SDK (el AAR es `compileOnly` en las variantes
 * PAX), y éste es el único punto que toca `PaymentResult`. Las pruebas de la regla pasan por AQUÍ con resultados reales
 * del AAR —no con mocks relajados, que devuelven `""` y `false` y esconderían un campo cruzado—.
 *
 * `status` va en `runCatching`: leer un campo de un resultado del SDK jamás puede tumbar la clasificación de un cobro.
 */
fun PaymentResult.paraLaReglaDelSdk(versionSdk: String?): ResultadoDelSdk = ResultadoDelSdk(
    versionSdk = versionSdk,
    approved = approved,
    authorizationAttempted = authorizationAttempted,
    status = runCatching { status.name }.getOrNull(),
    codigoSdk = callResult?.code,
    message = message,
    integratorReference = integratorReference,
    authCode = authCode,
    code = code,
    description = description,
    cardBrand = cardBrand,
    cardBin = cardBin,
    cardLast4 = cardLast4,
    reference = reference,
    affiliation = affiliation,
    folio = folio,
    transactionDate = transactionDate,
    issuingBank = issuingBank,
    cardType = cardType,
    cardEntryMode = cardEntryMode,
    aid = aid,
    arqc = arqc,
    applicationLabel = applicationLabel,
    tvr = tvr,
    tsi = tsi,
    authentication = authentication,
)
