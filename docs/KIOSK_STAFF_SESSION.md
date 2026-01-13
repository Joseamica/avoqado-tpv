# Kiosk Staff Session - Plan de Implementación

## Objetivo

Permitir que un staff se asigne al kiosk para atribución de ventas (comisiones/propinas).

---

## Resumen del Sistema

| Aspecto | Decisión |
|---------|----------|
| **Ubicación UI** | Botón al lado del ícono de settings en header |
| **Asignación** | Staff pone su PIN → queda asignado |
| **Cambio de staff** | Cualquiera puede cambiar poniendo su PIN |
| **Verificación previa** | No requerida (sin PIN del staff anterior) |
| **Persistencia** | En memoria (se pierde al cerrar app) |
| **Sin staff asignado** | Pagos funcionan, solo sin atribución |

---

## Flow de Usuario

### 1. Sin Staff Asignado

```
┌─────────────────────────────────────────────────┐
│  🍔 Menú Kiosk              [👤] [⚙️]          │
│                              ↑                  │
│                         Gris/outline            │
└─────────────────────────────────────────────────┘
```

Click en `[👤]` → Dialog de asignación:

```
┌─────────────────────────────┐
│     Asignar Empleado        │
│                             │
│   Ingresa tu PIN            │
│                             │
│   [● ● ● ●]                 │
│                             │
│   [1] [2] [3]               │
│   [4] [5] [6]               │
│   [7] [8] [9]               │
│   [C] [0] [←]               │
│                             │
│   [Cancelar]   [Asignar]    │
└─────────────────────────────┘
```

### 2. Con Staff Asignado

```
┌─────────────────────────────────────────────────┐
│  🍔 Menú Kiosk              [JP] [⚙️]          │
│                              ↑                  │
│                         Iniciales + color       │
└─────────────────────────────────────────────────┘
```

Click en `[JP]` → Dialog de info:

```
┌─────────────────────────────┐
│         👤                  │
│     Juan Pérez              │
│     Cajero                  │
│                             │
│   [Cambiar Empleado]        │
│   [Cerrar]                  │
└─────────────────────────────┘
```

Click en "Cambiar Empleado" → Mismo dialog de asignación (nuevo staff pone PIN)

---

## Integración con Pagos

### Card Payment

```kotlin
// Al procesar pago con tarjeta
processedById = kioskStaffSession?.staffId  // null si no hay staff
```

### Cash Payment

```kotlin
// Cash SIEMPRE pide PIN (confirma recepción de dinero físico)
// El staffId viene del PIN del momento, NO de la sesión
processedById = cashPinStaffId
```

### Tabla de Comportamiento

| Staff Session | Método | processedById | Comisión |
|---------------|--------|---------------|----------|
| ❌ Sin asignar | Card | `null` | ❌ No |
| ❌ Sin asignar | Cash | PIN del momento | ✅ Sí |
| ✅ Asignado | Card | Session staffId | ✅ Sí |
| ✅ Asignado | Cash | PIN del momento | ✅ Sí |

---

## Archivos a Crear/Modificar

### Nuevos Archivos

| Archivo | Descripción |
|---------|-------------|
| `KioskStaffSession.kt` | Data class para la sesión |
| `KioskStaffButton.kt` | Botón de usuario en header |
| `KioskStaffAssignDialog.kt` | Dialog para asignar con PIN |
| `KioskStaffInfoDialog.kt` | Dialog con info y cambiar |

### Archivos a Modificar

| Archivo | Cambios |
|---------|---------|
| `KioskViewModel.kt` | Agregar `_staffSession` StateFlow |
| `KioskMenuScreen.kt` | Agregar `KioskStaffButton` al header |
| `KioskPaymentScreen.kt` | Pasar `staffId` para Card payments |

---

## Modelo de Datos

```kotlin
data class KioskStaffSession(
    val staffId: String,
    val staffName: String,
    val staffInitials: String,  // "JP" para "Juan Pérez"
    val role: StaffRole
)
```

---

## Roles Autorizados

Staff con estos roles pueden asignarse al kiosk:
- SUPERADMIN
- OWNER
- ADMIN
- MANAGER
- CASHIER
- WAITER

---

## Consideraciones

1. **No persistir**: La sesión vive en ViewModel, se pierde al cerrar app (correcto para turnos)

2. **Sin warnings**: Si no hay staff asignado, pagos funcionan normalmente sin mostrar advertencias

3. **Reutilizar componentes**: El PIN dialog puede basarse en `KioskAdminPinDialog` existente

4. **Backend sin cambios**: El endpoint de payment ya acepta `processedById`, solo hay que enviarlo

---

## Verificación Post-Implementación

```bash
# Monitorear logs
adb logcat -c && adb logcat -s KioskViewModel | grep -iE "staff|session"
```

### Tests Manuales

1. **Asignar staff**: Click en botón → PIN → Verificar iniciales aparecen
2. **Ver info**: Click en iniciales → Ver nombre y rol
3. **Cambiar staff**: Click "Cambiar" → Nuevo PIN → Verificar cambio
4. **Pago Card sin staff**: Completar pago → Backend recibe `processedById: null`
5. **Pago Card con staff**: Asignar → Pagar → Backend recibe `processedById: staffId`
6. **Pago Cash**: Siempre pide PIN → Backend recibe `processedById` del PIN
