package com.jaac.avoqado_tpv.features.sim_custody.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.MySim
import com.jaac.avoqado_tpv.features.sim_custody.domain.model.SimCustodyState

/**
 * "Mis SIMs" screen — promoter's SIM custody inbox (plan §3.1).
 *
 * Tuned for PAX A910S (360×640dp): single scroll region, LazyRow filters,
 * compact search, 48dp touch targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MisSimsScreen(
    onBack: () -> Unit,
    viewModel: MisSimsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        val text = when (msg) {
            is UiMessage.Info -> msg.text
            is UiMessage.Error -> msg.text
        }
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Mis SIMs", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            SearchField(
                value = state.searchQuery,
                onChange = viewModel::onSearchChange,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            FilterRow(
                selected = state.filter,
                pendingCount = state.pendingCount,
                onSelect = viewModel::onFilterChange,
            )

            if (state.pendingCount > 0 && state.filter != MisSimsFilter.SOLD_TODAY) {
                Spacer(Modifier.height(8.dp))
                AcceptAllBanner(
                    count = state.pendingCount,
                    onClick = viewModel::requestAcceptAll,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading -> LoadingState()
                state.error != null -> ErrorState(state.error!!, onRetry = viewModel::refresh)
                state.filtered.isEmpty() -> EmptyState(filter = state.filter)
                else -> SimList(
                    state = state,
                    onAccept = viewModel::acceptOne,
                    onReject = viewModel::rejectOne,
                )
            }
        }
    }

    if (state.confirmAcceptAll) {
        AcceptAllConfirmation(
            count = state.pendingCount,
            onConfirm = viewModel::confirmAcceptAll,
            onDismiss = viewModel::dismissAcceptAllConfirm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = {
            Text(
                "Buscar ICCID…",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun FilterRow(
    selected: MisSimsFilter,
    pendingCount: Int,
    onSelect: (MisSimsFilter) -> Unit,
) {
    // LazyRow allows horizontal scroll → never clips or wraps labels on 360dp.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterPill(
                label = "Todos",
                selected = selected == MisSimsFilter.ALL,
                onClick = { onSelect(MisSimsFilter.ALL) },
            )
        }
        item {
            FilterPill(
                label = "Pendientes",
                badge = pendingCount.takeIf { it > 0 },
                selected = selected == MisSimsFilter.PENDING,
                onClick = { onSelect(MisSimsFilter.PENDING) },
                accent = PillAccent.Amber,
            )
        }
        item {
            FilterPill(
                label = "Míos",
                selected = selected == MisSimsFilter.MINE,
                onClick = { onSelect(MisSimsFilter.MINE) },
            )
        }
        item {
            FilterPill(
                label = "Vendidos hoy",
                selected = selected == MisSimsFilter.SOLD_TODAY,
                onClick = { onSelect(MisSimsFilter.SOLD_TODAY) },
                accent = PillAccent.Violet,
            )
        }
    }
}

private enum class PillAccent { Default, Amber, Violet }

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
    accent: PillAccent = PillAccent.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val activeBg = when (accent) {
        PillAccent.Amber -> Color(0xFFFFE9A8)
        PillAccent.Violet -> Color(0xFFE1D4F5)
        PillAccent.Default -> scheme.onSurface
    }
    val activeFg = when (accent) {
        PillAccent.Amber -> Color(0xFF6B4A00)
        PillAccent.Violet -> Color(0xFF4A2D82)
        PillAccent.Default -> scheme.surface
    }
    val bg = if (selected) activeBg else Color.Transparent
    val fg = if (selected) activeFg else scheme.onSurfaceVariant
    val border = if (selected) bg else scheme.outlineVariant

    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (badge != null && badge > 0) {
            Box(
                modifier = Modifier
                    .background(
                        if (selected) fg.copy(alpha = 0.15f) else scheme.onSurfaceVariant.copy(alpha = 0.12f),
                        CircleShape,
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge.toString(),
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AcceptAllBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF4CC), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFF2C94C), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Pendientes de aceptar",
                color = Color(0xFF6B4A00),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                "$count SIM${if (count == 1) "" else "s"} esperando tu verificación",
                color = Color(0xFF6B4A00).copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF6B4A00),
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Aceptar todos", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No pudimos cargar tus SIMs",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}

@Composable
private fun EmptyState(filter: MisSimsFilter) {
    val (title, hint, icon) = when (filter) {
        MisSimsFilter.ALL -> Triple(
            "Aún no te han asignado SIMs",
            "Cuando tu Supervisor te asigne alguno, te avisaremos aquí.",
            Icons.Filled.SimCard,
        )
        MisSimsFilter.PENDING -> Triple(
            "Sin pendientes",
            "No tienes SIMs esperando tu aceptación.",
            Icons.Filled.CheckCircle,
        )
        MisSimsFilter.MINE -> Triple(
            "No tienes SIMs en tu poder",
            "Pide más a tu Supervisor cuando los necesites.",
            Icons.Filled.Inbox,
        )
        MisSimsFilter.SOLD_TODAY -> Triple(
            "Sin ventas hoy",
            "Las ventas del día aparecerán aquí al completarse.",
            Icons.Filled.Inbox,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SimList(
    state: MisSimsUiState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.filtered, key = { it.id }) { sim ->
            SimRow(
                sim = sim,
                isAccepting = sim.serialNumber in state.acceptingSerials,
                isRejecting = sim.serialNumber in state.rejectingSerials,
                onAccept = { onAccept(sim.serialNumber) },
                onReject = { onReject(sim.serialNumber) },
            )
        }
    }
}

@Composable
private fun SimRow(
    sim: MySim,
    isAccepting: Boolean,
    isRejecting: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sim.serialNumber,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = sim.categoryName ?: "Sin categoría",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StateBadge(sim.custodyState)
        }

        when (sim.custodyState) {
            SimCustodyState.PROMOTER_PENDING -> {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        enabled = !isAccepting && !isRejecting,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (isAccepting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Aceptar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        enabled = !isAccepting && !isRejecting,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (isRejecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rechazar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            SimCustodyState.PROMOTER_HELD -> Unit

            SimCustodyState.SOLD, SimCustodyState.UNKNOWN -> Unit
        }
    }
}

@Composable
private fun StateBadge(state: SimCustodyState) {
    val (bg, fg, label) = when (state) {
        SimCustodyState.PROMOTER_PENDING ->
            Triple(Color(0xFFFFE9A8), Color(0xFF6B4A00), "Pendiente")
        SimCustodyState.PROMOTER_HELD ->
            Triple(Color(0xFFD7F5DD), Color(0xFF1B5E20), "En mi poder")
        SimCustodyState.SOLD ->
            Triple(Color(0xFFE1D4F5), Color(0xFF4A2D82), "Vendido")
        SimCustodyState.UNKNOWN ->
            Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "—")
    }
    Row(
        modifier = Modifier
            .background(bg, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == SimCustodyState.PROMOTER_HELD) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AcceptAllConfirmation(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aceptar $count SIM${if (count == 1) "" else "s"}") },
        text = {
            Text(
                "Verifica que tienes físicamente todos los SIMs. " +
                    "Una vez aceptados quedan registrados como tuyos.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
            ) {
                Text("Sí, los tengo todos")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(widthDp = 360, heightDp = 640, showBackground = true, name = "PAX A910S — empty")
@Composable
private fun MisSimsPreviewEmpty() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis SIMs", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            SearchField(
                value = "",
                onChange = {},
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            FilterRow(selected = MisSimsFilter.ALL, pendingCount = 0, onSelect = {})
            Spacer(Modifier.height(8.dp))
            EmptyState(filter = MisSimsFilter.ALL)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(widthDp = 360, heightDp = 640, showBackground = true, name = "PAX A910S — pending")
@Composable
private fun MisSimsPreviewPending() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis SIMs", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            SearchField(
                value = "",
                onChange = {},
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            FilterRow(selected = MisSimsFilter.PENDING, pendingCount = 3, onSelect = {})
            Spacer(Modifier.height(8.dp))
            AcceptAllBanner(
                count = 3,
                onClick = {},
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
