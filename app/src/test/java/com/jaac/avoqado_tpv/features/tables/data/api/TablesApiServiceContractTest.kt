package com.jaac.avoqado_tpv.features.tables.data.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Contrato de `TablesApiService` — Plan C Task 2.
 *
 * `.claude/rules/critical-warnings.md`/plan: la TPV está AISLADA a
 * /api/v1/tpv, nunca /mobile ni /dashboard. Un endpoint que se cuele
 * con otro prefijo rompe esa regla en silencio — no hay compilador que lo
 * atrape, solo este test de reflexión sobre las anotaciones de Retrofit.
 */
class TablesApiServiceContractTest {

    /**
     * Extrae la ruta (`value`) de CUALQUIER anotación HTTP de Retrofit sobre
     * un método — GET/POST/PUT/PATCH/DELETE. El ejemplo del plan (Task 2 Step
     * 1) solo cubría GET/POST/PUT/DELETE; se agrega PATCH aquí porque
     * `addItems` (Task 2 Step 4) usa `@PATCH` — sin esta rama el test pasaría
     * "en falso": no fallaría, pero tampoco vigilaría esa ruta.
     */
    private fun routesOf(methods: Array<java.lang.reflect.Method>): List<String> =
        methods.flatMap { method ->
            method.annotations.mapNotNull { annotation ->
                when (annotation) {
                    is GET -> annotation.value
                    is POST -> annotation.value
                    is PUT -> annotation.value
                    is PATCH -> annotation.value
                    is DELETE -> annotation.value
                    else -> null
                }
            }
        }

    @Test
    fun `todas las rutas del servicio son del namespace tpv`() {
        // El TPV esta aislado a /api/v1/tpv/*. Un mobile/ o dashboard/ que se
        // cuele rompe la regla y nadie lo nota hasta produccion.
        val rutas = routesOf(TablesApiService::class.java.declaredMethods)

        assertThat(rutas).isNotEmpty()
        rutas.forEach { ruta ->
            assertThat(ruta).startsWith("tpv/")
        }
    }

    @Test
    fun `el servicio expone las 12 operaciones verificadas contra tpv routes ts`() {
        // Ancla de regresion: si alguien borra o renombra un metodo sin querer
        // (o un merge conflict se come uno), esto lo marca en vez de que la
        // repo/viewmodel truene mas tarde con un NoSuchMethodError en runtime.
        val nombres = TablesApiService::class.java.declaredMethods.map { it.name }.toSet()

        assertThat(nombres).containsAtLeast(
            "getTables",
            "getFloorElements",
            "openTable",
            "clearTable",
            "getOrder",
            "addItems",
            "splitOrder",
            "splitOrderBySeat",
            "mergeOrders",
            "applyServiceCharge",
            "applyDiscount",
            "syncIntents",
            // Fase 1 (2026-08-03) — completitud del módulo Mesas.
            "getAvailableDiscounts",
            "compOrder",
            // Fase 2 (2026-08-03) — picker de personal para ASSIGN_ORDER.
            "getActiveStaff",
            // Fase 3 (2026-08-03) — completitud del módulo Mesas (últimos 3 de los 14 intents).
            "getServiceCharges",
            // Paridad Android 2026-08-06 (paridad-android-tpv.md, Hallazgo #4) —
            // "quitar descuento aplicado", online-only.
            "removeDiscount",
            // Paridad Android 2026-08-06 (paridad-android-tpv.md, Hallazgo #4,
            // último hueco cerrado — avoqado-server commit a0470a74) — "quitar
            // cargo por servicio aplicado", online-only, mismo patrón.
            "removeServiceCharge",
        )
    }

    @Test
    fun `getServiceCharges apunta al venue, no a una orden en particular`() {
        // A diferencia de discounts/available (elegibilidad de UNA cuenta),
        // service-charges es el catálogo del VENUE — sin :orderId en la ruta.
        // Verificado 2026-08-03 contra order-table.tpv.controller.ts::listServiceCharges
        // (companion nuevo de applyServiceCharge, que sí existía sin caller).
        val getServiceCharges = TablesApiService::class.java.declaredMethods.first { it.name == "getServiceCharges" }

        val ruta = getServiceCharges.annotations.filterIsInstance<GET>().first().value

        assertThat(ruta).isEqualTo("tpv/venues/{venueId}/service-charges")
    }

    @Test
    fun `addItems usa PATCH, no POST`() {
        // El ejemplo del plan (Task 2 Step 4) mostraba @POST para
        // orders/{orderId}/items. La ruta REAL registrada en
        // tpv.routes.ts:3908 es router.patch(...) -> orderController.addItemsToOrder.
        // Forzar esto con POST devolvería 404 (Express no matchea el verbo) la
        // primera vez que un mesero mande una ronda.
        val addItems = TablesApiService::class.java.declaredMethods.first { it.name == "addItems" }

        val isPatch = addItems.annotations.any { it is PATCH }
        val isPost = addItems.annotations.any { it is POST }

        assertThat(isPatch).isTrue()
        assertThat(isPost).isFalse()
    }

    @Test
    fun `applyDiscount apunta a discounts slash apply, no a discounts pelon`() {
        // El ejemplo del plan usaba "orders/{orderId}/discounts" — esa ruta no
        // existe en el server; la familia real vive bajo discounts/apply
        // (discountController.applyPredefinedDiscount, tpv.routes.ts:4806).
        val applyDiscount = TablesApiService::class.java.declaredMethods.first { it.name == "applyDiscount" }

        val ruta = applyDiscount.annotations.filterIsInstance<POST>().first().value

        assertThat(ruta).isEqualTo("tpv/venues/{venueId}/orders/{orderId}/discounts/apply")
    }

    @Test
    fun `removeDiscount usa DELETE sobre discounts slash discountId`() {
        // Verificado 2026-08-06 contra tpv.routes.ts:5073
        // (discountController.removeDiscount, permiso discounts:apply) — la
        // MISMA familia que applyDiscount, no discounts/apply.
        val removeDiscount = TablesApiService::class.java.declaredMethods.first { it.name == "removeDiscount" }

        val isDelete = removeDiscount.annotations.any { it is DELETE }
        val ruta = removeDiscount.annotations.filterIsInstance<DELETE>().first().value

        assertThat(isDelete).isTrue()
        assertThat(ruta).isEqualTo("tpv/venues/{venueId}/orders/{orderId}/discounts/{discountId}")
    }

    @Test
    fun `removeServiceCharge usa DELETE sobre service-charges slash orderServiceChargeId`() {
        // Verificado 2026-08-06 contra tpv.routes.ts (orderTableController.removeServiceCharge,
        // permiso orders:update + checkTableOwnership('order')) — avoqado-server
        // commit a0470a74, espejo de la ruta /mobile equivalente.
        val removeServiceCharge = TablesApiService::class.java.declaredMethods.first { it.name == "removeServiceCharge" }

        val isDelete = removeServiceCharge.annotations.any { it is DELETE }
        val ruta = removeServiceCharge.annotations.filterIsInstance<DELETE>().first().value

        assertThat(isDelete).isTrue()
        assertThat(ruta).isEqualTo("tpv/venues/{venueId}/orders/{orderId}/service-charges/{orderServiceChargeId}")
    }
}
