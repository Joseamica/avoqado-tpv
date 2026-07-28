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
        )
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
}
