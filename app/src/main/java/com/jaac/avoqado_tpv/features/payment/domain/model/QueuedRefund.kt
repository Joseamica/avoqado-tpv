package com.jaac.avoqado_tpv.features.payment.domain.model

import com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import java.math.BigDecimal

/**
 * Un reembolso que el SDK YA aprobó y que todavía no quedó registrado en el servidor.
 *
 * Es el gemelo de [QueuedPayment] para el carril de las devoluciones. La diferencia que importa:
 * cuando esto existe, **el dinero ya salió del cajón**. No es una intención de cobrar que se puede
 * abandonar — es un hecho consumado que falta anotar.
 */
data class QueuedRefund(
    /** == la `idempotencyKey` que viaja al servidor, y la PK de la fila. */
    val idempotencyKey: String,
    val venueId: String,
    val staffId: String,
    val processor: ProcessorType,
    val originalPaymentId: String,
    val originalOrderId: String?,
    val amount: BigDecimal,
    val originalTotalAmount: BigDecimal,
    val tipRefundCents: Int?,
    val isPartialRefund: Boolean,
    val refundReason: RefundReason,
    val merchantAccountId: String,
    val blumonSerialNumber: String,
    val originalOperationNumber: Int,
    /** 🔴 Del SDK, guardado tal cual. Ver [PendingRefundEntity] sobre por qué no se recalcula. */
    val authorizationNumber: String,
    /** 🔴 Del SDK, guardado tal cual. */
    val referenceNumber: String,
    val maskedPan: String?,
    val cardBrand: String?,
    val entryMode: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val syncStatus: String = PendingRefundEntity.SYNC_STATUS_PENDING,
    val claimToken: String? = null,
    val claimedAt: Long? = null,
    val permanent: Boolean = false,
    val acknowledged: Boolean = false,
    /** Quién reconoció el rechazo y cuándo (auditoría F9): evidencia durable del «Ya lo vi». */
    val acknowledgedBy: String? = null,
    val acknowledgedAt: Long? = null,
)

/**
 * Fila → dominio.
 *
 * ⚠️ `refundReason` se guarda como `String` y puede venir de una versión anterior de la app con un
 * valor que este enum ya no tiene. Cae a [RefundReason.OTHER] en vez de reventar: perder el MOTIVO
 * de un reembolso es una molestia; perder el reembolso entero por no poder leer su motivo sería
 * exactamente el defecto que esta cola viene a arreglar.
 */
fun PendingRefundEntity.toDomain(): QueuedRefund = QueuedRefund(
    idempotencyKey = idempotencyKey,
    venueId = venueId,
    staffId = staffId,
    processor = if (processor == PendingRefundEntity.PROCESSOR_ANGELPAY) ProcessorType.ANGELPAY else ProcessorType.BLUMON,
    originalPaymentId = originalPaymentId,
    originalOrderId = originalOrderId,
    amount = BigDecimal(amount),
    originalTotalAmount = BigDecimal(originalTotalAmount),
    tipRefundCents = tipRefundCents,
    isPartialRefund = isPartialRefund,
    refundReason = runCatching { RefundReason.valueOf(refundReason) }.getOrDefault(RefundReason.OTHER),
    merchantAccountId = merchantAccountId,
    blumonSerialNumber = blumonSerialNumber,
    originalOperationNumber = originalOperationNumber,
    authorizationNumber = authorizationNumber,
    referenceNumber = referenceNumber,
    maskedPan = maskedPan,
    cardBrand = cardBrand,
    entryMode = entryMode,
    createdAt = createdAt,
    retryCount = retryCount,
    lastError = lastError,
    syncStatus = syncStatus,
    claimToken = claimToken,
    claimedAt = claimedAt,
    permanent = permanent,
    acknowledged = acknowledged,
    acknowledgedBy = acknowledgedBy,
    acknowledgedAt = acknowledgedAt,
)

/** Dominio → fila. `BigDecimal` se guarda como `String`: nunca `Float`. */
fun QueuedRefund.toEntity(): PendingRefundEntity = PendingRefundEntity(
    idempotencyKey = idempotencyKey,
    venueId = venueId,
    staffId = staffId,
    processor = if (processor == ProcessorType.ANGELPAY) {
        PendingRefundEntity.PROCESSOR_ANGELPAY
    } else {
        PendingRefundEntity.PROCESSOR_BLUMON
    },
    originalPaymentId = originalPaymentId,
    originalOrderId = originalOrderId,
    amount = amount.toPlainString(),
    originalTotalAmount = originalTotalAmount.toPlainString(),
    tipRefundCents = tipRefundCents,
    isPartialRefund = isPartialRefund,
    refundReason = refundReason.name,
    merchantAccountId = merchantAccountId,
    blumonSerialNumber = blumonSerialNumber,
    originalOperationNumber = originalOperationNumber,
    authorizationNumber = authorizationNumber,
    referenceNumber = referenceNumber,
    maskedPan = maskedPan,
    cardBrand = cardBrand,
    entryMode = entryMode,
    createdAt = createdAt,
    retryCount = retryCount,
    lastError = lastError,
    syncStatus = syncStatus,
    claimToken = claimToken,
    claimedAt = claimedAt,
    permanent = permanent,
    acknowledged = acknowledged,
    acknowledgedBy = acknowledgedBy,
    acknowledgedAt = acknowledgedAt,
)
