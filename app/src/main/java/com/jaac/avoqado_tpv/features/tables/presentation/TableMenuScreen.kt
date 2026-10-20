package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.EntradaDeCatalogo
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.variantes.HojaDeVariantes
import com.jaac.avoqado_tpv.core.presentation.components.variantes.textoDePrecio
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuCategory
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifierGroup
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct
import com.jaac.avoqado_tpv.features.tables.domain.model.precioDeMesas
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `TableMenuScreen` (Plan C, Task 7) — catálogo para armar la ronda. Pantalla
 * PASO A PASO (empuja sobre `TableOrderScreen` en el back stack, "Listo"
 * regresa) — no el layout de dos paneles de la tablet; ver diseño §6.2.
 *
 * Producto SIN modificadores → un tap lo agrega (agrupa cantidad con el
 * mismo producto ya en el carrito). Producto CON modificadores → abre el
 * sheet de selección (cantidad + grupos, "Agregar" deshabilitado hasta que
 * todo grupo `required` tenga selección).
 *
 * Una FAMILIA de variantes (talla/color) sale como UN solo botón («3 variantes», «desde $199.00»): tocarlo abre la hoja de tallas
 * y la variante elegida sigue el camino de arriba (con extras ⇒ su sheet de selección; si no ⇒ se agrega).
 */
