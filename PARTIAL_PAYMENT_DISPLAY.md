# Partial Payment Display in OrderListScreen

**Fecha:** 2025-12-12
**Feature:** Mostrar información de pago parcial en OrderCard

---

## Cambio Implementado

Cuando una orden tiene `paymentStatus = PARTIAL` y `remainingBalance > 0`, el `OrderCard` ahora muestra:

1. **Total original** (en texto secundario)
2. **Monto faltante** (prominente, en color terciario)

---

## Antes vs Después

### ❌ Antes
```
┌─────────────────────────────────┐
│ ORD-001237 • 20m    [En Cocina]│
│                                  │
│ 🍴 Mesa 8                        │
│    4 items              $522.00  │  ← Solo muestra total
└─────────────────────────────────┘
```

### ✅ Después (con pago parcial)
```
┌─────────────────────────────────┐
│ ORD-001237 • 20m    [En Cocina]│
│                                  │
│ 🍴 Mesa 8           Total: $522.00 │
│    4 items          Falta: $322.00 │  ← Muestra total Y faltante
└─────────────────────────────────┘
```

---

## Detalles de Implementación

### Archivo Modificado
`app/src/main/java/com/jaac/avoqado_tpv/features/ordering/presentation/components/OrderCard.kt`

### Lógica
```kotlin
if (order.paymentStatus == PaymentStatus.PARTIAL && order.remainingBalance > BigDecimal.ZERO) {
    Column(horizontalAlignment = Alignment.End) {
        // Total (secundario)
        Text(
            text = "Total: $${String.format(java.util.Locale.US, "%.2f", order.total)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Falta (prominente)
        Text(
            text = "Falta: $${String.format(java.util.Locale.US, "%.2f", order.remainingBalance)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary  // Color terciario para destacar
        )
    }
} else {
    // Orden sin pago parcial - muestra solo total
    Text(text = "$${String.format(java.util.Locale.US, "%.2f", order.total)}", ...)
}
```

---

## Preview Agregado

Se agregó `OrderCardPartialPaymentPreview()` con un ejemplo de:
- Total: $522.00
- Pagado: $200.00
- Falta: $322.00

---

## Casos de Uso

1. **Mesa con split payment** - Una mesa donde algunos comensales ya pagaron su parte
2. **Pago parcial por adelantado** - Cliente pagó un depósito, falta el resto
3. **Cuenta dividida en progreso** - Algunos ya pagaron, otros aún no

---

## UX Benefits

✅ **Visibilidad clara** - El mesero ve inmediatamente que la orden tiene pago parcial
✅ **Monto destacado** - El balance pendiente es más prominente que el total
✅ **Sin confusión** - Se muestra tanto el total original como lo que falta

---

## Integración con PaymentScreen

Este cambio es consistente con el PaymentScreen, que ya mostraba:
```kotlin
"Continuar pagando $${String.format(java.util.Locale.US, "%.2f", remainingBalance)}"
```

Ahora el flujo es:
1. **OrderListScreen** - Ve que falta $322.00
2. **Tap en la orden** - Abre MenuScreen
3. **"Pagar"** - Abre PaymentScreen con "Continuar pagando $322.00"

---

**Status:** ✅ Implementado y compilado
**Archivos Modificados:**
- `app/src/main/java/com/jaac/avoqado_tpv/features/ordering/presentation/components/OrderCard.kt`
