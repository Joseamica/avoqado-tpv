package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import org.junit.Test
import java.math.BigDecimal

class QueuedPaymentTest {

    @Test
    fun `toPaymentContext maps cash queued payment merchant to null`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-1712660400",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("10.00"),
            tip = BigDecimal("1.50"),
            rating = 5,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            createdAt = System.currentTimeMillis()
        )

        val context = queued.toPaymentContext()

        assertThat(context.merchantAccountId).isNull()
    }

    @Test
    fun `toCardDetails returns CASH details for cash queued payment`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-KIOSK-1712660400",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("25.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO-CONFIRMADO",
            createdAt = System.currentTimeMillis()
        )

        val card = queued.toCardDetails()

        assertThat(card.isCash).isTrue()
        assertThat(card.toPaymentMethod()).isEqualTo("CASH")
        assertThat(card.entryMode).isEqualTo(CardEntryMode.MANUAL)
    }

    @Test
    fun `toPaymentContext keeps merchant for non-cash queued payment`() {
        val queued = QueuedPayment(
            referenceNumber = "195978383755",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("100.00"),
            tip = BigDecimal("10.00"),
            rating = 4,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "123456",
            createdAt = System.currentTimeMillis()
        )

        val context = queued.toPaymentContext()
        val card = queued.toCardDetails()

        assertThat(context.merchantAccountId).isEqualTo("merchant_cuid_123")
        assertThat(card.isCash).isFalse()
        assertThat(card.cardBrand).isEqualTo(CardBrand.VISA)
        assertThat(card.entryMode).isEqualTo(CardEntryMode.CHIP)
    }

    // ------------------------------------------------------------------
    // Processor-aware queue (2026-07-09) — AngelPay rows must rebuild the
    // EXACT context shape so RecordPaymentUseCase routes them like the
    // online path did (order vs fast recorder, processor tag, SIM metadata).
    // ------------------------------------------------------------------

    @Test
    fun `toPaymentContext rebuilds AngelPayPayment for ANGELPAY rows`() {
        val queued = QueuedPayment(
            referenceNumber = "195978383755",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("150.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "merchant_cuid_ap",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "OTHER",
            isInternational = false,
            authorizationNumber = "AUTH99",
            idempotencyKey = "uuid-123",
            processor = ProcessorType.ANGELPAY,
            orderId = "order-9",
            orderNumber = "SN00042",
            shiftId = "shift-7",
            isPortabilidad = true,
            serialNumbers = listOf("8952000000000000001"),
            createdAt = System.currentTimeMillis(),
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.AngelPayPayment::class.java)
        val angelPay = context as PaymentContext.AngelPayPayment
        assertThat(angelPay.orderId).isEqualTo("order-9")
        assertThat(angelPay.orderNumber).isEqualTo("SN00042")
        assertThat(angelPay.shiftId).isEqualTo("shift-7")
        assertThat(angelPay.referenceNumber).isEqualTo("195978383755")
        assertThat(angelPay.authorizationCode).isEqualTo("AUTH99")
        assertThat(angelPay.idempotencyKey).isEqualTo("uuid-123")
        assertThat(angelPay.isPortabilidad).isTrue()
        assertThat(angelPay.serialNumbers).containsExactly("8952000000000000001")
        // AngelPay entry mode must survive the round-trip as OTHER (not inventado CHIP)
        assertThat(angelPay.cardDetails?.entryMode).isEqualTo(CardEntryMode.OTHER)
    }

    // ------------------------------------------------------------------
    // Una fila Blumon de un cobro de ORDEN vuelve a SU orden (2026-09-07).
    // Antes toda fila que no fuera AngelPay se reproducía como FastPayment:
    // el servidor creaba una venta FAST nueva, sin artículos y sin
    // SaleVerification («venta sin SIM» en el dashboard de PlayTelecom,
    // 8 casos medidos jul–sep 2026) y la orden original quedaba SIN cobro.
    // ------------------------------------------------------------------

    @Test
    fun `una fila Blumon con orderId se reproduce como cobro de ORDEN, no como venta FAST`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-9f3a2c1e5b7d4a60",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("0.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            deviceSerialNumber = "AVQD-2840744206",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            idempotencyKey = "k-1",
            orderId = "order-9",
            orderNumber = "SN00396",
            shiftId = "shift-1",
            serialNumbers = listOf("8952140064479453293F"),
            createdAt = 1L,
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.OrderPayment::class.java)
        val order = context as PaymentContext.OrderPayment
        assertThat(order.orderId).isEqualTo("order-9")
        assertThat(order.idempotencyKey).isEqualTo("k-1")
        assertThat(order.merchantAccountId).isNull()          // efectivo: sin procesador
        assertThat(order.shiftId).isEqualTo("shift-1")
        assertThat(order.deviceSerialNumber).isEqualTo("AVQD-2840744206")
        assertThat(order.serialNumbers).containsExactly("8952140064479453293F")
    }

    @Test
    fun `una fila Blumon SIN orderId sigue siendo FAST (venta rapida encolada)`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-9f3a2c1e5b7d4a60",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("10.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            idempotencyKey = "k-2",
            createdAt = 1L,
        )

        assertThat(queued.toPaymentContext()).isInstanceOf(PaymentContext.FastPayment::class.java)
    }

    @Test
    fun `una fila Blumon de TARJETA con orderId tambien vuelve a su orden, con su merchant`() {
        val queued = QueuedPayment(
            referenceNumber = "000000188231",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("250.00"),
            tip = BigDecimal("25.00"),
            rating = null,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            deviceSerialNumber = "AVQD-2840744206",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            idempotencyKey = "k-3",
            orderId = "order-77",
            orderNumber = "0077",
            shiftId = "shift-1",
            createdAt = 1L,
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.OrderPayment::class.java)
        val order = context as PaymentContext.OrderPayment
        assertThat(order.orderId).isEqualTo("order-77")
        assertThat(order.merchantAccountId).isEqualTo("merchant_cuid_123")
        assertThat(order.blumonSerialNumber).isEqualTo("2841548417")
        // Limitación declarada: la fila no guarda el split; se reproduce como FULLPAYMENT.
        assertThat(order.splitType).isEqualTo(SplitType.FULLPAYMENT)
    }

    @Test
    fun `toPaymentContext keeps FastPayment shape for legacy rows without processor`() {
        val queued = QueuedPayment(
            referenceNumber = "000000188231",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("50.00"),
            tip = BigDecimal("5.00"),
            rating = null,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            createdAt = System.currentTimeMillis(),
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.FastPayment::class.java)
    }

    // ------------------------------------------------------------------
    // El SPLIT sobrevive a la cola (auditoría Codex P1-4, 2026-09-07).
    //
    // Una fila PERPRODUCT que se reproducía como FULLPAYMENT sin productos hacía que el
    // servidor NO creara la PaymentAllocation por artículo: otra terminal seguía viendo
    // el producto como no pagado y podía cobrarlo otra vez.
    // ------------------------------------------------------------------

    @Test
    fun `una fila PERPRODUCT se reproduce con su split y sus productos`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-9f3a2c1e5b7d4a60",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("120.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            idempotencyKey = "k-split-1",
            orderId = "order-9",
            orderNumber = "SN00396",
            splitType = SplitType.PERPRODUCT,
            paidProductIds = listOf("item-A", "item-B"),
            createdAt = 1L,
        )

        val order = queued.toPaymentContext() as PaymentContext.OrderPayment

        assertThat(order.splitType).isEqualTo(SplitType.PERPRODUCT)
        assertThat(order.paidProductIds).containsExactly("item-A", "item-B").inOrder()
    }

    @Test
    fun `una fila EQUALPARTS se reproduce con su reparto por personas`() {
        val queued = QueuedPayment(
            referenceNumber = "000000188231",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("50.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            orderId = "order-77",
            splitType = SplitType.EQUALPARTS,
            equalPartsPartySize = 4,
            equalPartsPayedFor = 1,
            createdAt = 1L,
        )

        val order = queued.toPaymentContext() as PaymentContext.OrderPayment

        assertThat(order.splitType).isEqualTo(SplitType.EQUALPARTS)
        assertThat(order.equalPartsPartySize).isEqualTo(4)
        assertThat(order.equalPartsPayedFor).isEqualTo(1)
    }

    @Test
    fun `una fila VIEJA sin split se reproduce como FULLPAYMENT, sin productos`() {
        // Las filas escritas antes de la v33 no tienen las columnas: el default las deja
        // exactamente donde estaban — pago completo, sin marca por producto.
        val queued = QueuedPayment(
            referenceNumber = "000000188232",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("250.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = null,
            cardBrand = null,
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            orderId = "order-88",
            createdAt = 1L,
        )

        val order = queued.toPaymentContext() as PaymentContext.OrderPayment

        assertThat(order.splitType).isEqualTo(SplitType.FULLPAYMENT)
        assertThat(order.paidProductIds).isEmpty()
        assertThat(order.equalPartsPartySize).isNull()
        assertThat(order.equalPartsPayedFor).isNull()
    }

    // ------------------------------------------------------------------
    // El RESPALDO a venta rápida no puede perder la prueba de venta
    // (2ª auditoría de Codex, 2026-09-07).
    //
    // Hay DOS ventas rápidas distintas y no se mezclan: la genérica (cobro sin orden, sin
    // seriales) y la del módulo de INVENTARIO SERIALIZADO — casi siempre de $0, que viaja con
    // `serialNumbers`/`isPortabilidad` y de la que el servidor crea la `SaleVerification` por su
    // camino genérico, sin un solo `if` de cliente. La rama FastPayment omitía esos tres campos
    // (más el turno): el dinero se registraba y la venta quedaba «sin SIM».
    // ------------------------------------------------------------------

    @Test
    fun `P1 el respaldo como venta rapida conserva la SIM, la portabilidad y el turno`() {
        val queued = QueuedPayment(
            referenceNumber = "CASH-1a2b3c4d5e6f7a8b",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("0.00"),
            tip = BigDecimal.ZERO,
            rating = null,
            merchantAccountId = "",
            blumonSerialNumber = "",
            deviceSerialNumber = "AVQD-2840744206",
            maskedPan = null,
            cardBrand = null,
            entryMode = "MANUAL",
            isInternational = false,
            authorizationNumber = "EFECTIVO",
            idempotencyKey = "k-sim",
            orderId = null, // el respaldo del worker es exactamente esta fila: copy(orderId = null)
            shiftId = "shift-1",
            isPortabilidad = true,
            serialNumbers = listOf("8952140064479453293F"),
            createdAt = 1L,
        )

        val context = queued.toPaymentContext()

        assertThat(context).isInstanceOf(PaymentContext.FastPayment::class.java)
        val fast = context as PaymentContext.FastPayment
        assertThat(fast.serialNumbers).containsExactly("8952140064479453293F")
        assertThat(fast.isPortabilidad).isTrue()
        assertThat(fast.shiftId).isEqualTo("shift-1")
    }

    @Test
    fun `P1 una venta rapida SIN seriales sigue viajando con la lista vacia`() {
        val queued = QueuedPayment(
            referenceNumber = "000000188231",
            venueId = "venue-1",
            staffId = "staff-1",
            amount = BigDecimal("150.00"),
            tip = BigDecimal("15.00"),
            rating = 5,
            merchantAccountId = "merchant_cuid_123",
            blumonSerialNumber = "2841548417",
            maskedPan = "411111******1111",
            cardBrand = "VISA",
            entryMode = "CHIP",
            isInternational = false,
            authorizationNumber = "502511",
            createdAt = 1L,
        )

        val fast = queued.toPaymentContext() as PaymentContext.FastPayment

        // Los negocios que no usan inventario serializado viajan igual que siempre: vacío y
        // portabilidad apagada. El respaldo copia los datos del contexto, no los inventa.
        assertThat(fast.serialNumbers).isEmpty()
        assertThat(fast.isPortabilidad).isFalse()
        assertThat(fast.shiftId).isNull()
    }

    // ------------------------------------------------------------------
    // desdeContexto(): la fila de la cola nace del contexto CONGELADO
    // (2ª auditoría de Codex, P1 parcial — 2026-09-07).
    //
    // Los tres sitios de encolado del PaymentViewModel armaban la fila leyendo estado VIVO
    // (`sessionSnapshot`, `_socketRequestId`, `_serialNumber`, `getOrderIdForFlow()`) DESPUÉS de
    // esperar al servidor. Un `resetPayment()` a media espera dejaba una fila de $0, sin orden,
    // sin llave y sin seriales… con la tarjeta YA cobrada. Esta función es la única forma de
    // armar esa fila: recibe el MISMO contexto que viajó al servidor y no puede leer nada más.
    // ------------------------------------------------------------------

    private fun contextoDeOrden() = PaymentContext.OrderPayment(
        venueId = "venue-1",
        staffId = "staff-kiosco",
        shiftId = "shift-1",
        orderId = "order-9",
        amount = BigDecimal("250.00"),
        tip = BigDecimal("25.00"),
        rating = 4,
        merchantAccountId = "merchant_cuid_123",
        blumonSerialNumber = "2841548417",
        deviceSerialNumber = "AVQD-2840744206",
        idempotencyKey = "k-viva",
        terminalPaymentRequestId = "req-77",
        splitType = SplitType.PERPRODUCT,
        paidProductIds = listOf("prod-1", "prod-2"),
        isPortabilidad = true,
        serialNumbers = listOf("8952140064479453293F"),
    )

    private val tarjeta = CardDetails(
        maskedPan = "411111******1111",
        cardBrand = CardBrand.VISA,
        entryMode = CardEntryMode.CHIP,
        isInternational = false,
    )

    @Test
    fun `P1 desdeContexto con OrderPayment conserva orden, llave, socket, seriales y split`() {
        val fila = QueuedPayment.desdeContexto(
            context = contextoDeOrden(),
            orderNumber = "0099",
            cardDetails = tarjeta,
            authorizationNumber = "502511",
            referenceNumber = "000000188231",
            error = RuntimeException("HTTP 500"),
            createdAt = 1_725_000_000_000L,
        )

        assertThat(fila.orderId).isEqualTo("order-9")
        assertThat(fila.orderNumber).isEqualTo("0099")
        assertThat(fila.idempotencyKey).isEqualTo("k-viva")
        assertThat(fila.terminalPaymentRequestId).isEqualTo("req-77")
        assertThat(fila.shiftId).isEqualTo("shift-1")
        assertThat(fila.staffId).isEqualTo("staff-kiosco")   // el MISMO que se le mandó al servidor
        assertThat(fila.amount).isEqualTo(BigDecimal("250.00"))
        assertThat(fila.tip).isEqualTo(BigDecimal("25.00"))
        assertThat(fila.rating).isEqualTo(4)
        assertThat(fila.merchantAccountId).isEqualTo("merchant_cuid_123")
        assertThat(fila.blumonSerialNumber).isEqualTo("2841548417")
        assertThat(fila.deviceSerialNumber).isEqualTo("AVQD-2840744206")
        assertThat(fila.serialNumbers).containsExactly("8952140064479453293F")
        assertThat(fila.isPortabilidad).isTrue()
        assertThat(fila.splitType).isEqualTo(SplitType.PERPRODUCT)
        assertThat(fila.paidProductIds).containsExactly("prod-1", "prod-2").inOrder()
        assertThat(fila.maskedPan).isEqualTo("411111******1111")
        assertThat(fila.cardBrand).isEqualTo("VISA")
        assertThat(fila.entryMode).isEqualTo("CHIP")
        assertThat(fila.authorizationNumber).isEqualTo("502511")
        assertThat(fila.referenceNumber).isEqualTo("000000188231")
        assertThat(fila.lastError).isEqualTo("HTTP 500")
        assertThat(fila.createdAt).isEqualTo(1_725_000_000_000L)
        assertThat(fila.syncStatus).isEqualTo(SyncStatus.PENDING)
        assertThat(fila.retryCount).isEqualTo(0)
        assertThat(fila.queueId).isEqualTo(0L)
        assertThat(fila.processor).isEqualTo(ProcessorType.BLUMON)
    }

    @Test
    fun `P1 desdeContexto con FastPayment queda sin orden y sin split`() {
        val fila = QueuedPayment.desdeContexto(
            context = PaymentContext.FastPayment(
                venueId = "venue-1",
                staffId = "staff-1",
                shiftId = "shift-1",
                amount = BigDecimal("15.00"),
                merchantAccountId = "merchant_cuid_123",
                blumonSerialNumber = "2841548417",
                idempotencyKey = "k-fast",
                isPortabilidad = true,
                serialNumbers = listOf("8952140064479453293F"),
            ),
            orderNumber = null,
            cardDetails = tarjeta,
            authorizationNumber = "502511",
            referenceNumber = "000000188232",
            error = null,
        )

        assertThat(fila.orderId).isNull()
        assertThat(fila.orderNumber).isNull()
        assertThat(fila.splitType).isEqualTo(SplitType.FULLPAYMENT)
        assertThat(fila.paidProductIds).isEmpty()
        assertThat(fila.equalPartsPartySize).isNull()
        // La venta rápida del módulo de inventario serializado SÍ lleva su prueba de venta.
        assertThat(fila.serialNumbers).containsExactly("8952140064479453293F")
        assertThat(fila.isPortabilidad).isTrue()
        assertThat(fila.lastError).isNull()
    }

    @Test
    fun `P1 desdeContexto de un cobro en EFECTIVO deja el merchant vacio y la fila sin marca`() {
        val fila = QueuedPayment.desdeContexto(
            context = PaymentContext.FastPayment(
                venueId = "venue-1",
                staffId = "staff-1",
                amount = BigDecimal("0.00"),
                merchantAccountId = null, // efectivo: sin procesador
                blumonSerialNumber = "",
                idempotencyKey = "k-cash",
            ),
            orderNumber = null,
            cardDetails = CardDetails.CASH,
            authorizationNumber = "EFECTIVO",
            referenceNumber = "CASH-1a2b3c4d5e6f7a8b",
            error = RuntimeException("sin red"),
        )

        // Centinela histórico: "" viaja a la fila y `toPaymentContext()` lo vuelve null al
        // reproducir (un cobro en efectivo no puede llevar merchant, o el arqueo lo cuenta
        // como cobro con tarjeta). Marca y PAN quedan nulos, como antes de esta función.
        assertThat(fila.merchantAccountId).isEmpty()
        assertThat(fila.maskedPan).isNull()
        assertThat(fila.cardBrand).isNull()
        assertThat(fila.entryMode).isEqualTo("MANUAL")
        assertThat((fila.toPaymentContext() as PaymentContext.FastPayment).merchantAccountId).isNull()
    }

    @Test
    fun `P1 desdeContexto NO puede marcar como BLUMON un contexto de AngelPay`() {
        // Cinturón y tirantes: AngelPay encola desde su propio ViewModel, pero si alguien pasara
        // su contexto por aquí, marcarlo BLUMON cambiaría EN SILENCIO la rama del replay.
        val fila = QueuedPayment.desdeContexto(
            context = PaymentContext.AngelPayPayment(
                venueId = "venue-1",
                staffId = "staff-1",
                amount = BigDecimal("100.00"),
                orderId = "order-ap",
                serialNumbers = listOf("895214006447945XXXX"),
            ),
            orderNumber = "0100",
            cardDetails = CardDetails.CASH,
            authorizationNumber = "AP-1",
            referenceNumber = "AP-REF-1",
            error = null,
        )

        assertThat(fila.processor).isEqualTo(ProcessorType.ANGELPAY)
        assertThat(fila.orderId).isEqualTo("order-ap")
        assertThat(fila.serialNumbers).containsExactly("895214006447945XXXX")
    }
}
