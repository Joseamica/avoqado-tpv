package com.jaac.avoqado_tpv.features.payment.data.api

import com.jaac.avoqado_tpv.features.payment.data.dto.FastPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.OrderPaymentRequest
import com.jaac.avoqado_tpv.features.payment.data.dto.PaymentResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit service interface para payment endpoints del backend.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (producción)
 *              https://unmistrustful-marla-unvermiculated.ngrok-free.dev/api/v1/ (desarrollo)
 *
 * **Autenticación:** Todas las requests requieren Bearer token en header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 *
 * **Endpoints disponibles:**
 * 1. POST /tpv/venues/{venueId}/fast - Fast payment sin orden
 * 2. POST /tpv/venues/{venueId}/orders/{orderId} - Payment para orden existente
 *
 * **Rate Limiting:**
 * - Producción: 1000 requests/hora
 * - Desarrollo: 10,000 requests/hora
 *
 * **Error Codes:**
 * - 401: Token inválido o expirado → Renovar token
 * - 403: Sin permisos (payments:create) → Mostrar error al usuario
 * - 404: Venue o Order no encontrado → Verificar IDs
 * - 429: Rate limit excedido → Retry después de 60s
 * - 500: Error interno del servidor → Retry con backoff
 */
interface PaymentApiService {
    /**
     * Graba un fast payment (sin orden previa).
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/fast
     *
     * **Comportamiento del backend:**
     * 1. Crea una orden virtual con orderNumber "FAST-{timestamp}"
     * 2. Crea el Payment vinculado a esa orden
     * 3. Genera DigitalReceipt automáticamente
     * 4. Crea VenueTransaction para settlement tracking
     * 5. Crea TransactionCost para tracking de fees
     * 6. Emite evento Socket.IO para dashboard real-time
     *
     * **Request Body:** FastPaymentRequest
     * ```json
     * {
     *   "amount": 5000,
     *   "tip": 500,
     *   "status": "COMPLETED",
     *   "method": "CREDIT_CARD",
     *   "source": "AVOQADO_TPV",
     *   "splitType": "FULLPAYMENT",
     *   "staffId": "staff_xxx",
     *   "authorizationNumber": "502511",
     *   "referenceNumber": "000000188231",
     *   "maskedPan": "411111******1111",
     *   "cardBrand": "VISA",
     *   "entryMode": "CHIP",
     *   "currency": "MXN",
     *   "isInternational": false
     * }
     * ```
     *
     * **Success Response (201):** PaymentResponse
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "clxxx...",
     *     "amount": 50.00,
     *     "tipAmount": 5.00,
     *     "authorizationNumber": "502511",
     *     "digitalReceipt": {
     *       "receiptUrl": "https://api.avoqado.io/api/v1/public/receipt/key_123"
     *     }
     *   }
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid request data (validation error)
     * - 401: Unauthorized (token missing or expired)
     * - 403: Forbidden (no payments:create permission)
     * - 404: Venue not found
     * - 429: Too many requests
     * - 500: Internal server error
     *
     * @param venueId ID del venue donde se procesa el pago
     * @param request Datos del fast payment (amount, tip, card details)
     * @return Response con PaymentResponse o error HTTP
     */
    @POST("tpv/venues/{venueId}/fast")
    suspend fun recordFastPayment(
        @Path("venueId") venueId: String,
        @Body request: FastPaymentRequest,
    ): Response<PaymentResponse>

    /**
     * Graba un payment para una orden existente.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/orders/{orderId}
     *
     * **Comportamiento del backend:**
     * 1. Valida que la orden existe y pertenece al venue
     * 2. Verifica que la orden no esté ya pagada completamente
     * 3. Crea el Payment vinculado a la orden
     * 4. Actualiza order.paymentStatus (PENDING → PARTIALLY_PAID → PAID)
     * 5. Si la orden queda PAID, deduce inventory automáticamente
     * 6. Genera DigitalReceipt
     * 7. Emite evento Socket.IO
     *
     * **NOTA:** Este endpoint NO se usa todavía (no hay create order).
     * Está aquí para cuando se implemente la feature.
     *
     * **Request Body:** OrderPaymentRequest
     * ```json
     * {
     *   "venueId": "venue_xxx",
     *   "amount": 5000,
     *   "tip": 500,
     *   "status": "COMPLETED",
     *   "method": "CREDIT_CARD",
     *   "source": "AVOQADO_TPV",
     *   "splitType": "FULLPAYMENT",
     *   "staffId": "staff_xxx",
     *   "paidProductsId": ["prod_1", "prod_2"],
     *   "authorizationNumber": "502511",
     *   "referenceNumber": "000000188231",
     *   "maskedPan": "411111******1111",
     *   "cardBrand": "VISA",
     *   "entryMode": "CHIP"
     * }
     * ```
     *
     * **Success Response (201):** PaymentResponse (idéntico a fast payment)
     *
     * **Error Responses:**
     * - 400: Invalid request data or order already fully paid
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Venue or Order not found
     * - 409: Conflict (concurrent payment update)
     * - 429: Too many requests
     * - 500: Internal server error
     *
     * @param venueId ID del venue donde se procesa el pago
     * @param orderId ID de la orden existente
     * @param request Datos del payment (amount, tip, card details)
     * @return Response con PaymentResponse o error HTTP
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun recordOrderPayment(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body request: OrderPaymentRequest,
    ): Response<PaymentResponse>
}
