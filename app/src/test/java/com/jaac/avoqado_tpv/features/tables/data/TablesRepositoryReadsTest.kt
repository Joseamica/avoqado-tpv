package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.FloorElementsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TablesResponse
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * `TablesRepository.getTables`/`getFloorElements` (Plan C, Task 6) — las
 * lecturas puras que alimentan `TablesViewModel`. A diferencia de [addItems]
 * (Task 4), estas NUNCA se encolan — no reciben `SyncOutbox` en su camino, y
 * este test lo prueba con un mock ESTRICTO de [SyncOutbox] que nunca se
 * invoca (`TablesRepositoryOfflineTest` ya prueba la clasificación
 * retry/propagate para operaciones que SÍ se encolan).
 */
class TablesRepositoryReadsTest {

    private val api = mockk<TablesApiService>()
    private val outbox = mockk<SyncOutbox>() // nunca se toca en lecturas
    /** `tables:pay-any` — irrelevante para estos tests; el default niega el override. */
    private val permissions = mockk<PermissionsRepository>(relaxed = true)  // relaxed → hasPermission = false
    private val repo = TablesRepository(api, outbox, permissions)

    private val venueId = "venue-1"

    // region — getTables

    @Test
    fun getTables_exito_regresa_la_lista_del_body() = runTest {
        val tables = listOf(DiningTable(id = "1", number = "5"))
        coEvery { api.getTables(venueId) } returns Response.success(TablesResponse(success = true, data = tables))

        val result = repo.getTables(venueId)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(tables)
    }

    @Test
    fun getTables_http_error_regresa_failure_con_el_codigo() = runTest {
        coEvery { api.getTables(venueId) } returns errorTablesResponse(403)

        val result = repo.getTables(venueId)

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(BackendHttpException::class.java)
        assertThat((error as BackendHttpException).statusCode).isEqualTo(403)
    }

    @Test
    fun getTables_sin_red_regresa_failure_no_lanza() = runTest {
        coEvery { api.getTables(venueId) } throws IOException("sin red")

        val result = repo.getTables(venueId)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun getTables_cancelacion_se_repropaga_no_se_traga() = runTest {
        coEvery { api.getTables(venueId) } throws CancellationException("cancelado")

        var propagated = false
        try {
            repo.getTables(venueId)
        } catch (e: CancellationException) {
            propagated = true
        }

        assertThat(propagated).isTrue()
    }

    // endregion

    // region — getFloorElements

    @Test
    fun getFloorElements_exito_regresa_la_lista_del_body() = runTest {
        val elements = listOf(FloorElement(id = "e1", type = "WALL", positionX = 0.1f, positionY = 0.2f))
        coEvery { api.getFloorElements(venueId) } returns Response.success(FloorElementsResponse(success = true, data = elements))

        val result = repo.getFloorElements(venueId)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(elements)
    }

    @Test
    fun getFloorElements_http_error_regresa_failure() = runTest {
        coEvery { api.getFloorElements(venueId) } returns errorFloorResponse(500)

        val result = repo.getFloorElements(venueId)

        assertThat(result.isFailure).isTrue()
    }

    // endregion

    private fun errorTablesResponse(code: Int): Response<TablesResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    private fun errorFloorResponse(code: Int): Response<FloorElementsResponse> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
}
