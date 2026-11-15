package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.CatalogoGuardado
import com.jaac.avoqado_tpv.core.data.local.ProductoGuardado
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifier
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifierGroup
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuCategory
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

data class TableMenuUiState(
    val isLoading: Boolean = true,
    val products: List<MenuProduct> = emptyList(),
    val categories: List<MenuCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val errorMessage: String? = null,
    /**
     * Las familias de variantes (talla/color) del catálogo COMPLETO, por `groupId`, calculadas UNA vez cuando llega un catálogo (en el
     * mismo `update` que [products]: nunca hay una ventana en que no coincidan). Cambiar de categoría no las recalcula; la categoría sólo
     * decide en qué lugar de la lista sale el botón de cada una.
     */
    val familias: Map<String, Familia<MenuProduct>> = emptyMap(),
    /** D86: el menú que se ve es el guardado en la terminal (sin red); `null` = el del SERVIDOR. */
    val avisoCatalogo: String? = null,
) {
    fun productsForSelectedCategory(): List<MenuProduct> =
        if (selectedCategoryId == null) products else products.filter { it.categoryId == selectedCategoryId }
}

/**
 * `TableMenuScreen` (Plan C, Task 7) — catálogo + alta al carrito de la ronda.
 * Pantalla PROPIA de Mesas (regla dura del plan: no importa de
 * `features/ordering/`) — consume [TablesRepository.getProducts]/
 * [TablesRepository.getCategories] (`tpv/venues/{venueId}/products|categories`,
 * verificados 2026-07-29) y escribe en el mismo [PendingRoundCart] singleton
 * que lee [TableOrderViewModel] — dos `hiltViewModel()` de dos entradas
 * distintas del back stack, mismo estado compartido.
 */
@HiltViewModel
class TableMenuViewModel @Inject constructor(
    private val repository: TablesRepository,
    val tableSession: TableSession,
    val pendingCart: PendingRoundCart,
    private val deviceInfoManager: DeviceInfoManager,
    /** D86: el catálogo que la terminal ya guarda, para pedir sin red. */
    private val catalogoGuardado: CatalogoGuardado,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TableMenuUiState())
    val uiState: StateFlow<TableMenuUiState> = _uiState.asStateFlow()

    init {
        tableSession.current()?.let { pendingCart.ensureOrder(it.orderId) }
        load()
    }

    fun load() {
        val vId = deviceInfoManager.getVenueId()
        if (vId == null) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "No hay venue configurado en este terminal.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.products.isEmpty()) }

            val productsResult = repository.getProducts(vId)
            val categoriesResult = repository.getCategories(vId)

            productsResult.fold(
                onSuccess = { products ->
                    // Fuera del `update`: éste puede reintentarse si otro hilo escribe a la vez.
                    val familias = reglasDeMesas.familiasDe(products)
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            products = products,
                            familias = familias,
                            categories = categoriesResult.getOrElse { current.categories },
                            errorMessage = null,
                            avisoCatalogo = null,
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    // D86: sin red y sin catálogo en memoria, se pide del catálogo que la terminal ya guarda.
                    if (e is IOException && _uiState.value.products.isEmpty()) {
                        val guardados = leerCatalogoGuardado(vId)
                        val guardado = guardados.map { it.aMenuDeMesas() }
                        if (guardado.isNotEmpty()) {
                            val familias = reglasDeMesas.familiasDe(guardado)
                            _uiState.update { current ->
                                current.copy(
                                    isLoading = false,
                                    products = guardado,
                                    familias = familias,
                                    categories = categoriasDe(guardados),
                                    errorMessage = null,
                                    avisoCatalogo = AVISO_CATALOGO_GUARDADO,
                                )
                            }
                            return@fold
                        }
                    }
                    _uiState.update { current ->
                        if (current.products.isNotEmpty()) {
                            // Offline-first de lectura: el catálogo ya cargado se
                            // sigue mostrando — nunca se tapa un grid que
                            // funcionaba con una pantalla de error.
                            Timber.w(e, "⚠️ [TableMenu] Refresco de catálogo falló — mostrando el último cargado")
                            current.copy(isLoading = false)
                        } else {
                            Timber.e(e, "❌ [TableMenu] Sin catálogo en memoria y el refresco falló")
                            current.copy(
                                isLoading = false,
                                errorMessage = if (e is IOException) {
                                    // D86: la terminal no tiene un catálogo guardado de este negocio.
                                    "Sin conexión y sin menú guardado en esta terminal. Verifica tu red e intenta de nuevo."
                                } else {
                                    "No se pudo cargar el menú."
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * D86 (Codex r0 #3): sólo lo que se puede vender — `available` (activo y con existencia; el guardado no separa las dos) y de
     * una categoría activa. Lo inactivo nunca aparece: Mesas no lo deshabilita en la cuadrícula, sólo no lo muestra.
     */
    private suspend fun leerCatalogoGuardado(venueId: String): List<ProductoGuardado> = try {
        // Room: las consultas `suspend` ya corren fuera del hilo principal.
        catalogoGuardado.disponibles(venueId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "⚠️ [TableMenu] No se pudo leer el catálogo guardado")
        emptyList()
    }

    fun selectCategory(categoryId: String?) {
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
    }

    /**
     * El producto con este `id` en el catálogo de AHORA (`null` si ya no está). La hoja de tallas puede llevar abierta una copia vieja:
     * al elegir, la variante se resuelve aquí por `id` y se decide con [accionAlElegirEnMesas].
     */
    fun productoVigente(id: String): MenuProduct? = _uiState.value.products.firstOrNull { it.id == id }

    /** Tap simple — solo para productos SIN grupos de modificadores. */
    fun addSimple(product: MenuProduct) {
        pendingCart.addSimple(productId = product.id, name = product.name, unitPrice = product.price)
    }

    /** Alta desde el sheet de modificadores. */
    fun addWithModifiers(product: MenuProduct, quantity: Int, selected: List<PendingRoundCart.Modifier>) {
        pendingCart.addWithModifiers(
            productId = product.id,
            name = product.name,
            unitPrice = product.price,
            quantity = quantity,
            modifiers = selected,
        )
    }
}

/** D86: el aviso de «Agregar productos» cuando el menú es el guardado en la terminal. */
internal const val AVISO_CATALOGO_GUARDADO = "Sin conexión: menú guardado en la terminal. La ronda se guarda y se sube sola."

/** D86: un producto del catálogo guardado (el de «Cobrar» rápido) visto como producto de Mesas. */
internal fun ProductoGuardado.aMenuDeMesas(): MenuProduct = MenuProduct(
    id = id,
    name = nombre,
    price = precio,
    categoryId = categoriaId,
    imageUrl = imagenUrl,
    color = categoriaColor,
    active = true,
    modifierGroups = grupos.map { g ->
        MenuModifierGroup(id = g.id, name = g.nombre, type = g.tipo, required = g.obligatorio, modifiers = g.extras.map { MenuModifier(it.id, it.nombre, it.precio) })
    },
    familia = familia,
)

/** D86: las categorías del catálogo guardado, en el orden en que llegaron (sin duplicados). */
internal fun categoriasDe(guardados: List<ProductoGuardado>): List<MenuCategory> =
    guardados.distinctBy { it.categoriaId }.mapIndexed { indice, p ->
        MenuCategory(id = p.categoriaId, name = p.categoriaNombre, displayOrder = indice, color = p.categoriaColor, active = true)
    }
