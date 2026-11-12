package com.jaac.avoqado_tpv.features.checkout.presentation.components.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Warning
import com.jaac.avoqado_tpv.features.checkout.presentation.AvisoDescuentoDelCliente

/** D1 · «Descuento (<nombre>)  −$10.00  Quitar»: el descuento que el negocio le asignó al cliente. */
@Composable
fun LineaDescuentoDelCliente(
    nombre: String,
    monto: String,
    onQuitar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Descuento ($nombre)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE61F6B),
            modifier = Modifier.weight(1f),
        )
        Text(text = monto, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE61F6B))
        // TextButton ya respeta el área táctil mínima de 48dp.
        TextButton(onClick = onQuitar) { Text("Quitar") }
    }
}

/** D1 · por qué el descuento del cliente no está en la cuenta. Textos y valores los trae el ViewModel. */
@Composable
fun AvisoDescuentoDelClienteCard(
    aviso: AvisoDescuentoDelCliente,
    onPedirAutorizacion: () -> Unit,
    onUsarDescuento: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (texto, boton) = when (aviso) {
        is AvisoDescuentoDelCliente.SinPermiso ->
            "${aviso.nombre} tiene ${aviso.valor} de descuento. Tu puesto no puede aplicarlo." to
                ("Pedir autorización".takeIf { aviso.puedePedirPin }?.let { it to onPedirAutorizacion })
        is AvisoDescuentoDelCliente.YaHayOtro ->
            "${aviso.nombre} tiene ${aviso.valor} de descuento." to ("Usar el de ${aviso.nombre}" to onUsarDescuento)
        // El botón sigue para reintentar cuando vuelva la red.
        AvisoDescuentoDelCliente.SinRedParaPin ->
            "Sin conexión: no se puede pedir autorización ahora." to ("Pedir autorización" to onPedirAutorizacion)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Warning),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (boton != null) {
                OutlinedButton(onClick = boton.second) { Text(boton.first) }
            }
        }
    }
}

@Preview(name = "Línea del descuento del cliente", widthDp = 360, showBackground = true)
@Composable
private fun LineaDescuentoDelClientePreview() {
    AvoqadoTheme { LineaDescuentoDelCliente(nombre = "Cliente frecuente", monto = "−$10.00", onQuitar = {}) }
}

@Preview(name = "Aviso · sin permiso", widthDp = 360, showBackground = true)
@Composable
private fun AvisoSinPermisoPreview() {
    AvoqadoTheme {
        AvisoDescuentoDelClienteCard(AvisoDescuentoDelCliente.SinPermiso("Ana", "10 %", true), {}, {})
    }
}

@Preview(name = "Aviso · ya hay otro", widthDp = 360, showBackground = true)
@Composable
private fun AvisoYaHayOtroPreview() {
    AvoqadoTheme { AvisoDescuentoDelClienteCard(AvisoDescuentoDelCliente.YaHayOtro("Ana", "10 %"), {}, {}) }
}

@Preview(name = "Aviso · sin red", widthDp = 360, showBackground = true)
@Composable
private fun AvisoSinRedPreview() {
    AvoqadoTheme { AvisoDescuentoDelClienteCard(AvisoDescuentoDelCliente.SinRedParaPin, {}, {}) }
}
