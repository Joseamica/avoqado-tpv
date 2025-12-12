# ADB Monitoring Guide - Avoqado TPV

> **Propósito**: Guía para monitorear y testear cambios en tiempo real usando ADB logcat.

---

## 🎯 Regla Obligatoria para Claude

**Después de implementar, modificar o arreglar CUALQUIER funcionalidad, Claude DEBE:**

1. Proporcionar el comando ADB específico para monitorear el cambio
2. Indicar qué buscar en los logs (patrones de éxito/error)
3. El usuario ejecutará el comando, copiará los logs y los pegará de vuelta para validación

**Formato de respuesta esperado:**
```
✅ Implementación completada: [descripción del cambio]

Para probar este cambio, ejecuta en tu terminal:
───────────────────────────────────────────────
adb logcat -c && adb logcat -s [TAGS] | grep -iE "[patterns]"
───────────────────────────────────────────────

Busca en los logs:
- ✅ Éxito: "[mensaje de éxito esperado]"
- ⚠️ Warning: "[mensaje de advertencia]"
- ❌ Error: "[mensaje de error]"

Cuando tengas los logs, pégalos aquí para verificar que funciona correctamente.
```

---

## 📋 Comandos por Área

### Pagos (Payments)

```bash
# Flow completo de pago
adb logcat -c && adb logcat -s PaymentViewModel,InitializationManager,RecordPaymentUseCase | grep -iE "payment|merchant|amount|blumon|process"

# Solo SDK Blumon
adb logcat -c && adb logcat -s BlumonInitializer,InitializationManager | grep -iE "oauth|dukpt|init|token"

# Errores de pago
adb logcat -c && adb logcat -s PaymentViewModel | grep -iE "error|fail|exception|rejected"
```

### Menú y Productos

```bash
# Carga de menú
adb logcat -c && adb logcat -s MenuViewModel | grep -iE "load|fetch|menu|product|category"

# Sincronización de menú
adb logcat -c && adb logcat -s MenuViewModel,MenuRepository | grep -iE "sync|cache|update"

# Stock/Inventario
adb logcat -c && adb logcat -s MenuViewModel | grep -iE "stock|inventory|available"
```

### Órdenes

```bash
# Operaciones de orden
adb logcat -c && adb logcat -s OrderViewModel,OrderSyncCoordinator | grep -iE "order|create|update|sync"

# Agregar items
adb logcat -c && adb logcat -s OrderViewModel | grep -iE "item|add|remove|quantity"

# Sincronización local-first
adb logcat -c && adb logcat -s OrderSyncCoordinator | grep -iE "cache|local|merge|sentToKitchen"
```

### Socket.IO (Eventos Real-time)

```bash
# Conexión y estado
adb logcat -c && adb logcat -s SocketManager | grep -iE "connect|disconnect|reconnect|status"

# Eventos recibidos
adb logcat -c && adb logcat -s SocketManager | grep -iE "event|received|emit"

# Rooms (salas)
adb logcat -c && adb logcat -s SocketManager | grep -iE "room|join|leave|venue"
```

### Autenticación

```bash
# Login flow
adb logcat -c && adb logcat -s AuthViewModel,LoginViewModel | grep -iE "login|auth|pin|credential"

# Token refresh
adb logcat -c && adb logcat -s TokenAuthenticator | grep -iE "token|refresh|401|expire"

# Sesión
adb logcat -c && adb logcat -s SessionManager,AuthRepository | grep -iE "session|logout|clear"
```

### Floor Plan (Mesas y Áreas)

```bash
# Carga de mesas
adb logcat -c && adb logcat -s FloorPlanViewModel | grep -iE "table|area|load|floor"

# Estado de mesas
adb logcat -c && adb logcat -s FloorPlanViewModel | grep -iE "status|occupied|available|reserved"

# Asignación de órdenes a mesas
adb logcat -c && adb logcat -s FloorPlanViewModel | grep -iE "assign|order|table"
```

### Reportes

```bash
# Carga de reportes
adb logcat -c && adb logcat -s ReportsViewModel | grep -iE "report|sales|fetch|load"

# Filtros y períodos
adb logcat -c && adb logcat -s ReportsViewModel | grep -iE "period|date|filter|range"
```

### Turnos (Shifts)

```bash
# Operaciones de turno
adb logcat -c && adb logcat -s ShiftViewModel | grep -iE "shift|open|close|active"

# Historial
adb logcat -c && adb logcat -s ShiftViewModel | grep -iE "history|list|fetch"
```

### Red y HTTP

```bash
# Requests/Responses HTTP
adb logcat -c && adb logcat -s OkHttp | grep -iE "-->|<--"

# Solo errores HTTP
adb logcat -c && adb logcat -s OkHttp | grep -iE "4[0-9][0-9]|5[0-9][0-9]|error"

# Headers (debug)
adb logcat -c && adb logcat -s OkHttp | grep -iE "authorization|content-type"
```