@Composable
fun TableMenuScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableMenuViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingLines by viewModel.pendingCart.lines.collectAsStateWithLifecycle()
    var modifierSheetProduct by remember { mutableStateOf<MenuProduct?>(null) }
    // `groupId` de la familia cuya hoja de tallas está abierta (null = cerrada).
    var familiaAbierta by remember { mutableStateOf<String?>(null) }

    val cartCount = pendingLines.sumOf { it.quantity }
    // Lo que se pinta: la lista de la categoría con cada familia en el lugar de su primera variante. Se recuerda para no reagrupar en
    // cada recomposición (la PAX tiene poca RAM); sólo cambia con el catálogo o la categoría.
    val entradas = remember(uiState.products, uiState.selectedCategoryId, uiState.familias) {
        reglasDeMesas.agruparCatalogo(uiState.productsForSelectedCategory(), uiState.familias)
    }

    Box(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                AvoqadoTopBar(
                    title = "Agregar productos",
                    onNavigationClick = onNavigateBack,
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider()
                    Box(modifier = Modifier.padding(Spacing.Space4)) {
                        AvoqadoButton(
                            text = if (cartCount > 0) "Listo · $cartCount artículo${if (cartCount == 1) "" else "s"}" else "Listo",
                            onClick = onNavigateBack,
                            fullWidth = true,
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            when {
                uiState.isLoading && uiState.products.isEmpty() -> {
                    AvoqadoFullScreenLoading(message = "Cargando menú…", modifier = Modifier.padding(padding))
                }

                uiState.errorMessage != null && uiState.products.isEmpty() -> {
                    MenuErrorState(message = uiState.errorMessage!!, onRetry = viewModel::load, modifier = Modifier.padding(padding))
                }

                else -> {
                    Column(modifier = Modifier.padding(padding)) {
                        if (uiState.categories.size > 1) {
                            CategoryChipsRow(
                                categories = uiState.categories,
                                selectedCategoryId = uiState.selectedCategoryId,
                                onSelect = viewModel::selectCategory,
                            )
                        }
                        ProductGrid(
                            entradas = entradas,
                            cartQuantities = pendingLines
                                .filter { it.modifiers.isEmpty() }
                                .groupingBy { it.productId }
                                .fold(0) { acc, line -> acc + line.quantity },
                            onProductTap = { product ->
                                if (product.hasModifiers) {
                                    modifierSheetProduct = product
                                } else {
                                    viewModel.addSimple(product)
                                }
                            },
                            onFamilyTap = { familia -> familiaAbierta = familia.groupId },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // 🧩 Hoja de tallas de la familia tocada. Va DESPUÉS del Scaffold para pintarse encima de él (barra, cuadrícula y «Listo»), y cubre
        // toda la pantalla consumiendo los toques. El Box acota el zIndex(100f) de la hoja. Los sheets de extras de abajo son ventanas
        // propias (ModalBottomSheet): quedan encima de todo, y la hoja ya está cerrada cuando se abren (se cierra PRIMERO al elegir).
        // El menú no envía ni cobra nada (la ronda se envía en `TableOrderScreen`, otra ruta): no tiene un «en vuelo» que frene la hoja.
        val familiaDeLaHoja = familiaAbierta?.let { uiState.familias[it] }
        if (familiaAbierta != null && familiaDeLaHoja == null) {
            // La familia ya no existe (catálogo refrescado sin ella): la hoja se cierra sola.
            LaunchedEffect(familiaAbierta) { familiaAbierta = null }
        }
        if (familiaDeLaHoja != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                HojaDeVariantes(
                    familia = familiaDeLaHoja,
                    reglas = reglasDeMesas,
                    precioTexto = { precioDeMesas(it.price) },
                    // Mesas no conoce existencias: no hay aviso de «Agotado» que dar (ni el «se puede vender igual» de Android).
                    aviso = { null },
                    onCerrar = { familiaAbierta = null },
                    onElegir = { elegida ->
                        // 1º cerrar la hoja (la entrega es única por apertura); 2º resolver la variante por id en el catálogo de AHORA;
                        // 3º el camino de hoy: con extras ⇒ el sheet de ESA variante; si no ⇒ agregarla a la ronda.
                        familiaAbierta = null
                        when (val accion = accionAlElegirEnMesas(viewModel.productoVigente(elegida.id))) {
                            is AccionAlElegir.Agregar -> viewModel.addSimple(accion.variante)
                            is AccionAlElegir.AbrirExtras -> modifierSheetProduct = accion.variante
                            is AccionAlElegir.NoDisponible, AccionAlElegir.Cerrar -> Unit
                        }
                    },
                )
            }
        }
    }

    modifierSheetProduct?.let { product ->
        ModifierPickerSheet(
            product = product,
            onDismiss = { modifierSheetProduct = null },
            onConfirm = { quantity, selected ->
                viewModel.addWithModifiers(product, quantity, selected)
                modifierSheetProduct = null
            },
        )
    }
}

@Composable
private fun CategoryChipsRow(
    categories: List<MenuCategory>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Space4, vertical = Spacing.Space2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
    ) {
        FilterChip(
            selected = selectedCategoryId == null,
            onClick = { onSelect(null) },
            label = { Text("Todos", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun ProductGrid(
    entradas: List<EntradaDeCatalogo<MenuProduct>>,
    cartQuantities: Map<String, Int>,
    onProductTap: (MenuProduct) -> Unit,
    onFamilyTap: (Familia<MenuProduct>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entradas.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No hay productos en esta categoría.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.Space6),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier,
        contentPadding = PaddingValues(Spacing.Space3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
    ) {
        items(entradas, key = { claveDeEntradaDeMesas(it) }) { entrada ->
            when (entrada) {
                is EntradaDeCatalogo.Suelto -> {
                    val product = entrada.producto
                    ProductTile(
                        name = product.name,
                        priceText = product.priceDisplay,
                        cartQuantity = cartQuantities[product.id] ?: 0,
                        hasModifiers = product.hasModifiers,
                        onClick = { onProductTap(product) },
                    )
                }
                // El botón de una familia NO agrega nada: abre la hoja de tallas.
                is EntradaDeCatalogo.DeFamilia -> {
                    val familia = entrada.familia
                    val precio = reglasDeMesas.textoDePrecio(familia, ::precioDeMesas)
                    ProductTile(
                        name = familia.nombre,
                        priceText = precio,
                        cartQuantity = cantidadEnLaRondaDeFamilia(familia, cartQuantities),
                        badgeText = TextosVariantes.variantes(familia.variantes.size),
                        onClick = { onFamilyTap(familia) },
                        // «Playera, 3 variantes, desde $199.00»: igual que el botón de familia de Órdenes y Cobro rápido.
                        modifier = Modifier.semantics {
                            contentDescription = TextosVariantes.accesibleFamilia(familia.nombre, familia.variantes.size, precio)
                        },
                    )
                }
            }
        }
    }
}

/**
 * El botón de un producto, o de una familia de variantes si trae [badgeText] («3 variantes»): entonces [priceText] es el «desde …» y
 * [hasModifiers] no aplica (cada talla puede o no tener extras: eso se ve al elegirla).
 *
 * Cuentas del botón de familia (PAX 360 dp, escala 1.0): la celda es (360 − 24 − 16) / 3 = 106.7 dp en cuadro, con 8 dp de margen ⇒
 * 90.7 × 90.7 dp de contenido. Alto: nombre en 2 renglones (2 × 20) + insignia (16 + 2) + 2 + precio en un renglón (16) = 76 dp ≤ 90.7.
 * El precio de la familia va en `labelSmall` (11 sp, sin espaciado) y no en `bodyMedium` (14 sp): «desde $199.00» a 14 sp mide 99 dp (no
 * cabe en un renglón) y en dos renglones el botón ya no cabe con el nombre en 2. Anchos medidos con `dmsans_medium.ttf` a 11 sp:
 * «desde $199.00» 76.5 dp, «desde $1299.00» 83.0, «desde $9999.00» 86.4, «desde $12999.00» 89.9 (de 90.7; de seis cifras se corta con «…»; la
 * hoja trae el precio exacto). Insignia (`dmsans_bold.ttf`, 11 sp, margen 2 × 4): «3 variantes» 69.2 dp, «100 variantes» 81.8.
 */
@Composable
private fun ProductTile(
    name: String,
    priceText: String,
    cartQuantity: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hasModifiers: Boolean = false,
    badgeText: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.Space2),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    // Una familia lleva además insignia y precio: el nombre pierde un renglón (la hoja de tallas lo trae completo).
                    maxLines = if (badgeText != null) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badgeText == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = priceText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasModifiers) {
                            Spacer(Modifier.padding(start = 4.dp))
                            Text(
                                text = "···",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 0.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = priceText,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 0.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (cartQuantity > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$cartQuantity",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.Space6)) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.Space2))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(Spacing.Space4))
            TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// MODIFICADORES — sheet propio de Mesas (no reusa nada de checkout/ordering)
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModifierPickerSheet(
    product: MenuProduct,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, selected: List<PendingRoundCart.Modifier>) -> Unit,
) {
    var quantity by rememberSaveable(product.id) { mutableStateOf(1) }
    // groupId -> set de modifierIds seleccionados
    val selections = remember(product.id) { mutableStateOf(mapOf<String, Set<String>>()) }

    fun toggle(group: MenuModifierGroup, modifierId: String) {
        val current = selections.value[group.id].orEmpty()
        val next = if (group.isSingleChoice) {
            setOf(modifierId)
        } else if (modifierId in current) {
            current - modifierId
        } else {
            current + modifierId
        }
        selections.value = selections.value + (group.id to next)
    }

    val allRequiredSatisfied = product.modifierGroups.filter { it.required }.all { group ->
        selections.value[group.id].orEmpty().isNotEmpty()
    }

    // 🔴 Bug real encontrado EN HARDWARE (Task 7, PAX A910S): un producto con
    // varios grupos de modificadores (p.ej. "Extras" + "Aderezos" + un grupo
    // Obligatorio) desborda la altura del sheet — sin scroll, el grupo
    // Obligatorio y el botón "Agregar" quedaban FUERA de pantalla e
    // INALCANZABLES, dejando al mesero sin forma de completar el alta.
    // Fix: el header (nombre/precio) y el botón "Agregar" quedan FIJOS; solo
    // la cantidad + los grupos de modificadores hacen scroll, acotados a un
    // porcentaje de la pantalla para que el sheet nunca crezca más allá de lo
    // navegable en una pantalla chica (NEXGO 480×480).
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight),
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.Space4)) {
                Text(text = product.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = product.priceDisplay, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Space4),
            ) {
                Spacer(Modifier.height(Spacing.Space4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = { quantity = (quantity - 1).coerceAtLeast(1) },
                        enabled = quantity > 1,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Icon(Icons.Default.Remove, contentDescription = "Menos") }
                    Text(text = "$quantity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    FilledIconButton(
                        onClick = { quantity += 1 },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Icon(Icons.Default.Add, contentDescription = "Más") }
                }

                product.modifierGroups.forEach { group ->
                    Spacer(Modifier.height(Spacing.Space4))
                    HorizontalDivider()
                    Spacer(Modifier.height(Spacing.Space2))
                    Text(
                        text = group.name + if (group.required) " · Obligatorio" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val selectedIds = selections.value[group.id].orEmpty()
                    group.modifiers.forEach { mod ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (group.isSingleChoice) {
                                RadioButton(selected = mod.id in selectedIds, onClick = { toggle(group, mod.id) })
                            } else {
                                Checkbox(checked = mod.id in selectedIds, onCheckedChange = { toggle(group, mod.id) })
                            }
                            Text(text = mod.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (mod.price != BigDecimal.ZERO) {
                                Text(
                                    text = "+$${mod.price.setScale(2, RoundingMode.HALF_UP).toPlainString()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.Space2))
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.Space4)
                    .padding(top = Spacing.Space2, bottom = Spacing.Space6),
            ) {
                Button(
                    onClick = {
                        val selected = product.modifierGroups.flatMap { group ->
                            val ids = selections.value[group.id].orEmpty()
                            group.modifiers.filter { it.id in ids }
                        }.map { PendingRoundCart.Modifier(id = it.id, name = it.name, price = it.price) }
                        onConfirm(quantity, selected)
                    },
                    enabled = allRequiredSatisfied,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Agregar")
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

private val previewCategories = listOf(
    MenuCategory(id = "c1", name = "Bebidas"),
    MenuCategory(id = "c2", name = "Comida"),
)

private val previewProducts = listOf(
    MenuProduct(id = "p1", name = "Café americano", price = BigDecimal("45.00"), categoryId = "c1"),
    MenuProduct(id = "p2", name = "Croissant de jamón y queso", price = BigDecimal("65.00"), categoryId = "c2"),
    MenuProduct(
        id = "p3",
        name = "Agua mineral",
        price = BigDecimal("30.00"),
        categoryId = "c1",
        modifierGroups = listOf(
            MenuModifierGroup(id = "g1", name = "Tamaño", type = "SINGLE_CHOICE", required = true, modifiers = listOf(
                com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifier(id = "m1", name = "Chica"),
                com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifier(id = "m2", name = "Grande", price = BigDecimal("15.00")),
            )),
        ),
    ),
)

// Tres familias entre los productos de siempre: playeras (precios distintos ⇒ «desde $199.00»), tazas (todas iguales ⇒ «$89.00») y una
// camisa de nombre largo y precio de cuatro cifras («desde $1299.00»).
private fun varianteDePreview(id: String, nombre: String, grupo: String, nombreGrupo: String, talla: String, indice: Int, precio: String) =
    MenuProduct(
        id = id,
        name = "$nombre $talla",
        price = BigDecimal(precio),
        familia = DatosDeFamilia(grupo, nombreGrupo, null, listOf(OpcionDeVariante("Talla", talla, 0, indice))),
    )

private val previewProductosConFamilias = listOf(
    previewProducts[0],
    varianteDePreview("v1", "Playera", "g-playera", "Playera deportiva", "S", 0, "199.00"),
    previewProducts[1],
    varianteDePreview("v2", "Playera", "g-playera", "Playera deportiva", "M", 1, "199.00"),
    varianteDePreview("v3", "Playera", "g-playera", "Playera deportiva", "L", 2, "249.00"),
    previewProducts[2],
    varianteDePreview("t1", "Taza", "g-taza", "Taza", "Chica", 0, "89.00"),
    varianteDePreview("t2", "Taza", "g-taza", "Taza", "Grande", 1, "89.00"),
    varianteDePreview("c1", "Camisa de lino con bordado", "g-camisa", "Camisa de lino con bordado", "M", 0, "1299.00"),
    varianteDePreview("c2", "Camisa de lino con bordado", "g-camisa", "Camisa de lino con bordado", "L", 1, "1899.00"),
)

@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "NEXGO 480×480 — grid")
@Composable
private fun TableMenuScreenPreview() {
    AvoqadoTheme {
        Scaffold(
            topBar = { AvoqadoTopBar(title = "Agregar productos", onNavigationClick = {}) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                CategoryChipsRow(categories = previewCategories, selectedCategoryId = null, onSelect = {})
                ProductGrid(
                    entradas = previewProducts.map { EntradaDeCatalogo.Suelto(it) },
                    cartQuantities = mapOf("p1" to 2),
                    onProductTap = {},
                    onFamilyTap = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640, name = "PAX 360×640 — familias entre productos")
@Composable
private fun TableMenuFamiliasPreview() {
    AvoqadoTheme {
        Scaffold(
            topBar = { AvoqadoTopBar(title = "Agregar productos", onNavigationClick = {}) },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                CategoryChipsRow(categories = previewCategories, selectedCategoryId = null, onSelect = {})
                ProductGrid(
                    entradas = reglasDeMesas.agruparCatalogo(
                        previewProductosConFamilias,
                        reglasDeMesas.familiasDe(previewProductosConFamilias),
                    ),
                    // 2 playeras S ya en la ronda ⇒ el botón de la familia lleva «2», como un producto suelto.
                    cartQuantities = mapOf("p1" to 2, "v1" to 2),
                    onProductTap = {},
                    onFamilyTap = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
