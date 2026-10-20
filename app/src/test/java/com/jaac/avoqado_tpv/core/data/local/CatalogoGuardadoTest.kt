package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal

/**
 * El refresco del catálogo guardado (Room `products`) es UN reemplazo exacto del negocio, de uno en uno.
 *
 * Antes sólo se hacía upsert: un producto archivado en el server se quedaba para siempre en la terminal, y con familias una sola fila
 * vieja (el nombre viejo de la familia) volvía inválida la familia entera sin red. Ahora `CatalogoGuardado.refrescar` toma un candado
 * del proceso, pregunta al server y, sólo si contestó bien, cambia las filas del negocio en una transacción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CatalogoGuardadoTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var repositorio: ProductRepository
    private lateinit var catalogo: CatalogoGuardado

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        repositorio = mockk(relaxed = true)
        catalogo = CatalogoGuardado(repositorio, db.productDao())
    }

    @After fun cerrar() {
        db.close()
    }

    @Test fun `el refresco deja exactamente lo que mando el server`() = runTest {
        guardar("v1", playera("S", "Playera básica"), playera("M", "Playera básica"), playera("L", "Playera básica"))
        // El server ya sólo manda S y M, y la familia se renombró.
        coEvery { repositorio.getProducts("v1") } returns Result.success(
            listOf(playera("S", "Playera clásica"), playera("M", "Playera clásica")),
        )

        val resultado = catalogo.refrescar("v1")

        assertThat(resultado.getOrThrow().map { it.id }).containsExactly("S", "M")
        val guardados = db.productDao().getAllProducts("v1").toDomain()
        assertWithMessage("L ya no existe en el server: no se queda en la terminal").that(guardados.map { it.id }).containsExactly("S", "M")
        assertThat(guardados.map { it.familia?.groupName }).containsExactly("Playera clásica", "Playera clásica")
        // Con una fila vieja ("Playera básica") la familia sería inválida y las dos tallas saldrían sueltas.
        val familias = reglas.familiasDe(guardados)
        assertThat(familias.keys).containsExactly("g-playera")
        assertThat(familias.getValue("g-playera").variantes.map { it.id }).containsExactly("S", "M")
    }

    // Codex r2 #1: el lento termina después del rápido y pisaría la respuesta más nueva con la vieja.
    @Test fun `dos refrescos cruzados terminan con la respuesta mas nueva`() = runTest {
        val primeraRespuesta = CompletableDeferred<Result<List<Product>>>()
        var llamadas = 0
        coEvery { repositorio.getProducts("v1") } coAnswers {
            if (++llamadas == 1) primeraRespuesta.await()
            else Result.success(listOf(playera("S", "Playera básica"), playera("M", "Playera básica")))
        }

        val a = async { catalogo.refrescar("v1") }
        runCurrent() // A ya tiene el candado y espera al server
        val b = async { catalogo.refrescar("v1") }
        runCurrent() // B espera el candado: no pregunta todavía
        assertWithMessage("B no pregunta mientras A tiene el candado").that(llamadas).isEqualTo(1)

        primeraRespuesta.complete(Result.success(listOf(playera("S", "Playera básica"), playera("M", "Playera básica"), playera("L", "Playera básica"))))
        awaitAll(a, b)

        assertThat(llamadas).isEqualTo(2)
        assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("S", "M")
    }

    @Test fun `no toca la cache de otro negocio`() = runTest {
        guardar("v2", playera("B-1", "Playera de B"), playera("B-2", "Playera de B"))
        guardar("v1", playera("S", "Playera básica"))
        coEvery { repositorio.getProducts("v1") } returns Result.success(listOf(playera("M", "Playera básica")))

        catalogo.refrescar("v1")

        assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("M")
        assertThat(db.productDao().getAllProducts("v2").map { it.productId }).containsExactly("B-1", "B-2")
    }

    @Test fun `si el refresco falla no borra nada`() = runTest {
        guardar("v1", playera("S", "Playera básica"), playera("M", "Playera básica"))
        coEvery { repositorio.getProducts("v1") } returns Result.failure(IOException("sin red"))

        val resultado = catalogo.refrescar("v1")

        assertThat(resultado.isFailure).isTrue()
        assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("S", "M")
    }

    @Test fun `un refresco que falla suelta el candado para el siguiente`() = runTest {
        coEvery { repositorio.getProducts("v1") } returnsMany listOf(
            Result.failure(IOException("sin red")),
            Result.success(listOf(playera("S", "Playera básica"))),
        )

        assertThat(catalogo.refrescar("v1").isFailure).isTrue()
        assertThat(catalogo.refrescar("v1").isSuccess).isTrue()
        assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("S")
    }

    private suspend fun guardar(venueId: String, vararg productos: Product) {
        db.productDao().upsertProducts(productos.map { it.toEntity(venueId, 100L) })
    }

    private fun playera(talla: String, familia: String) = Product(
        id = talla, name = "Playera $talla", sku = "sku-$talla", price = BigDecimal("199.00"), categoryId = "c-1", categoryName = "Ropa",
        description = null, emoji = "👕", imageUrl = null, available = true,
        familia = DatosDeFamilia("g-playera", familia, null, listOf(OpcionDeVariante("Talla", talla, 0, 0))),
    )

    private val reglas = ReglasDeFamilia<Product>(
        datos = { it.familia ?: DatosDeFamilia(null, null, null, null) },
        imagen = { it.imageUrl },
        precio = { it.price },
        agotado = { false },
        nombre = { it.name },
        codigos = { listOf(it.sku) },
    )
}