### Impresión (Kitchen/Receipt)

```bash
# Impresión a cocina
adb logcat -c && adb logcat -s PrinterViewModel,KitchenPrinter | grep -iE "print|kitchen|ticket|sent"

# Estado de impresora
adb logcat -c && adb logcat -s PrinterManager | grep -iE "printer|connect|status|error"
```

### Conectividad

```bash
# Estado de conexión
adb logcat -c && adb logcat -s ConnectivityObserver | grep -iE "network|available|unavailable|offline"

# Auto-retry
adb logcat -c && adb logcat -s ConnectivityObserver | grep -iE "retry|reconnect|restore"
```

---

## 🔧 Comandos Utilitarios

### Limpiar logs antes de testear
```bash
adb logcat -c
```

### Filtrar solo logs de la app
```bash
adb logcat --pid=$(adb shell pidof com.jaac.avoqado_tpv.sandbox)
```

### Logs con timestamp
```bash
adb logcat -v time -s TAG
```

### Guardar logs a archivo
```bash
adb logcat -s TAG > logs.txt
```

### Ver logs de crash
```bash
adb logcat -s AndroidRuntime | grep -iE "fatal|exception|crash"
```

### Ver todos los logs de Avoqado
```bash
adb logcat | grep -iE "avoqado|jaac|timber"
```

---

## 📱 Comandos Específicos por Build Variant

### Sandbox
```bash
# PID de sandbox
adb logcat --pid=$(adb shell pidof com.jaac.avoqado_tpv.sandbox)
```

### Production
```bash
# PID de production
adb logcat --pid=$(adb shell pidof com.jaac.avoqado_tpv)
```

---

## 🚀 Ejemplos de Uso Real

### Ejemplo 1: Después de modificar MenuViewModel

```
✅ Implementación completada: Agregada validación de stock mínimo

Para probar este cambio, ejecuta en tu terminal:
───────────────────────────────────────────────
adb logcat -c && adb logcat -s MenuViewModel | grep -iE "stock|minimum|validate"
───────────────────────────────────────────────

Busca en los logs:
- ✅ Éxito: "Stock validated: product X has Y units"
- ⚠️ Warning: "Low stock warning: product X below minimum"
- ❌ Error: "Stock validation failed: [reason]"

Cuando tengas los logs, pégalos aquí para verificar que funciona correctamente.
```

### Ejemplo 2: Después de arreglar bug en PaymentViewModel

```
✅ Bug fix completado: Corregido crash al procesar pago sin merchant

Para probar este cambio, ejecuta en tu terminal:
───────────────────────────────────────────────
adb logcat -c && adb logcat -s PaymentViewModel | grep -iE "merchant|null|process|error"
───────────────────────────────────────────────

Pasos para reproducir:
1. Abre la app
2. Selecciona una orden
3. Intenta procesar un pago

Busca en los logs:
- ✅ Éxito: "Payment processed successfully with merchant: X"
- ✅ Éxito (cash): "Cash payment processed (no merchant required)"
- ❌ Error anterior: "NullPointerException at getMerchant"
- ❌ Error: "Payment failed: [reason]"

Cuando tengas los logs, pégalos aquí para verificar que el bug está corregido.
```

### Ejemplo 3: Después de agregar nuevo evento Socket.IO

```
✅ Implementación completada: Nuevo evento 'inventory_alert' para alertas de stock

Para probar este cambio, ejecuta en tu terminal:
───────────────────────────────────────────────
adb logcat -c && adb logcat -s SocketManager | grep -iE "inventory_alert|event|receive"
───────────────────────────────────────────────

Pasos para reproducir:
1. Asegúrate de que la app esté conectada al socket
2. Desde el backend o dashboard, dispara un evento de alerta de inventario
3. Observa los logs

Busca en los logs:
- ✅ Éxito: "✅ Received event: inventory_alert"
- ✅ Éxito: "Processing inventory alert for product: X"
- ❌ Error: "Failed to parse inventory_alert event"

Cuando tengas los logs, pégalos aquí para verificar que el evento se procesa correctamente.
```

---

## 📝 Plantilla para Nuevas Áreas

Cuando se agregue una nueva área o feature, añadir aquí:

```bash
### [Nombre del Área]

```bash
# [Descripción breve]
adb logcat -c && adb logcat -s [Tags] | grep -iE "[patterns]"
```
```

---

## 🔍 Tips para Análisis de Logs

1. **Buscar errores primero**: Siempre empieza buscando "error", "exception", "fail"
2. **Verificar secuencia**: Los logs deben mostrar el flujo esperado en orden
3. **Comparar con éxito**: Si algo falla, compara los logs con un caso exitoso anterior
4. **Timestamps**: Usa `-v time` para ver cuánto tarda cada operación
5. **Contexto**: Incluye suficiente contexto (grep amplio) antes de filtrar más

---

**Last Updated:** 2025-12-11
**Maintainer:** Development Team
