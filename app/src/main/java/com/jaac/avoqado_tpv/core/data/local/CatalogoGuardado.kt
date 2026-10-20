package com.jaac.avoqado_tpv.core.data.local

import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntities
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Único dueño de la escritura del catálogo guardado (Room `products`).
 *
 * Antes cada pantalla (Inicio y Órdenes) hacía upsert de lo que le contestaba el server: un producto archivado en el server se quedaba
 * para siempre en la terminal, y con familias una sola fila vieja (el nombre viejo de la familia) volvía inválida la familia entera sin
 * red. Ahora el refresco es:
 *
 * - **De uno en uno:** un candado del proceso cubre el GET *y* la escritura. El último en escribir es siempre el último en preguntar
 *   (con dos refrescos cruzados, el lento ya no pisa la respuesta más nueva con la vieja).
 * - **Reemplazo exacto del negocio:** en una sola transacción se borran las filas del negocio y se guardan las que mandó el server;
 *   sólo si el server contestó bien. Si falla (sin red, error), la caché queda como estaba.
 *
 * Es seguro porque `GET tpv/venues/{venueId}/products` devuelve el catálogo completo, sin páginas, y nada local depende de `products`
 * (las órdenes locales no tienen FK a esta tabla y el alta rápida crea por HTTP).
 */
@Singleton
class CatalogoGuardado @Inject constructor(
    private val productRepository: ProductRepository,
    private val productDao: ProductDao,
) {
    private val candado = Mutex()

    suspend fun refrescar(venueId: String): Result<List<Product>> = candado.withLock {
        val cachedAt = System.currentTimeMillis()
        productRepository.getProducts(venueId).onSuccess { productos ->
            withContext(Dispatchers.IO) { productDao.reemplazarCatalogo(venueId, productos.toEntities(venueId, cachedAt)) }
        }
    }
}
