package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.systemui.ImmersiveSystemUiEffect
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

@Composable
fun PosInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 328.dp)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 56).dp),
        title = {
            ImmersiveSystemUiEffect()
            Text("Avoqado POS")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Habla con ventas", style = MaterialTheme.typography.titleMedium)
                Text("+52 56 4007 0001", style = MaterialTheme.typography.titleMedium)
                Text("Escanea el QR desde tu celular para escribirnos por WhatsApp.")
                Image(
                    painter = rememberQrBitmapPainter("https://wa.me/525640070001", size = 152.dp),
                    contentDescription = "QR de WhatsApp de ventas",
                    // A white quiet zone keeps the QR readable in either theme.
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .size(208.dp).background(Color.White).padding(28.dp),
                )
                Text("Esta información y el QR se ven sin conexión. WhatsApp necesita internet en tu celular.")
                Text("Tu negocio en un solo sistema", style = MaterialTheme.typography.titleMedium)
                Text("Avoqado POS funciona en Android, iPhone, iPad y Windows. Tu terminal también puede ser un mini POS y trabajar junto al sistema.")
                Text("Para tiendas, restaurantes, salones, consultorios, estudios y más.")
                Text("Las funciones disponibles dependen del plan, la configuración y el giro de tu negocio.")
                PosInfoFeature("Ventas y catálogo", "Productos, precios y órdenes desde tu punto de venta.")
                PosInfoFeature("Caja y turnos", "Apertura, movimientos y cierre de caja.")
                PosInfoFeature("Inventario, compras y recetas", "Existencias, insumos y órdenes de compra.")
                PosInfoFeature("Clientes y lealtad", "Historial de clientes, puntos y sellos.")
                PosInfoFeature("Reportes y sucursales", "Consulta ventas y resultados de tus negocios.")
                PosInfoFeature("Mesas, comandas y cocina", "Cuentas por mesa, impresión y pantalla de cocina (KDS).")
                PosInfoFeature("Reservas, citas y clases", "Agenda, horarios y clases con cupo.")
                PosInfoFeature("Personal, permisos y comisiones", "Accesos por persona y seguimiento del equipo.")
                PosInfoFeature("Facturación y contabilidad", "CFDI y herramientas para las cuentas del negocio.")
                PosInfoFeature("IA con ChatGPT y Claude", "Conecta tu asistente para consultar y gestionar tu negocio con tus permisos.")
            }
        },
        confirmButton = { AvoqadoButton(text = "Cerrar", onClick = onDismiss) },
    )
}

@Composable
private fun PosInfoFeature(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Preview(showBackground = true, widthDp = 360, heightDp = 640, fontScale = 1.5f)
@Composable
private fun PosInfoDialogPreview() {
    AvoqadoTheme(darkTheme = false) {
        Column {
            Text("Sin conexión", modifier = Modifier.height(56.dp))
            PosInfoDialog(onDismiss = {})
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun PosInfoDialogDarkPreview() {
    AvoqadoTheme(darkTheme = true) {
        Column {
            Text("Sin conexión", modifier = Modifier.height(56.dp))
            PosInfoDialog(onDismiss = {})
        }
    }
}
