# Avoqado TPV - Documentacion Completa de Funcionalidades

> Documento generado mediante analisis exhaustivo del codebase.
> Ultima actualizacion: Diciembre 2024

---

## Indice

1. [Arquitectura General](#1-arquitectura-general)
2. [Pantallas y Navegacion](#2-pantallas-y-navegacion)
3. [Autenticacion por PIN de Empleado](#3-autenticacion-por-pin-de-empleado)
4. [Procesamiento de Pagos](#4-procesamiento-de-pagos)
5. [Sistema de Reembolsos](#5-sistema-de-reembolsos)
6. [Sistema de Propinas](#6-sistema-de-propinas)
7. [Split Payments (Pagos Divididos)](#7-split-payments-pagos-divididos)
8. [Gestion de Ordenes](#8-gestion-de-ordenes)
9. [Floor Plan (Gestion de Mesas)](#9-floor-plan-gestion-de-mesas)
10. [Sistema de Turnos (Shifts)](#10-sistema-de-turnos-shifts)
11. [Reloj de Empleados (Timeclock)](#11-reloj-de-empleados-timeclock)
12. [Reportes y Analiticas](#12-reportes-y-analiticas)
13. [Capacidades Offline](#13-capacidades-offline)
14. [Integraciones Backend](#14-integraciones-backend)
15. [Comandos Remotos (Enterprise)](#15-comandos-remotos-enterprise)
16. [Seguridad y Cumplimiento](#16-seguridad-y-cumplimiento)
17. [Hardware e Impresion](#17-hardware-e-impresion)
18. [Configuracion y Personalizacion](#18-configuracion-y-personalizacion)
19. [Sistema de Reviews (Calificacion)](#19-sistema-de-reviews-calificacion)
20. [Sistema de Propinas Detallado](#20-sistema-de-propinas-detallado)
21. [Verificacion de Pago](#21-verificacion-de-pago)
22. [Opciones de Recibo Digital](#22-opciones-de-recibo-digital)
23. [Payment Routing (Multi-Merchant)](#23-payment-routing-multi-merchant)
24. [Metodos de Pago (Efectivo vs Tarjeta)](#24-metodos-de-pago-efectivo-vs-tarjeta)
25. [Sistema de Descuentos y Cupones](#25-sistema-de-descuentos-y-cupones)
26. [Pedido Rapido vs Servicio de Mesa](#26-pedido-rapido-vs-servicio-de-mesa)
27. [Tabs de MenuScreen](#27-tabs-de-menuscreen)
28. [Configuracion de Flujo (TpvSettings)](#28-configuracion-de-flujo-tpvsettings)
29. [Activacion de Terminal](#29-activacion-de-terminal)
30. [Sistema de Modificadores Detallado](#30-sistema-de-modificadores-detallado)
31. [Programa de Lealtad de Clientes](#31-programa-de-lealtad-de-clientes)
32. [Barcode Quick Add](#32-barcode-quick-add)
33. [Sistema de Inventario](#33-sistema-de-inventario)
34. [SuperAdminScreen](#34-superadminscreen)
35. [SupportScreen y Ayuda](#35-supportscreen-y-ayuda)
36. [Modo Sandbox/Demo](#36-modo-sandboxdemo)
37. [Impresion de Comandas (Cocina)](#37-impresion-de-comandas-cocina)
38. [Covers y Notas de Orden](#38-covers-y-notas-de-orden)
39. [Void vs Refund](#39-void-vs-refund)
40. [Observabilidad y Diagnosticos](#40-observabilidad-y-diagnosticos)
41. [Auditoria y Compliance](#41-auditoria-y-compliance)

---

## 1. Arquitectura General

### Stack Tecnologico
- **Lenguaje:** Kotlin
- **UI Framework:** Jetpack Compose
- **Arquitectura:** Clean Architecture (Presentation → Domain → Data)
- **Inyeccion de Dependencias:** Hilt
- **Base de Datos Local:** Room DB
- **Networking:** Retrofit + OkHttp
- **Real-time:** Socket.IO
- **Pagos:** Blumon SDK (PAX terminals)

### Estructura de Capas

```
├── presentation/     # ViewModels, UI Compose, Navigation
├── domain/          # Use Cases, Repository Interfaces, Models
├── data/            # Repository Implementations, API, Room
└── di/              # Hilt Modules
```

### Patron de Estado
- **UiState Pattern:** Loading, Success, Error states
- **StateFlow/SharedFlow:** Observables reactivos
- **ViewModel:** Manejo de estado por pantalla

---

## 2. Pantallas y Navegacion

### 2.1 Flujo de Inicio

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **SplashScreen** | Pantalla inicial con logo animado | `splash` |
| **ActivationScreen** | Activacion del dispositivo con codigo | `activation` |
| **LoginScreen** | Autenticacion con PIN de empleado | `login` |

### 2.2 Pantallas Principales

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **WelcomeScreen** | Home principal con acceso rapido | `welcome` |
| **FastPaymentEntryScreen** | Pago rapido sin orden | `fast_payment` |
| **ShiftScreen** | Gestion de turno actual | `shift` |
| **OrderingWelcomeScreen** | Inicio de toma de ordenes | `ordering_welcome` |

### 2.3 Pantallas de Ordenes

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **FloorPlanCanvasScreen** | Mapa interactivo de mesas | `floor_plan` |
| **MenuScreen** | Catalogo de productos y categorias | `menu` |
| **OrderListScreen** | Lista de ordenes activas | `order_list` |
| **OrderDetailScreen** | Detalle de una orden especifica | `order_detail/{orderId}` |

### 2.4 Pantallas de Pago

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **PaymentScreen** | Procesamiento de pago principal | `payment/{orderId}` |
| **SplitByProductScreen** | Division por productos | `split_product/{orderId}` |
| **SplitByPersonScreen** | Division por personas | `split_person/{orderId}` |
| **RefundConfirmationScreen** | Confirmacion de reembolso | `refund_confirm` |

### 2.5 Pantallas de Historial

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **PaymentsScreen** | Historial de transacciones | `payments` |
| **ReportsScreen** | Dashboard de reportes | `reports` |
| **HistoricalPeriodDetailScreen** | Detalle de periodo especifico | `historical_detail` |

### 2.6 Pantallas de Configuracion

| Pantalla | Descripcion | Ruta |
|----------|-------------|------|
| **SettingsScreen** | Configuracion general | `settings` |
| **SupportScreen** | Ayuda y soporte tecnico | `support` |
| **TimeclockScreen** | Control de asistencia | `timeclock` |
| **SuperAdminScreen** | Funciones administrativas avanzadas | `super_admin` |

### 2.7 Overlays y Modales

- **TipSelectionOverlay:** Seleccion de propina
- **PaymentMethodOverlay:** Seleccion de metodo de pago
- **ReceiptOptionsOverlay:** Opciones de recibo
- **RefundReasonDialog:** Motivo de reembolso
- **MerchantSelectionDialog:** Seleccion de merchant (multi-merchant)
- **ConfirmationDialogs:** Dialogos de confirmacion genericos

---

## 3. Autenticacion por PIN de Empleado

El TPV utiliza un sistema de autenticacion basado en PIN numerico para identificar a los empleados. Este sistema sigue los patrones de Square POS y Toast POS.

### 3.1 Flujo de Autenticacion

```
1. Pantalla de Login
   └── Muestra PIN pad numerico (4 digitos)

2. Ingreso de PIN
   └── PIN pad custom (sin teclado del sistema)
   └── Indicador visual de digitos ingresados

3. Validacion en Backend
   └── POST /api/v1/venues/{venueId}/auth/pin-login
   └── PIN hasheado con bcrypt

4. Respuesta Exitosa
   └── JWT tokens (access + refresh)
   └── Datos del empleado
   └── Permisos y rol
   └── Conexion Socket.IO

5. Navegacion a Home
   └── WelcomeScreen con opciones
```

### 3.2 Componentes de UI

#### PinPad (Teclado Numerico)
```kotlin
// Diseno profesional estilo Square/Toast
- Grid 3x4: numeros 1-9, Clear, 0, Backspace
- Botones circulares de 80dp (touch-friendly)
- Feedback visual (ripple, elevation)
- Estados: enabled, disabled (durante carga)
```

#### PinIndicator (Indicador de Digitos)
```kotlin
// 4 circulos que se llenan conforme se ingresa el PIN
- Vacio: circulo con borde
- Lleno: circulo solido
- Animacion de transicion
```

#### Botones de Accion
| Boton | Icono | Funcion |
|-------|-------|---------|
| **Timeclock** | ⏱ | Clock in/out sin login completo |
| **Ir** | Texto | Login completo al TPV |

### 3.3 Request de Login

```kotlin
data class PinLoginRequest(
    val pin: String,           // 4-6 digitos
    val serialNumber: String   // Numero de serie del dispositivo
)
```

#### Endpoint
```
POST /api/v1/venues/{venueId}/auth/pin-login
```

### 3.4 Response de Autenticacion

```kotlin
data class AuthResponse(
    // Tokens JWT
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,         // 86400 segundos (24 horas)
    val tokenType: String,      // "Bearer"

    // Contexto de autenticacion
    val staffId: String,
    val venueId: String,
    val role: StaffRole,
    val permissions: List<String>,

    // Detalles del empleado
    val staff: StaffMember,
    val venue: VenueInfo,

    // Metadata
    val correlationId: String,
    val issuedAt: String,

    // Programa de lealtad
    val loyaltyActive: Boolean
)
```

### 3.5 Roles de Empleado

| Rol | Descripcion | Puede Reembolsar |
|-----|-------------|------------------|
| **SUPERADMIN** | Acceso total al sistema | Si |
| **OWNER** | Acceso a toda la organizacion | Si |
| **ADMIN** | Gestion a nivel venue | Si |
| **MANAGER** | Gestion de operaciones | No |
| **CASHIER** | Procesamiento de pagos | No |
| **WAITER** | Gestion de ordenes | No |
| **KITCHEN** | Vista de cocina | No |
| **HOST** | Reservaciones | No |
| **VIEWER** | Solo lectura | No |

### 3.6 Estados de Login

```kotlin
sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Success(val authResponse: AuthResponse) : LoginState()
    data class Error(val message: String) : LoginState()
    object TerminalNotActivated : LoginState()
    data class VenueNotOperational(val message: String) : LoginState()
}
```

### 3.7 Manejo de Errores

| Codigo HTTP | Mensaje | Accion |
|-------------|---------|--------|
| 401 | PIN incorrecto | Mostrar error, limpiar PIN |
| 404 | Usuario no encontrado | Verificar venue |
| 429 | Rate limit (10 intentos/15min) | Esperar y reintentar |
| TERMINAL_NOT_ACTIVATED | Terminal desactivada | Ir a activacion |
| VENUE_SUSPENDED | Venue suspendido | Mostrar overlay bloqueante |

### 3.8 Persistencia de Sesion

Datos guardados en SecureStorage (encriptado AES256-GCM):

| Dato | Uso |
|------|-----|
| `accessToken` | Autenticacion API |
| `refreshToken` | Renovacion silenciosa |
| `venueId` | Aislamiento de tenant |
| `staffId` | Contexto de usuario |
| `staffName` | Display en UI |
| `role` | Autorizacion de reembolsos |
| `permissions` | Control de acceso a features |
| `venueLogo` | Branding en UI |
| `venueSlug` | Path de Firebase Storage |

### 3.9 Refresh Token

```kotlin
// Renovacion automatica cuando access token expira
suspend fun refreshAccessToken(): Result<RefreshTokenResponse>

// Flujo:
// 1. AuthInterceptor detecta 401
// 2. Llama a refresh endpoint
// 3. Guarda nuevo access token
// 4. Reintenta request original
// 5. Si falla → Logout forzado
```

### 3.10 Logout

```kotlin
fun logout() {
    secureStorage.clearSession()
    permissionsRepository.clearCache()
    socketManager.disconnect()
    // Navega a LoginScreen
}
```

### 3.11 Acceso Rapido a Timeclock

El boton de Timeclock permite a empleados registrar entrada/salida sin hacer login completo:

1. Empleado ingresa PIN
2. Presiona boton ⏱ (Timeclock)
3. Sistema verifica PIN
4. Muestra pantalla de Timeclock
5. Clock in/out disponible
6. Empleado puede salir sin entrar al TPV

---

## 4. Procesamiento de Pagos

### 4.1 Flujo Completo de Pago

```
1. Entrada de Monto
   └── FastPaymentEntry o desde Orden

2. Seleccion de Propina
   └── Porcentajes rapidos (10%, 15%, 20%) o monto custom

3. Seleccion de Merchant (si multi-merchant)
   └── Solo disponible antes del primer pago parcial

4. Verificacion Pre-Pago
   └── Validacion de monto, merchant, permisos

5. Interaccion con Terminal
   └── Blumon SDK: EMV chip, contactless, swipe

6. Procesamiento
   └── Comunicacion con procesador de pagos

7. Registro en Backend
   └── POST /payments con retry exponencial

8. Generacion de Recibo
   └── Digital (email/SMS) o impreso (termica)

9. Actualizacion de Estado
   └── Orden marcada como pagada/parcialmente pagada
```

### 4.2 Integracion Blumon SDK

#### Metodos de Lectura de Tarjeta
| Metodo | Descripcion | Constante |
|--------|-------------|-----------|
| **ICC** | Chip EMV insertado | `CARD_ICC` |
| **PICC** | Contactless/NFC | `CARD_PICC` |
| **MAG_STRIPE** | Banda magnetica | `CARD_SWIPE` |

#### Estados de Transaccion
```kotlin
enum class TransactionStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    DECLINED,
    CANCELLED,
    ERROR,
    TIMEOUT
}
```

#### Configuracion de Terminal
```kotlin
data class TerminalConfig(
    val merchantId: String,
    val terminalId: String,
    val timeout: Int = 60000,  // 60 segundos
    val enableContactless: Boolean = true,
    val enableChip: Boolean = true,
    val enableSwipe: Boolean = true
)
```

### 4.3 Multi-Merchant Support

El TPV soporta multiples merchants en un solo dispositivo:

- **Seleccion de Merchant:** Al inicio de transaccion
- **Bloqueo:** Una vez iniciado split payment, merchant queda fijo
- **Configuracion:** Cada merchant tiene su propia configuracion Blumon
- **Reportes:** Separados por merchant

### 4.4 Registro de Pagos en Backend

#### Endpoint
```
POST /api/v1/payments
```

#### Payload
```json
{
  "orderId": "uuid",
  "merchantId": "merchant_123",
  "amount": 150.00,
  "tip": 22.50,
  "paymentMethod": "CARD",
  "cardType": "VISA",
  "lastFour": "4242",
  "authCode": "ABC123",
  "transactionId": "txn_xxx",
  "terminalId": "terminal_001",
  "employeeId": "emp_456",
  "shiftId": "shift_789"
}
```

#### Retry Strategy
- **Intentos:** 3
- **Backoff:** Exponencial (1s, 2s, 4s)
- **Fallback:** Cola offline si falla

### 4.5 Smart Retry Context

Sistema que preserva datos del usuario despues de errores (patron Square/Toast/Stripe).

#### Filosofia

El usuario **nunca debe perder** datos ingresados despues de un error de tarjeta.

#### RetryContext Model

```kotlin
data class RetryContext(
    // Datos de formulario
    val amount: String,
    val tipAmount: String,
    val rating: Int?,

    // Contexto de merchant
    val merchantAccountId: String?,
    val merchantLocalId: String?,

    // Contexto de orden (si aplica)
    val orderId: String?,
    val orderNumber: String?,
    val splitType: SplitMode?,

    // Estado de split payment
    val equalPartsPartySize: Int?,
    val equalPartsPayedFor: Int?,
    val paidProductIds: List<String>?
) {
    fun calculateTotal(): BigDecimal = amount.toBigDecimal() + tipAmount.toBigDecimal()
    fun isValid(): Boolean = amount.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } ?: false
    fun isOrderPayment(): Boolean = orderId != null
}
```

#### Flujo de Retry

```
1. Usuario ingresa monto, propina, rating
2. Error de tarjeta (timeout, declinada, etc.)
3. PaymentState.Error con RetryContext preservado
4. Usuario toca "Reintentar"
5. Navega directo a DetectingCard (NO a EnteringAmount)
6. Datos de formulario intactos
```

#### Uso en Estados

```kotlin
sealed class PaymentState {
    // Estado de error preserva contexto
    data class Error(
        val message: String,
        val retryContext: RetryContext?  // Permite reintentar sin perder datos
    ) : PaymentState()

    // Estado de exito tiene contexto completo
    data class Success(
        val authCode: String,
        val amount: BigDecimal,
        val tipAmount: BigDecimal,
        val rating: Int?,
        val receipt: PaymentReceipt,
        // ... mas campos
    ) : PaymentState()
}
```

---

## 5. Sistema de Reembolsos

### 5.1 Flujo de Reembolso

```
1. Seleccion de Transaccion
   └── Desde historial de pagos

2. Verificacion de Elegibilidad
   └── Mismo merchant, dentro de ventana de tiempo

3. Seleccion de Monto
   └── Total o parcial

4. Motivo de Reembolso
   └── Requerido para compliance

5. Autorizacion
   └── Puede requerir PIN de supervisor

6. Procesamiento via Blumon
   └── Reverso de transaccion original

7. Registro en Backend
   └── POST /refunds
```

### 5.2 Tipos de Reembolso

| Tipo | Descripcion |
|------|-------------|
| **FULL** | Reembolso total del monto |
| **PARTIAL** | Reembolso de monto especifico |
| **VOID** | Cancelacion antes de batch close |

### 5.3 Motivos de Reembolso
```kotlin
enum class RefundReason {
    CUSTOMER_REQUEST,
    WRONG_AMOUNT,
    DUPLICATE_CHARGE,
    PRODUCT_RETURN,
    SERVICE_ISSUE,
    OTHER
}
```

### 5.4 Blumon Operation Number

Para procesar reembolsos, el TPV guarda el numero de operacion de Blumon:

```kotlin
data class PaymentContext(
    // ...
    val blumonOperationNumber: Int?  // Requerido para refunds
)

// El refund usa este numero en lugar de esperar webhook
// Permite refunds inmediatos sin latencia de webhook
```

### 5.5 Validaciones
- Transaccion original debe existir
- Mismo merchant que proceso el pago
- Monto no puede exceder original
- Dentro de ventana de reembolso (configurable)
- Permisos de empleado
- `blumonOperationNumber` presente (para tarjeta)

---

## 6. Sistema de Propinas

### 6.1 Opciones de Propina

#### Porcentajes Rapidos (Configurables)
- 10% del subtotal
- 15% del subtotal
- 20% del subtotal

#### Monto Personalizado
- Entrada manual de cantidad
- Validacion de monto maximo (opcional)

#### Sin Propina
- Opcion explicita de $0

### 6.2 Calculo de Propina

```kotlin
data class TipCalculation(
    val subtotal: Double,
    val tipPercentage: Double?,
    val tipAmount: Double,
    val total: Double
)

fun calculateTip(subtotal: Double, percentage: Double): TipCalculation {
    val tipAmount = subtotal * (percentage / 100)
    return TipCalculation(
        subtotal = subtotal,
        tipPercentage = percentage,
        tipAmount = tipAmount,
        total = subtotal + tipAmount
    )
}
```

### 6.3 Propinas en Split Payments

- Propina se puede agregar en cada pago parcial
- Tracking individual por transaccion
- Reportes desglosados

---

## 7. Split Payments (Pagos Divididos)

### 7.1 Modos de Division

| Modo | Descripcion | Uso |
|------|-------------|-----|
| **FULLPAYMENT** | Pago completo | Default |
| **PERPRODUCT** | Por productos seleccionados | Grupos separados |
| **EQUALPARTS** | Partes iguales | "Dividir entre N" |
| **CUSTOMAMOUNT** | Monto especifico | Flexibilidad total |

### 7.2 Flujo de Split Payment

```
1. Seleccion de Modo
   └── Usuario elige como dividir

2. Configuracion
   └── Segun modo: productos, personas, o montos

3. Primer Pago
   └── Merchant queda bloqueado

4. Pagos Subsecuentes
   └── Balance restante mostrado

5. Finalizacion
   └── Cuando balance = 0
```

### 7.3 Estado de Split

```kotlin
data class SplitPaymentState(
    val orderId: String,
    val totalAmount: Double,
    val paidAmount: Double,
    val remainingAmount: Double,
    val payments: List<PartialPayment>,
    val lockedMerchantId: String?,
    val splitMode: SplitMode
)
```

### 7.4 Validaciones
- No cambiar merchant despues del primer pago
- Balance no puede ser negativo
- Cada pago parcial genera transaccion individual
- Recibo consolidado opcional al final

---

## 8. Gestion de Ordenes

### 8.1 Ciclo de Vida de Orden

```
DRAFT → OPEN → IN_PROGRESS → READY → DELIVERED → PAID → CLOSED
                    ↓
                 VOIDED
```

### 8.2 Estructura de Orden

```kotlin
data class Order(
    val id: String,
    val orderNumber: Int,
    val tableId: String?,
    val customerId: String?,
    val items: List<OrderItem>,
    val subtotal: Double,
    val tax: Double,
    val discount: Double,
    val total: Double,
    val status: OrderStatus,
    val createdAt: Instant,
    val createdBy: String,
    val notes: String?,

    // Tracking de mesero
    val waiterId: String?,
    val waiterName: String?,

    // Split payment tracking
    val paidAmount: Double,
    val remainingBalance: Double,
    val merchantAccountId: String?,  // Bloqueado despues del primer pago
    val lastSplitType: SplitMode?
)
```

### 8.3 Items de Orden

```kotlin
data class OrderItem(
    val id: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val modifiers: List<Modifier>,
    val specialInstructions: String?,
    val status: ItemStatus,
    val sentToKitchen: Boolean
)
```

### 8.4 Modificadores

```kotlin
data class Modifier(
    val id: String,
    val name: String,
    val price: Double,
    val category: String  // "ADD", "REMOVE", "SUBSTITUTE"
)
```

### 8.5 Operaciones de Orden

| Operacion | Descripcion | Permisos |
|-----------|-------------|----------|
| **Crear** | Nueva orden | Todos |
| **Editar** | Modificar items | Todos |
| **Void Item** | Cancelar item | Manager+ |
| **Void Order** | Cancelar orden completa | Manager+ |
| **Comp** | Item gratis | Manager+ |
| **Discount** | Aplicar descuento | Manager+ |
| **Transfer** | Mover a otra mesa | Todos |
| **Merge** | Combinar ordenes | Todos |
| **Split** | Dividir orden | Todos |

### 8.6 Sistema de Descuentos

```kotlin
sealed class Discount {
    data class Percentage(val value: Double) : Discount()
    data class FixedAmount(val value: Double) : Discount()
    data class Comp(val reason: String) : Discount()
}
```

### 8.7 Envio a Cocina

- Items se envian automaticamente al agregar
- Opcion de envio manual (hold)
- Estaciones de cocina multiples
- Impresion de comanda

---

## 9. Floor Plan (Gestion de Mesas)

### 9.1 Canvas Interactivo

#### Controles
- **Zoom:** Pinch-to-zoom, botones +/-
- **Pan:** Arrastrar canvas
- **Seleccion:** Tap en elemento

### 9.2 Estados de Mesa

| Estado | Color | Descripcion |
|--------|-------|-------------|
| **AVAILABLE** | Verde | Disponible |
| **OCCUPIED** | Rojo | Con clientes |
| **RESERVED** | Amarillo | Reservada |
| **DIRTY** | Gris | Necesita limpieza |
| **BLOCKED** | Negro | Fuera de servicio |

### 9.3 Elementos del Floor Plan

```kotlin
sealed class FloorElement {
    data class Table(
        val id: String,
        val number: Int,
        val seats: Int,
        val shape: TableShape,
        val position: Position,
        val rotation: Float,
        val status: TableStatus,
        val currentOrderId: String?
    ) : FloorElement()

    data class Wall(
        val start: Position,
        val end: Position,
        val thickness: Float
    ) : FloorElement()

    data class Bar(
        val id: String,
        val seats: Int,
        val position: Position,
        val width: Float
    ) : FloorElement()

    data class Door(
        val position: Position,
        val width: Float,
        val rotation: Float
    ) : FloorElement()

    data class Decoration(
        val type: DecorationType,
        val position: Position
    ) : FloorElement()
}
```

### 9.4 Formas de Mesa

```kotlin
enum class TableShape {
    SQUARE,
    ROUND,
    RECTANGLE,
    OVAL
}
```

### 9.5 Acciones de Mesa

- Abrir nueva orden
- Ver orden actual
- Transferir orden
- Cambiar estado
- Ver historial

---

## 10. Sistema de Turnos (Shifts)

### 10.1 Ciclo de Vida de Turno

```
CLOSED → OPEN → IN_PROGRESS → CLOSING → CLOSED
```

### 10.2 Estructura de Turno

```kotlin
data class Shift(
    val id: String,
    val employeeId: String,
    val employeeName: String,
    val startTime: Instant,
    val endTime: Instant?,
    val status: ShiftStatus,
    val startingCash: Double,
    val endingCash: Double?,
    val cashSales: Double,
    val cardSales: Double,
    val totalSales: Double,
    val tips: Double,
    val refunds: Double,
    val voids: Double,
    val transactionCount: Int
)
```

### 10.3 Operaciones de Turno

| Operacion | Descripcion | Datos |
|-----------|-------------|-------|
| **Clock In** | Iniciar turno | Cash inicial |
| **Clock Out** | Cerrar turno | Cash final |
| **Cash Drop** | Retiro de efectivo | Monto, motivo |
| **Pay In** | Ingreso de efectivo | Monto, motivo |

### 10.4 Reporte de Cierre

```kotlin
data class ShiftReport(
    val shift: Shift,
    val expectedCash: Double,
    val actualCash: Double,
    val variance: Double,
    val salesByPaymentMethod: Map<PaymentMethod, Double>,
    val salesByCategory: Map<String, Double>,
    val topProducts: List<ProductSales>,
    val hourlyBreakdown: List<HourlySales>
)
```

### 10.5 Cache Offline de Turnos

- Turno activo se cachea localmente
- Sync automatico al reconectar
- Indicador visual de estado offline

---

## 11. Reloj de Empleados (Timeclock)

### 11.1 Funcionalidades

| Accion | Descripcion |
|--------|-------------|
| **Clock In** | Registrar entrada |
| **Clock Out** | Registrar salida |
| **Start Break** | Iniciar descanso |
| **End Break** | Terminar descanso |

### 11.2 Estructura de Registro

```kotlin
data class TimeclockEntry(
    val id: String,
    val employeeId: String,
    val type: EntryType,
    val timestamp: Instant,
    val photoUrl: String?,
    val location: LatLng?,
    val deviceId: String
)

enum class EntryType {
    CLOCK_IN,
    CLOCK_OUT,
    BREAK_START,
    BREAK_END
}
```

### 11.3 Captura de Foto (Opcional)

- Foto selfie al clock in/out
- Subida a Firebase Storage
- Verificacion visual de identidad
- Configurable por venue

### 11.4 Validaciones

- No puede clock out sin clock in
- Descanso maximo configurable
- Alertas de horas extras
- Geolocalizacion opcional

---

## 12. Reportes y Analiticas

### 12.1 Periodos Disponibles

| Periodo | Descripcion |
|---------|-------------|
| **HOY** | Dia actual |
| **7D** | Ultimos 7 dias |
| **30D** | Ultimos 30 dias |
| **90D** | Ultimos 90 dias |
| **CUSTOM** | Rango personalizado |

### 12.2 Metricas Principales

```kotlin
data class SalesMetrics(
    val totalSales: Double,
    val transactionCount: Int,
    val averageTicket: Double,
    val totalTips: Double,
    val totalRefunds: Double,
    val netSales: Double
)
```

### 12.3 Desgloses Disponibles

| Desglose | Descripcion |
|----------|-------------|
| **Por hora** | Ventas por hora del dia |
| **Por empleado** | Ventas por empleado |
| **Por categoria** | Ventas por categoria de producto |
| **Por producto** | Top productos vendidos |
| **Por metodo de pago** | Efectivo vs tarjeta |
| **Por merchant** | Si multi-merchant |

### 12.4 Comparacion de Periodos

```kotlin
data class PeriodComparison(
    val currentPeriod: SalesMetrics,
    val previousPeriod: SalesMetrics,
    val salesChange: Double,      // Porcentaje
    val transactionsChange: Double,
    val averageTicketChange: Double
)
```

### 12.5 Exportacion

- PDF con graficas
- CSV para Excel
- Envio por email

### 12.6 Paginacion con Cursor

```kotlin
data class HistoricalResponse(
    val data: List<Transaction>,
    val nextCursor: String?,
    val hasMore: Boolean
)
```

---

## 13. Capacidades Offline

### 13.1 Room Database

#### Entidades Principales

| Entidad | Descripcion | Sync |
|---------|-------------|------|
| **PendingPaymentEntity** | Cola de pagos offline | Auto |
| **DraftOrderEntity** | Ordenes locales | Auto |
| **ProductEntity** | Cache de productos | 24h |
| **CategoryEntity** | Cache de categorias | 24h |
| **EmployeeEntity** | Cache de empleados | Session |
| **ShiftEntity** | Turno activo | Auto |
| **FloorPlanEntity** | Layout de mesas | 24h |

#### Migraciones (14 versiones)

```kotlin
val MIGRATION_1_2 = Migration(1, 2) { database ->
    database.execSQL("ALTER TABLE orders ADD COLUMN merchantId TEXT")
}
// ... hasta MIGRATION_13_14
```

### 13.2 Cola de Pagos Offline

```kotlin
@Entity(tableName = "pending_payments")
data class PendingPaymentEntity(
    @PrimaryKey val localId: String,
    val orderId: String,
    val amount: Double,
    val tip: Double,
    val paymentData: String,  // JSON serializado
    val createdAt: Long,
    val retryCount: Int,
    val lastError: String?
)
```

#### Estrategia de Sync
1. Detectar conectividad restaurada
2. Procesar cola en orden FIFO
3. Retry con backoff exponencial
4. Notificar exito/error por item

### 13.3 Cache de Productos

```kotlin
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val price: Double,
    val categoryId: String,
    val imageUrl: String?,
    val modifiers: String,  // JSON
    val isAvailable: Boolean,
    val cachedAt: Long
)
```

- TTL: 24 horas
- Refresh en background
- Fallback a cache si API falla

### 13.4 Indicadores de Estado

```kotlin
sealed class ConnectivityState {
    object Online : ConnectivityState()
    object Offline : ConnectivityState()
    data class Syncing(val progress: Float) : ConnectivityState()
    data class Error(val message: String) : ConnectivityState()
}
```

---

## 14. Integraciones Backend

### 14.1 API REST (Retrofit)

#### Base URL
```
https://api.avoqado.io/api/v1/
```

#### Endpoints Principales

| Metodo | Endpoint | Descripcion |
|--------|----------|-------------|
| POST | `/auth/login` | Login con PIN |
| POST | `/auth/refresh` | Refresh token |
| GET | `/venues/{id}` | Info de venue |
| GET | `/products` | Lista de productos |
| GET | `/categories` | Categorias |
| GET | `/orders` | Ordenes activas |
| POST | `/orders` | Crear orden |
| PUT | `/orders/{id}` | Actualizar orden |
| POST | `/payments` | Registrar pago |
| POST | `/refunds` | Procesar reembolso |
| GET | `/shifts/current` | Turno actual |
| POST | `/shifts/open` | Abrir turno |
| POST | `/shifts/close` | Cerrar turno |
| GET | `/reports/sales` | Reporte de ventas |
| GET | `/timeclock/entries` | Registros de asistencia |
| POST | `/timeclock/clock-in` | Registrar entrada |
| POST | `/timeclock/clock-out` | Registrar salida |

#### Interceptors

```kotlin
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${tokenManager.accessToken}")
            .addHeader("X-Device-Id", deviceId)
            .addHeader("X-App-Version", BuildConfig.VERSION_NAME)
            .build()
        return chain.proceed(request)
    }
}
```

### 14.2 Socket.IO (Eventos Real-time)

#### Conexion
```kotlin
val socket = IO.socket("https://api.avoqado.io", IO.Options().apply {
    auth = mapOf("token" to accessToken)
    reconnection = true
    reconnectionAttempts = 5
    reconnectionDelay = 1000
})
```

#### Eventos Escuchados

| Evento | Descripcion | Accion |
|--------|-------------|--------|
| `order:created` | Nueva orden creada | Actualizar lista |
| `order:updated` | Orden modificada | Refresh orden |
| `order:paid` | Orden pagada | Actualizar estado |
| `table:status_changed` | Estado de mesa cambio | Refresh floor plan |
| `product:availability` | Disponibilidad cambio | Actualizar menu |
| `kitchen:item_ready` | Item listo en cocina | Notificacion |
| `shift:opened` | Turno abierto | Sync |
| `shift:closed` | Turno cerrado | Sync |
| `command:received` | Comando remoto | Ejecutar |
| `sync:required` | Sync necesario | Trigger sync |

#### Eventos Emitidos

| Evento | Descripcion |
|--------|-------------|
| `join:venue` | Unirse a sala del venue |
| `leave:venue` | Salir de sala |
| `order:send_to_kitchen` | Enviar a cocina |
| `table:claim` | Reclamar mesa |
| `heartbeat` | Keep-alive |

### 14.3 Firebase Storage

#### Usos
- Subida de fotos de timeclock
- Imagenes de productos (cache)
- Recibos escaneados

#### Estructura
```
gs://avoqado-prod/
├── venues/{venueId}/
│   ├── timeclock/{employeeId}/{timestamp}.jpg
│   └── receipts/{transactionId}.pdf
```

### 14.4 Manejo de Errores

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(
        val code: Int,
        val message: String,
        val isRetryable: Boolean
    ) : ApiResult<Nothing>()
    object NetworkError : ApiResult<Nothing>()
}
```

---

## 15. Comandos Remotos (Enterprise)

### 15.1 Comandos Disponibles

| Comando | Descripcion | Parametros |
|---------|-------------|------------|
| `LOCK` | Bloquear dispositivo | mensaje |
| `UNLOCK` | Desbloquear dispositivo | - |
| `MAINTENANCE_MODE` | Modo mantenimiento | enabled |
| `RESTART` | Reiniciar app | delay_seconds |
| `SHUTDOWN` | Apagar dispositivo | - |
| `FORCE_UPDATE` | Forzar actualizacion | version |
| `SYNC_DATA` | Forzar sincronizacion | entities[] |
| `FACTORY_RESET` | Reset de fabrica | confirm_code |
| `CLEAR_CACHE` | Limpiar cache | - |
| `LOGOUT_ALL` | Cerrar todas las sesiones | - |

### 15.2 Estructura de Comando

```kotlin
data class RemoteCommand(
    val id: String,
    val type: CommandType,
    val parameters: Map<String, Any>,
    val issuedBy: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val priority: Priority
)

enum class Priority {
    LOW, NORMAL, HIGH, CRITICAL
}
```

### 15.3 Flujo de Ejecucion

```
1. Comando recibido via Socket.IO
2. Validacion de autenticidad
3. Verificacion de expiracion
4. Ejecucion segun tipo
5. Reporte de resultado al servidor
```

### 15.4 Pantalla de Bloqueo

Cuando se recibe `LOCK`:
- Overlay de pantalla completa
- Muestra mensaje personalizado
- Solo desbloqueable via comando `UNLOCK`
- Persiste en reinicios

---

## 16. Seguridad y Cumplimiento

### 16.1 Autenticacion

#### JWT Tokens
```kotlin
data class AuthTokens(
    val accessToken: String,    // Expira: 15 min
    val refreshToken: String,   // Expira: 7 dias
    val expiresAt: Instant
)
```

#### Refresh Flow
```
1. Access token expira
2. Interceptor detecta 401
3. Intenta refresh con refreshToken
4. Si exitoso: actualiza tokens, reintenta request
5. Si falla: logout, redirigir a login
```

### 16.2 PIN de Empleado

- Almacenado encriptado en SecureStorage
- No se transmite en texto plano
- Hash con salt unico por dispositivo
- Intentos limitados (3) antes de bloqueo temporal

### 16.3 Permisos

```kotlin
enum class Permission {
    PROCESS_PAYMENT,
    PROCESS_REFUND,
    VOID_ORDER,
    VOID_ITEM,
    APPLY_DISCOUNT,
    VIEW_REPORTS,
    MANAGE_SHIFTS,
    MANAGE_EMPLOYEES,
    ACCESS_SETTINGS,
    SUPER_ADMIN
}
```

#### Cache de Permisos
- TTL: 5 minutos
- Refresh en background
- Fallback a cache si offline

### 16.4 PCI-DSS Compliance

- **No almacena PAN completo:** Solo ultimos 4 digitos
- **No almacena CVV:** Nunca
- **Tokenizacion:** Datos sensibles tokenizados
- **Transmision segura:** TLS 1.3
- **Logs sanitizados:** Sin datos de tarjeta

### 16.5 Certificate Pinning

```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add("api.avoqado.io", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .build()
```

### 16.6 Encriptacion Local

- Room DB encriptada con SQLCipher
- SharedPreferences encriptadas
- Keystore para claves

---

## 17. Hardware e Impresion

### 17.1 Terminal PAX

#### Modelos Soportados
- PAX A920
- PAX A920 Pro
- PAX A77
- PAX D220

#### Caracteristicas
- Pantalla tactil
- Lector EMV chip
- Lector contactless NFC
- Lector banda magnetica
- Impresora termica integrada
- Camara (para timeclock)
- WiFi + 4G LTE

### 17.2 Impresion Termica

#### Tipos de Impresion

| Tipo | Descripcion | Ancho |
|------|-------------|-------|
| **Recibo** | Comprobante de pago | 58mm |
| **Comanda** | Orden para cocina | 58mm |
| **Reporte** | Cierre de turno | 58mm |

#### Formato de Recibo

```
================================
        NOMBRE DEL VENUE
     Direccion del local
       Tel: 55 1234 5678
================================

Fecha: 22/12/2024  Hora: 14:30
Ticket: #00042
Mesero: Juan Perez
Mesa: 5

--------------------------------
2x Hamburguesa Clasica    $300.00
   - Extra queso          $20.00
1x Papas grandes          $65.00
1x Refresco               $35.00
--------------------------------
Subtotal:                 $420.00
IVA (16%):                $67.20
--------------------------------
TOTAL:                    $487.20

Propina (15%):            $73.08
--------------------------------
TOTAL CON PROPINA:        $560.28

Pago con: VISA ****4242
Auth: ABC123

================================
     Gracias por su visita!
================================

[QR Code para encuesta]
```

### 17.3 Apertura de Cajon de Dinero

```kotlin
fun openCashDrawer() {
    printerService.sendCommand(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA))
}
```

---

## 18. Configuracion y Personalizacion

### 18.1 Configuracion de Venue

```kotlin
data class VenueConfig(
    val id: String,
    val name: String,
    val address: String,
    val phone: String,
    val timezone: String,
    val currency: String,
    val taxRate: Double,
    val tipPercentages: List<Int>,
    val requireTableForOrder: Boolean,
    val autoSendToKitchen: Boolean,
    val allowOfflinePayments: Boolean,
    val receiptHeader: String,
    val receiptFooter: String,
    val logoUrl: String?
)
```

### 18.2 Configuracion de Terminal

```kotlin
data class TerminalSettings(
    val screenTimeout: Int,         // segundos
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val language: String,
    val dateFormat: String,
    val printerEnabled: Boolean,
    val autoLogoutTime: Int?,       // minutos
    val requirePinForRefund: Boolean,
    val maxOfflineTransactions: Int
)
```

### 18.3 Temas

```kotlin
enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM
}
```

### 18.4 Idiomas Soportados

- Espanol (es-MX) - Default
- Ingles (en-US)

---

## 19. Sistema de Reviews (Calificacion)

El TPV incluye una pantalla de calificacion opcional donde el cliente puede dejar una resena de su experiencia.

### 19.1 Pantalla de Review

**Ubicacion:** `payment/presentation/ReviewScreen.kt`

```kotlin
@Composable
fun ReviewScreen(
    currentReview: Int,           // 0 = sin calificar, 1-5 = estrellas
    amount: String,               // Monto del pago
    onReviewChange: (Int) -> Unit,  // Auto-avance al seleccionar
    onSkip: () -> Unit            // Omitir calificacion
)
```

### 19.2 Caracteristicas

| Feature | Descripcion |
|---------|-------------|
| **5 Estrellas** | Interfaz interactiva con 5 estrellas |
| **Auto-avance** | Al tocar estrella, avanza automaticamente a propina |
| **Saltar** | Boton para omitir calificacion |
| **Configurable** | `TpvSettings.showReviewScreen` |

### 19.3 Estado en PaymentState

```kotlin
data class CollectingRating(
    val amount: String,
    val rating: Int = 0  // 0 = no calificado, 1-5 = estrellas
) : PaymentState()
```

---

## 20. Sistema de Propinas Detallado

Sistema completo de seleccion de propinas con multiples opciones.

### 20.1 Pantalla de Propinas

**Ubicacion:** `payment/presentation/TipScreen.kt`

### 20.2 Opciones de Propina

| Opcion | Descripcion |
|--------|-------------|
| **10%** | Boton rapido - calcula automaticamente |
| **15%** | Boton rapido - calcula automaticamente |
| **20%** | Boton rapido - calcula automaticamente |
| **Personalizado** | Abre modal con entrada manual |
| **Sin propina** | Omitir propina ($0) |

### 20.3 Modal de Propina Personalizada

**Ubicacion:** `core/presentation/components/TipInputBottomSheet.kt`

```kotlin
@Composable
fun TipInputBottomSheet(
    subtotal: BigDecimal,
    onTipConfirmed: (BigDecimal) -> Unit,
    onDismiss: () -> Unit
)
```

**Caracteristicas:**
- Toggle **$/%** para cambiar modo de entrada
- Entrada numerica con teclado personalizado
- Calculo automatico del monto
- Validacion de monto maximo

### 20.4 Estado en PaymentState

```kotlin
data class CollectingTip(
    val amount: String,
    val rating: Int?,
    val selectedTipPercentage: Int? = null,  // 10, 15, 20
    val tipAmount: String = "0"
) : PaymentState()
```

### 20.5 Configuracion

```kotlin
// En TpvSettings
tipSuggestions: List<Int> = listOf(10, 15, 20)  // Porcentajes mostrados
defaultTipPercentage: Int? = null               // Pre-seleccionado
showTipScreen: Boolean = true                   // Mostrar/ocultar
```

---

## 21. Verificacion de Pago

Sistema para verificar transacciones mediante fotos y codigos de barra.

### 21.1 Tipos de Verificacion

| Tipo | Momento | Uso |
|------|---------|-----|
| **PRE-payment** | Antes del pago | Verificar items antes de cobrar |
| **POST-payment** | Despues del pago | Confirmar entrega/servicio |

### 21.2 Pantalla de Verificacion

**Ubicacion:** `verification/presentation/VerificationScreen.kt`

**Secciones:**
1. **Resumen de Pago** - Monto y numero de orden
2. **Fotos** - Grid de fotos capturadas
3. **Codigos** - Lista de productos escaneados
4. **Indicadores** - Requisitos pendientes

### 21.3 Captura de Fotos

**Ubicacion:** `verification/presentation/components/CameraPreviewScreen.kt`

```kotlin
data class VerificationPhoto(
    val localPath: String,
    val status: PhotoUploadStatus,  // PENDING, UPLOADING, UPLOADED, ERROR
    val firebaseUrl: String?,
    val uploadProgress: Float,
    val error: String?
)
```

### 21.4 Escaneo de Codigos

**Ubicacion:** `verification/presentation/components/BarcodeScannerScreen.kt`

```kotlin
data class ScannedProduct(
    val barcode: String,          // Valor del codigo
    val format: String,           // EAN_13, UPC_A, QR_CODE
    val productName: String?,     // Si esta en cache
    val productId: String?,
    val hasInventory: Boolean,
    val quantity: Int = 1
)
```

### 21.5 Estados de Verificacion

```kotlin
// PRE-payment
data class VerifyingPrePayment(
    val amount: String,
    val photos: List<VerificationPhoto>,
    val scannedBarcodes: List<ScannedProduct>,
    val requirePhoto: Boolean,
    val requireBarcode: Boolean
) : PaymentState()

// POST-payment
data class Verifying(
    val paymentId: String,
    val capturedPhotos: List<String>,
    val scannedBarcodes: List<ScannedProduct>,
    val isUploading: Boolean
) : PaymentState()
```

### 21.6 Configuracion

```kotlin
// En TpvSettings
showVerificationScreen: Boolean = false
requireVerificationPhoto: Boolean = false
requireVerificationBarcode: Boolean = false
```

---

## 22. Opciones de Recibo Digital

Multiples opciones para entregar el recibo al cliente.

### 22.1 Opciones Disponibles

| Opcion | Descripcion |
|--------|-------------|
| **QR Code** | Codigo QR para escanear y ver recibo |
| **Email** | Enviar recibo por correo electronico |
| **Impresion** | Imprimir en impresora termica |

### 22.2 Modelo de Recibo

```kotlin
data class PaymentReceipt(
    val paymentId: String,
    val receiptUrl: String,    // URL publica
    val accessKey: String,     // Token de acceso (CUID)
    val amount: BigDecimal,
    val tipAmount: BigDecimal
)
```

### 22.3 QR Code

- Siempre visible en pantalla de exito
- URL publica sin autenticacion
- Texto: "Escanea para ver recibo y dejar calificacion"

### 22.4 Envio por Email

**Dialog:** `EmailReceiptDialog` en PaymentScreen.kt

**Caracteristicas:**
- Busqueda de clientes (debounce 300ms)
- Clientes recientes
- Auto-completar email de cliente
- Entrada manual de email
- Validacion de formato

**Endpoint:**
```
POST /tpv/venues/{venueId}/payments/{paymentId}/send-receipt
Body: { "recipientEmail": "cliente@email.com" }
```

### 22.5 Impresion Termica

**Ubicacion:** `core/printer/PrinterManager.kt`

**Contenido del recibo:**
- Logo de venue
- Direccion y RFC
- Fecha/hora
- Items ordenados
- Metodo de pago
- Desglose (subtotal, propina, total)
- Codigo de autorizacion
- QR para recibo digital

---

## 23. Payment Routing (Multi-Merchant)

Sistema para enrutar pagos a diferentes cuentas de procesador.

### 23.1 Modelo de Merchant

**Ubicacion:** `payment/domain/model/MerchantAccount.kt`

```kotlin
data class MerchantAccount(
    val id: String,                    // CUID del backend
    val serialNumber: String,          // Serial de Blumon
    val posId: String,                 // ID de Momentum API
    val displayName: String,           // Nombre para UI
    val environment: MerchantEnvironment,  // SANDBOX, PRODUCTION
    val isActive: Boolean
)
```

### 23.2 Multi-Merchant SDK Manager

**Ubicacion:** `payment/data/MultiMerchantSDKManager.kt`

**Operaciones:**
```kotlin
suspend fun switchMerchant(targetAccount: MerchantAccount): Result<Unit>
fun isMerchantActive(account: MerchantAccount): Boolean
fun getCurrentMerchant(): MerchantAccount?
fun resetToDefault()
```

**Flujo de cambio:**
```
1. Usuario selecciona cuenta diferente
2. Validar que cuenta este activa
3. Actualizar TerminalConfig.serialNumber
4. Reinicializar SDK Blumon (3-5 segundos)
5. Retornar success/failure
```

### 23.3 Bloqueo de Merchant

Cuando se procesa el primer pago de una orden:
- La orden se "bloquea" a esa cuenta merchant
- Pagos subsecuentes deben usar la misma cuenta
- Campo: `Order.merchantAccountId`

---

## 24. Metodos de Pago (Efectivo vs Tarjeta)

### 24.1 Identificacion de Metodo

**Campo clave:** `merchantAccountId` en PaymentContext

| Valor | Metodo |
|-------|--------|
| `null` | Efectivo (sin procesador) |
| `CUID` | Tarjeta (via Blumon SDK) |

### 24.2 Metodos Soportados

```kotlin
val method: String  // Posibles valores:
- "CASH"           // Efectivo
- "CREDIT_CARD"    // Tarjeta de credito
- "DEBIT_CARD"     // Tarjeta de debito
- "DIGITAL_WALLET" // Apple Pay, Google Pay
```

### 24.3 Datos de Tarjeta

Solo presentes cuando `merchantAccountId != null`:

```kotlin
val authorizationNumber: String?  // "502511"
val referenceNumber: String?      // "000000188231"
val maskedPan: String?           // "411111******1111"
val cardBrand: String?           // "VISA", "MASTERCARD"
val blumonOperationNumber: Int?  // Para refunds
```

### 24.4 Datos de Efectivo

```kotlin
val merchantAccountId: String? = null  // Indica efectivo
val authorizationNumber: String? = null
val referenceNumber: String? = null
val maskedPan: String? = null
```

---

## 25. Sistema de Descuentos y Cupones

Sistema completo para aplicar descuentos, promociones y cupones.

### 25.1 Tipos de Descuento

```kotlin
enum class DiscountType {
    PERCENTAGE,  // Porcentaje (10% off)
    FIXED        // Monto fijo ($50 off)
}
```

### 25.2 Scope de Descuento

```kotlin
enum class DiscountScope {
    ORDER,      // Aplica a toda la orden
    ITEM,       // Aplica a items especificos
    CATEGORY    // Aplica a categoria
}
```

### 25.3 Modelo de Descuento

```kotlin
data class Discount(
    val id: String,
    val name: String,
    val type: DiscountType,
    val value: BigDecimal,           // 10 (%) o 50.00 ($)
    val scope: DiscountScope,
    val conditions: DiscountConditions?,
    val requiresAuthorization: Boolean  // Requiere gerente
)
```

### 25.4 Condiciones de Descuento

```kotlin
data class DiscountConditions(
    val minOrderAmount: BigDecimal?,   // Minimo para aplicar
    val maxOrderAmount: BigDecimal?,   // Maximo para aplicar
    val validDays: List<DayOfWeek>?,   // Dias validos
    val validHoursStart: String?,      // "14:00"
    val validHoursEnd: String?         // "18:00"
)
```

### 25.5 Sistema de Cupones

```kotlin
data class CouponCode(
    val id: String,
    val code: String,              // Codigo a ingresar
    val name: String,
    val type: DiscountType,
    val value: BigDecimal,
    val usesRemaining: Int?,       // Usos restantes
    val validFrom: Instant?,
    val validUntil: Instant?,
    val isValid: Boolean
)
```

### 25.6 Operaciones de Descuento

```kotlin
// Obtener descuentos disponibles
getAvailableDiscounts(venueId, orderId)

// Aplicar descuento predefinido
applyPredefinedDiscount(venueId, orderId, discountId)

// Aplicar descuento manual (gerencial)
applyManualDiscount(venueId, orderId, type, value, reason)

// Validar cupon
validateCoupon(venueId, code)

// Aplicar cupon
applyCoupon(venueId, orderId, code)

// Eliminar descuento
removeDiscount(venueId, orderId, orderDiscountId)
```

### 25.7 Comps y Voids

| Operacion | Descripcion | Permiso |
|-----------|-------------|---------|
| **Comp** | 100% descuento con razon | Manager+ |
| **Void** | Cancelar item con razon | Manager+ |

---

## 26. Pedido Rapido vs Servicio de Mesa

Dos modos de operacion completamente diferentes.

### 26.1 Control de Visibilidad

```kotlin
// En SecureStorage.kt
fun supportsTableService(): Boolean {
    val type = getVenueType()
    return type in listOf("RESTAURANT", "BAR", "CAFE", "FAST_FOOD")
}
```

### 26.2 Tipos de Orden Completos

```kotlin
enum class OrderType {
    DINE_IN,    // Servicio de mesa
    TAKEOUT,    // Para llevar
    DELIVERY,   // Entrega a domicilio
    PICKUP      // Recoger en local
}
```

### 26.3 Pedido Rapido (TAKEOUT)

**Caracteristicas:**

| Aspecto | Valor |
|---------|-------|
| **OrderType** | `TAKEOUT` |
| **tableId** | `null` |
| **Creacion** | Local-first (instantanea) |
| **Sync** | Debounced 2 segundos |
| **Covers** | 1 (default) |
| **Accion primaria** | "Pagar" |
| **Visibilidad** | Siempre |

**Flujo:**
```
WelcomeScreen → OrderingWelcomeScreen → MenuScreen (TAKEOUT)
                      ↓
              "Pedido Rapido"
```

### 26.4 Servicio de Mesa (DINE_IN)

**Caracteristicas:**

| Aspecto | Valor |
|---------|-------|
| **OrderType** | `DINE_IN` |
| **tableId** | Requerido |
| **Creacion** | Backend-first |
| **Cocina** | PENDING → PREPARING → READY → SERVED |
| **Covers** | 2+ (editable) |
| **Accion primaria** | "Enviar a cocina" |
| **Visibilidad** | Solo si supportsTableService() |

**Flujo:**
```
WelcomeScreen → OrderingWelcomeScreen → FloorPlanCanvasScreen → MenuScreen (DINE_IN)
                      ↓
              "Servicio de Mesa"
```

### 26.5 Comparacion

| Feature | TAKEOUT | DINE_IN |
|---------|---------|---------|
| Mesa asignada | No | Si |
| Workflow cocina | No | Si |
| Creacion orden | Local-first | Backend-first |
| Boton primario | Pagar | Enviar cocina |
| FloorPlan | No usado | Requerido |
| Banner sin pagar | Si | No |

---

## 27. Tabs de MenuScreen

Interface de 4 tabs estilo Square POS.

### 27.1 Estructura

```kotlin
enum class OrderTab {
    MENU,     // Tab 1: Productos
    CHECK,    // Tab 2: Revision
    ACTIONS,  // Tab 3: Acciones
    GUEST     // Tab 4: Cliente
}
```

### 27.2 Tab MENU

**Ubicacion:** `ordering/presentation/menu/MenuTab.kt`

**Funciones:**
- Navegar categorias de productos
- Buscar por nombre, descripcion, SKU
- Agregar items (con o sin modificadores)
- Pull-to-refresh

### 27.3 Tab CHECK

**Ubicacion:** `ordering/presentation/menu/CheckTab.kt`

**Funciones:**
- Ver items de la orden
- Modificar cantidades
- Eliminar items
- Ver descuentos aplicados
- Botones: "Pagar", "Dividir"
- (DINE_IN) "Enviar a cocina", imprimir comanda

### 27.4 Tab ACTIONS

**Ubicacion:** `ordering/presentation/menu/ActionsTab.kt`

**Funciones:**
- Aplicar descuentos predefinidos
- Aplicar descuentos manuales
- Validar y aplicar cupones
- Comp items (cortesia)
- Void items (cancelar)

### 27.5 Tab GUEST

**Ubicacion:** `ordering/presentation/menu/GuestTab.kt`

**Funciones:**
- Buscar cliente existente
- Crear nuevo cliente
- Multi-customer support
- Editar covers (comensales)
- Editar nombre, telefono
- Ver puntos de lealtad

---

## 28. Configuracion de Flujo (TpvSettings)

Configuracion centralizada del flujo de pago.

### 28.1 Modelo Completo

```kotlin
data class TpvSettings(
    // Pantallas de pago
    val showReviewScreen: Boolean = true,
    val showTipScreen: Boolean = true,
    val showReceiptScreen: Boolean = true,

    // Verificacion
    val showVerificationScreen: Boolean = false,
    val requireVerificationPhoto: Boolean = false,
    val requireVerificationBarcode: Boolean = false,

    // Propinas
    val defaultTipPercentage: Int? = null,
    val tipSuggestions: List<Int> = listOf(10, 15, 20),

    // Turnos
    val enableShifts: Boolean = true,
    val requireClockInPhoto: Boolean = false,

    // Login
    val requirePinLogin: Boolean = true
)
```

### 28.2 Flujo Configurable

```
FastPaymentEntry (monto)
        ↓
[Si showReviewScreen=true]
ReviewScreen (calificacion)
        ↓
[Si showTipScreen=true]
TipScreen (propina)
        ↓
[Si showVerificationScreen=true && PRE]
VerificationScreen (fotos + barcodes)
        ↓
MerchantSelection (si multi-merchant)
        ↓
Payment Processing (Blumon SDK)
        ↓
Success (QR + opciones email/print)
        ↓
[Si showVerificationScreen=true && POST]
VerificationScreen (confirmacion)
```

### 28.3 Persistencia

- Settings se cargan durante login
- Se guardan en SecureStorage
- Se refrescan via `TpvSettingsRepository.refreshSettings()`

---

## 29. Activacion de Terminal

Proceso de vinculacion de un dispositivo fisico con una cuenta de venue.

### 29.1 Flujo de Activacion

**Ubicacion:** `features/activation/presentation/ActivationViewModel.kt`

```
1. Usuario obtiene codigo de activacion (6 caracteres alfanumericos)
   └── Generado en Dashboard (valido por 7 dias)

2. Ingresa codigo en ActivationScreen
   └── Validacion: formato y existencia

3. Backend vincula terminal
   └── POST /tpv/activate
   └── Registra serialNumber: "AVQD-{androidId}"

4. Carga configuracion de merchant
   └── GET /tpv/terminal-config
   └── Obtiene cuentas merchant (CUIDs)

5. Navegacion a LoginScreen
   └── Terminal listo para usar
```

### 29.2 Serial Number

```kotlin
// Formato del serial number
val serialNumber = "AVQD-${Settings.Secure.ANDROID_ID}"

// Ejemplo: AVQD-a1b2c3d4e5f6g7h8
```

### 29.3 Estados de Activacion

```kotlin
sealed class ActivationState {
    object Idle : ActivationState()
    object Loading : ActivationState()
    data class Success(val venueId: String) : ActivationState()

    // Errores
    data class InvalidCode(val attemptsRemaining: Int) : ActivationState()
    object TerminalLocked : ActivationState()      // 3 intentos fallidos
    object CodeExpired : ActivationState()          // Codigo > 7 dias
    object AlreadyActivated : ActivationState()     // Terminal ya vinculado
    data class ConfigError(val message: String) : ActivationState()  // Config fallo
}
```

### 29.4 Auto-Retry Mechanism

```kotlin
// Si el servidor esta caido, verifica cada 10 segundos
// si la terminal ya esta activada en backend
fun checkAlreadyActivatedOnBackend() {
    // Cada 10 segundos verifica si terminal existe
    // Si existe → navega a Login automaticamente
    // Util cuando servidor se recupera
}
```

### 29.5 Config Error Handling

Si la activacion es exitosa pero la configuracion de merchant falla:

| Estado | Descripcion | Accion |
|--------|-------------|--------|
| `ConfigError` | Terminal activado pero sin config | Bloquea navegacion |
| Retry | Usuario puede reintentar | `retryConfigFetch()` |
| Fallback | Sin cuentas merchant | Pagos fallaran |

### 29.6 Datos Guardados Post-Activacion

```kotlin
// En SecureStorage
venueId: String           // ID del venue
venueName: String         // Nombre para display
venueSlug: String         // Path de Firebase Storage
venueLogo: String?        // URL del logo
venueType: VenueType      // RESTAURANT, RETAIL, etc.
serialNumber: String      // AVQD-{androidId}
merchantAccounts: List    // Cuentas de procesador
```

---

## 30. Sistema de Modificadores Detallado

Sistema completo de modificadores de producto estilo Square/Toast.

### 30.1 Estructura de Modificadores

**Ubicacion:** `features/ordering/domain/Product.kt`

```kotlin
data class ModifierGroup(
    val id: String,
    val name: String,              // "Termino", "Extras", "Tamano"
    val type: ModifierType,        // SINGLE_CHOICE o MULTIPLE_CHOICE
    val required: Boolean,         // Obligatorio seleccionar
    val displayOrder: Int,         // Orden en UI
    val modifiers: List<ProductModifier>
)

data class ProductModifier(
    val id: String,
    val name: String,              // "Termino medio", "Extra queso"
    val priceAdjustment: BigDecimal,  // 0, +20, -5 (raro)
    val type: ModifierType,
    val required: Boolean
)
```

### 30.2 Tipos de Modificador

| Tipo | UI | Comportamiento |
|------|-----|----------------|
| **SINGLE_CHOICE** | Radio buttons | Solo 1 seleccion por grupo |
| **MULTIPLE_CHOICE** | Checkboxes | Multiples selecciones |

### 30.3 Modificadores Requeridos

```kotlin
// Validacion antes de agregar al carrito
fun canAddToCart(product: Product, selectedModifiers: List<Modifier>): Boolean {
    return product.modifierGroups
        .filter { it.required }
        .all { group ->
            selectedModifiers.any { it.groupId == group.id }
        }
}
```

**Ejemplo de flujo:**
```
Hamburguesa ($120)
├── Termino (REQUIRED, SINGLE_CHOICE)
│   ├── ○ Bien cocido (+$0)
│   ├── ○ Termino medio (+$0)
│   └── ○ Poco cocido (+$0)
├── Extras (OPTIONAL, MULTIPLE_CHOICE)
│   ├── □ Extra queso (+$20)
│   ├── □ Tocino (+$25)
│   └── □ Aguacate (+$30)
└── Sin... (OPTIONAL, MULTIPLE_CHOICE)
    ├── □ Sin cebolla (+$0)
    └── □ Sin jitomate (+$0)
```

### 30.4 Calculo de Precio

```kotlin
fun calculateItemPrice(
    basePrice: BigDecimal,
    quantity: Int,
    modifiers: List<ProductModifier>
): BigDecimal {
    val modifierTotal = modifiers.sumOf { it.priceAdjustment }
    val unitPrice = basePrice + modifierTotal
    return unitPrice * quantity.toBigDecimal()
}
```

### 30.5 Backend DTO

```kotlin
data class ProductModifierGroupDto(
    val modifierGroup: ModifierGroupDto
)

data class ModifierGroupDto(
    val id: String,
    val name: String,
    val modifiers: List<ModifierDto>,
    val type: String,        // "SINGLE_CHOICE" | "MULTIPLE_CHOICE"
    val required: Boolean,
    val displayOrder: Int
)

data class ModifierDto(
    val id: String,
    val name: String,
    val price: String,       // Backend envia precio absoluto
    val type: String,
    val required: Boolean
)
```

---

## 31. Programa de Lealtad de Clientes

Sistema de puntos y segmentacion de clientes.

### 31.1 Modelo de Cliente

**Ubicacion:** `features/ordering/domain/Customer.kt`

```kotlin
data class Customer(
    val id: String,
    val name: String,
    val email: String?,
    val phone: String?,

    // Lealtad
    val loyaltyPoints: Int,        // Puntos acumulados
    val totalVisits: Int,          // Visitas totales
    val totalSpent: BigDecimal,    // Gasto historico

    // Segmentacion
    val customerGroup: CustomerGroup?
)
```

### 31.2 Grupos de Cliente

```kotlin
enum class CustomerGroup {
    VIP,        // Cliente VIP
    EMPLOYEE,   // Empleado (descuentos especiales)
    REGULAR,    // Cliente regular
    NEW         // Cliente nuevo
}
```

### 31.3 Deteccion Automatica

```kotlin
// Cliente frecuente: 10+ visitas
val isFrequent: Boolean = totalVisits >= 10

// Cliente VIP: Grupo VIP O gasto >= $10,000
val isVip: Boolean = customerGroup == VIP || totalSpent >= 10_000
```

### 31.4 Display Formateado

```kotlin
// Puntos: "1,250 pts"
fun formatLoyaltyPoints(): String = "$loyaltyPoints pts"

// Gasto: "$12,345.67"
fun formatTotalSpent(): String = NumberFormat.getCurrencyInstance().format(totalSpent)
```

### 31.5 Multi-Customer Orders

```kotlin
data class OrderCustomer(
    val orderId: String,
    val customerId: String,
    val isPrimary: Boolean  // Primer cliente = recibe puntos
)

// El primer cliente agregado es el "primary"
// Los puntos de lealtad se acreditan al primary
```

### 31.6 Busqueda de Clientes

```kotlin
// Debounce de 300ms para busqueda
// Busca por: nombre, email, telefono
// Muestra clientes recientes primero
fun searchCustomers(query: String): List<Customer>
```

### 31.7 Badge de Cliente

| Condicion | Badge | Color |
|-----------|-------|-------|
| VIP Group | "VIP" | Dorado |
| totalSpent >= $10,000 | "VIP" | Dorado |
| 10+ visitas | "Frecuente" | Azul |
| Employee | "Empleado" | Verde |

---

## 32. Barcode Quick Add

Sistema de escaneo rapido estilo Square "Scan & Go".

### 32.1 Pantalla de Escaneo

**Ubicacion:** `features/ordering/presentation/menu/BarcodeQuickAddScreen.kt`

### 32.2 Caracteristicas

| Feature | Descripcion |
|---------|-------------|
| **Escaneo continuo** | No cierra camara despues de cada escaneo |
| **Feedback inmediato** | Toast: "✓ Pizza Margherita agregada" |
| **Contador** | Muestra items agregados en sesion |
| **Boton Listo** | Sale del modo escaneo |

### 32.3 Activacion

```kotlin
// Tecla VOLUME+ en MenuScreen (cuando hay orden activa)
// Abre BarcodeQuickAddScreen
```

### 32.4 Flujo de Escaneo

```
1. Escanear codigo de barras
   └── Formatos: EAN-13, UPC-A, QR_CODE

2. Buscar producto en cache local
   └── Si existe: agregar a orden
   └── Si no existe: mostrar dialog "Crear producto"

3. Mostrar confirmacion
   └── Toast overlay: "✓ {ProductName} agregado"
   └── Actualizar contador

4. Continuar escaneando
   └── Camara sigue activa
```

### 32.5 Quick Add API (Crear producto on-the-fly)

```kotlin
// Endpoint para crear producto desde barcode
POST /venues/{venueId}/products/quick-add

data class QuickAddRequest(
    val barcode: String,
    val name: String,
    val price: BigDecimal,
    val categoryId: String,
    val trackInventory: Boolean = false
)
```

### 32.6 Busqueda por Barcode

```kotlin
// Buscar producto existente por codigo
GET /venues/{venueId}/products/barcode/{barcode}

// Retorna ProductResponse si existe
// 404 si no existe
```

---

## 33. Sistema de Inventario

Control de stock desde el TPV.

### 33.1 Metodos de Inventario

```kotlin
enum class InventoryMethod {
    QUANTITY,  // Conteo directo (10 unidades)
    RECIPE     // Por receta (porciones calculadas)
}
```

### 33.2 Modelo de Inventario

```kotlin
data class ProductInventory(
    val trackInventory: Boolean,       // Habilitar tracking
    val inventoryMethod: InventoryMethod?,
    val availableQuantity: Int,        // Unidades disponibles
    val currentStock: Int,             // Stock actual
    val reservedStock: Int,            // Reservado por ordenes pendientes
    val minimumStock: Int,             // Alerta de stock bajo
    val maximumStock: Int?             // Maximo (opcional)
)
```

### 33.3 Calculo de Disponibilidad

```kotlin
// QUANTITY: Disponible = Stock - Reservado
val available = currentStock - reservedStock

// RECIPE: Backend calcula porciones basado en ingredientes
// TPV solo recibe `availableQuantity` ya calculado
```

### 33.4 Display en UI

| Disponibilidad | Display |
|----------------|---------|
| > minimumStock | "{X} disponibles" (verde) |
| <= minimumStock | "{X} disponibles" (amarillo) |
| 0 | "Agotado" (rojo, deshabilitado) |

### 33.5 86'd Items (Agotados)

```kotlin
// Producto marcado como agotado temporalmente
data class Product(
    // ...
    val isEightySixed: Boolean,  // 86'd = no disponible
    val eightySixedReason: String?
)

// 86'd se usa cuando:
// - Se acaba un ingrediente clave
// - Cocina no puede preparar temporalmente
// - Diferente de inventario 0
```

### 33.6 Actualizacion en Tiempo Real

```kotlin
// Socket event cuando cambia disponibilidad
socket.on("product:availability") { data ->
    val productId = data.productId
    val available = data.availableQuantity
    val is86d = data.isEightySixed
    // Actualizar UI inmediatamente
}
```

---

## 34. SuperAdminScreen

Pantalla de administracion avanzada para diagnosticos y configuracion del terminal.

### 34.1 Acceso

**Ubicacion:** `core/presentation/screens/SuperAdminScreen.kt`

- Solo accesible por roles SUPERADMIN y OWNER
- Acceso desde menu de Settings

### 34.2 Funciones Disponibles

| Funcion | Descripcion |
|---------|-------------|
| **Test de Impresora** | Imprime recibo de prueba para verificar impresora termica |
| **Info de Terminal** | Serial number, modelo, version de app |
| **Test de Red** | Verifica conectividad con backend |
| **Limpiar Cache** | Borra datos cacheados localmente |
| **Health Check API** | Verifica estado del backend |
| **Test Crashlytics** | Genera crash fatal y error no-fatal para probar logging |
| **Pago de Prueba** | Procesa $10.00 en sandbox para verificar SDK |

### 34.3 Estado del SuperAdmin

```kotlin
data class SuperAdminState(
    val deviceInfo: DeviceInfo?,
    val isLoading: Boolean = false,
    val lastOperationResult: OperationResult? = null
)

data class DeviceInfo(
    val serialNumber: String,
    val deviceModel: String,
    val appVersion: String,
    val androidVersion: String,
    val buildType: String  // "debug", "release", "sandbox"
)
```

### 34.4 Test de Crashlytics

```kotlin
// Para probar integracion con Firebase Crashlytics
fun testFatalCrash() {
    throw RuntimeException("Test fatal crash from SuperAdmin")
}

fun testNonFatalError() {
    Firebase.crashlytics.recordException(
        Exception("Test non-fatal error from SuperAdmin")
    )
}
```

---

## 35. SupportScreen y Ayuda

Sistema de soporte y ayuda integrado en el TPV.

### 35.1 Pantalla de Soporte

**Ubicacion:** `features/support/presentation/SupportScreen.kt`

### 35.2 Opciones de Contacto

| Canal | Valor |
|-------|-------|
| **Email** | hola@avoqado.io |
| **Telefono** | +52 56 400 70001 |
| **WhatsApp** | Link directo |

### 35.3 Acciones Rapidas

| Accion | Descripcion |
|--------|-------------|
| **Reportar Bug** | Dialog para describir problema (min 10 chars) |
| **Sugerir Feature** | Dialog para sugerencias |

### 35.4 Envio de Feedback

```kotlin
data class FeedbackRequest(
    val type: FeedbackType,      // BUG_REPORT, FEATURE_REQUEST
    val message: String,
    val deviceInfo: DeviceInfo,  // Auto-incluido
    val venueSlug: String        // Auto-incluido
)

// Endpoint
POST /api/v1/feedback
// El backend envia email a hola@avoqado.io
```

### 35.5 Informacion de App

| Campo | Ejemplo |
|-------|---------|
| Version | 2.4.1 |
| Build | 241 |
| Android | 12 (API 31) |
| Dispositivo | PAX A920 |

### 35.6 FAQ Integrado

6 preguntas frecuentes con respuestas expandibles:
- Como procesar un reembolso
- Que hacer si el pago falla
- Como abrir/cerrar turno
- Como dividir una cuenta
- Que hacer sin conexion
- Como contactar soporte

### 35.7 Links de Documentacion

| Recurso | URL |
|---------|-----|
| Guia de Usuario | docs.avoqado.io/tpv/user-guide |
| Video Tutoriales | youtube.com/@avoqado |

---

## 36. Modo Sandbox/Demo

Sistema de build variants para pruebas sin pagos reales.

### 36.1 Build Variants

```
app/src/
├── main/          # Codigo compartido
├── sandbox/       # Configuracion de pruebas
│   └── java/.../features/payment/
│       ├── data/InitializationManager.kt
│       ├── data/BlumonInitializer.kt
│       └── presentation/PaymentViewModel.kt
└── production/    # Configuracion de produccion
```

### 36.2 Diferencias

| Aspecto | Sandbox | Production |
|---------|---------|------------|
| **Pagos** | Simulados | Reales |
| **Blumon SDK** | Modo test | Modo produccion |
| **Backend** | staging.api.avoqado.io | api.avoqado.io |
| **Transacciones** | No se cobran | Se cobran |

### 36.3 Uso

```bash
# Compilar sandbox
./gradlew assembleSandboxDebug

# Compilar produccion
./gradlew assembleProductionRelease
```

### 36.4 Indicador Visual

- Badge "SANDBOX" visible en UI cuando esta en modo prueba
- Color diferente en header (amarillo warning)

---

## 37. Impresion de Comandas (Cocina)

Sistema de impresion separado para ordenes de cocina.

### 37.1 Diferencia con Recibos

| Tipo | Proposito | Contenido |
|------|-----------|-----------|
| **Recibo** | Cliente | Desglose completo, pago, totales |
| **Comanda** | Cocina | Items, modificadores, mesa, notas |

### 37.2 Formato de Comanda

```
================================
        COMANDA DE COCINA
================================

Mesa: 5          Mesero: Juan
Hora: 14:30      Orden: #0042

--------------------------------
2x Hamburguesa Clasica
   >> TERMINO MEDIO
   >> SIN CEBOLLA
   >> Extra queso (+$20)

1x Ensalada Caesar
   >> Sin crutones

--------------------------------
NOTA: Cliente alergico a nueces
================================
```

### 37.3 Estaciones de Cocina

```kotlin
enum class KitchenStation {
    GRILL,      // Parrilla
    FRYER,      // Freidora
    SALAD,      // Ensaladas
    DESSERT,    // Postres
    BAR,        // Bebidas
    GENERAL     // General
}

// Cada producto puede tener estacion asignada
// La comanda se imprime en la estacion correcta
```

### 37.4 Impresion Incremental

```kotlin
// Solo imprime items NUEVOS cuando se envia a cocina
// No reimprime items ya enviados anteriormente
fun printKitchenOrder(order: Order) {
    val newItems = order.items.filter { !it.sentToKitchenAt }
    if (newItems.isNotEmpty()) {
        printerManager.printKitchenTicket(newItems, order.metadata)
    }
}
```

---

## 38. Covers y Notas de Orden

Sistema de seguimiento de comensales e instrucciones especiales.

### 38.1 Covers (Comensales)

```kotlin
data class Order(
    // ...
    val covers: Int,           // Numero de comensales
    val guestName: String?     // Nombre del grupo/reservacion
)
```

### 38.2 Uso de Covers

| Uso | Descripcion |
|-----|-------------|
| **Reportes** | Ticket promedio por persona |
| **Cocina** | Preparar porciones adecuadas |
| **Servicio** | Saber cuantos cubiertos poner |

### 38.3 Edicion de Covers

- Se establece al crear orden en DINE_IN
- Editable desde GuestTab
- Default: 2 para mesa, 1 para TAKEOUT

### 38.4 Notas de Orden

```kotlin
data class Order(
    // ...
    val notes: String?  // Notas generales de la orden
)

data class OrderItem(
    // ...
    val notes: String?  // Instrucciones especificas del item
)
```

### 38.5 Ejemplos de Notas

| Nivel | Ejemplo |
|-------|---------|
| **Orden** | "Mesa de cumpleanos - traer pastel al final" |
| **Item** | "Sin sal", "Muy picante", "Alergico a mariscos" |

### 38.6 Visibilidad de Notas

- **Comanda cocina:** Notas de items visibles
- **Recibo:** Notas NO se imprimen (privacidad)
- **UI:** Icono de nota en items con instrucciones

---

## 39. Void vs Refund

Diferencias entre cancelacion y reembolso.

### 39.1 Definiciones

| Operacion | Momento | Efecto |
|-----------|---------|--------|
| **Void** | Antes de batch close | Cancela transaccion, no aparece en estado de cuenta |
| **Refund** | Despues de batch close | Nueva transaccion de credito |

### 39.2 Void (Anulacion)

```kotlin
// Void: Cancela transaccion el mismo dia antes de cierre
data class VoidRequest(
    val transactionId: String,
    val reason: VoidReason,
    val authorizedBy: String?  // PIN de supervisor si requerido
)

enum class VoidReason {
    CUSTOMER_CANCELLED,
    WRONG_AMOUNT,
    DUPLICATE,
    EMPLOYEE_ERROR
}
```

**Caracteristicas:**
- Solo disponible antes del batch close (generalmente 11pm)
- No genera cargo al comercio
- Transaccion desaparece del estado de cuenta del cliente
- Mas rapido que refund

### 39.3 Refund (Reembolso)

```kotlin
// Refund: Devuelve dinero despues de batch close
data class RefundRequest(
    val originalTransactionId: String,
    val amount: BigDecimal,        // Puede ser parcial
    val reason: RefundReason,
    val authorizedBy: String?
)

enum class RefundReason {
    CUSTOMER_REQUEST,
    PRODUCT_RETURN,
    SERVICE_ISSUE,
    PRICE_ADJUSTMENT,
    OTHER
}
```

**Caracteristicas:**
- Disponible en cualquier momento (dentro de ventana de 180 dias)
- Genera nueva transaccion de credito
- Puede ser parcial
- Tarda 3-5 dias en reflejarse

### 39.4 Flujo de Decision

```
Transaccion a revertir
        ↓
¿Mismo dia y antes de batch close?
        ↓
   Si → VOID (preferido)
   No → REFUND (unica opcion)
```

### 39.5 Permisos

| Operacion | Rol Minimo |
|-----------|------------|
| Void | MANAGER |
| Refund | ADMIN |
| Refund > $500 | OWNER |

---

## 40. Observabilidad y Diagnosticos

Sistema de monitoreo y logging del TPV.

### 40.1 Componentes

**Ubicacion:** `core/observability/`

| Componente | Funcion |
|------------|---------|
| **ObservabilityManager** | Coordinador central |
| **RemoteLogger** | Envia logs al backend |
| **FileLogger** | Logs locales en archivo |
| **HealthMonitor** | Monitorea salud del sistema |

### 40.2 Firebase Crashlytics

```kotlin
// Crash reporting automatico
Firebase.crashlytics.apply {
    setUserId(staffId)
    setCustomKey("venue_id", venueId)
    setCustomKey("terminal_serial", serialNumber)
    setCustomKey("app_version", BuildConfig.VERSION_NAME)
}

// Logging de errores no-fatales
fun logError(tag: String, message: String, error: Throwable?) {
    Firebase.crashlytics.log("$tag: $message")
    error?.let { Firebase.crashlytics.recordException(it) }
}
```

### 40.3 Health Monitor

```kotlin
data class SystemHealth(
    val memoryUsage: Float,        // 0.0 - 1.0
    val batteryLevel: Int,         // 0 - 100
    val networkLatency: Long,      // ms
    val diskSpace: Long,           // bytes disponibles
    val lastSyncTime: Instant?,
    val pendingPayments: Int,
    val sdkStatus: SdkStatus
)

enum class SdkStatus {
    READY,
    INITIALIZING,
    ERROR,
    NOT_CONFIGURED
}
```

### 40.4 Remote Logging

```kotlin
// Logs importantes se envian al backend
POST /api/v1/tpv/logs

data class LogEntry(
    val level: LogLevel,      // DEBUG, INFO, WARN, ERROR
    val tag: String,
    val message: String,
    val timestamp: Instant,
    val metadata: Map<String, Any>
)
```

### 40.5 Diagnosticos Locales

```kotlin
// Archivo de logs rotativo
// Ubicacion: /data/data/com.jaac.avoqado_tpv/files/logs/
// Rotacion: Cada 5MB o 7 dias
// Maximo: 3 archivos
```

---

## 41. Auditoria y Compliance

Sistema de registro para cumplimiento regulatorio.

### 41.1 Audit Log Repository

**Ubicacion:** `core/audit/AuditLogRepository.kt`

### 41.2 Eventos Auditados

| Evento | Datos Registrados |
|--------|-------------------|
| **LOGIN** | staffId, timestamp, deviceId, success/failure |
| **LOGOUT** | staffId, timestamp, reason |
| **PAYMENT** | amount, method, authCode, staffId |
| **REFUND** | originalTxn, amount, reason, authorizedBy |
| **VOID** | transactionId, reason, authorizedBy |
| **SHIFT_OPEN** | staffId, startingCash, timestamp |
| **SHIFT_CLOSE** | staffId, expectedCash, actualCash, variance |
| **DISCOUNT** | orderId, discountType, value, authorizedBy |
| **COMP** | orderId, itemId, reason, authorizedBy |
| **CONFIG_CHANGE** | setting, oldValue, newValue, changedBy |

### 41.3 Modelo de Audit Log

```kotlin
data class AuditEntry(
    val id: String,
    val eventType: AuditEventType,
    val timestamp: Instant,
    val staffId: String,
    val staffName: String,
    val venueId: String,
    val deviceId: String,
    val details: Map<String, Any>,
    val ipAddress: String?,
    val syncedToBackend: Boolean
)
```

### 41.4 Retencion de Datos

| Tipo | Retencion Local | Retencion Backend |
|------|-----------------|-------------------|
| Transacciones | 90 dias | 7 anos |
| Logins | 30 dias | 1 ano |
| Config changes | 30 dias | 3 anos |

### 41.5 PCI-DSS Compliance

```kotlin
// Datos de tarjeta NUNCA se almacenan
// Solo se guarda:
data class CardAuditInfo(
    val lastFour: String,     // "4242"
    val cardBrand: String,    // "VISA"
    val entryMode: String     // "CHIP", "CONTACTLESS", "SWIPE"
    // NO: PAN completo, CVV, PIN, track data
)
```

### 41.6 Exportacion de Auditorias

```kotlin
// Para auditorias externas
GET /api/v1/venues/{venueId}/audit-logs
Query params:
  - startDate: ISO date
  - endDate: ISO date
  - eventTypes: comma-separated
  - format: json | csv

// Requiere rol OWNER o SUPERADMIN
```

---

## Apendice A: Codigos de Error

| Codigo | Descripcion | Accion |
|--------|-------------|--------|
| `E001` | Token expirado | Refresh o login |
| `E002` | Permisos insuficientes | Contactar admin |
| `E003` | Merchant no encontrado | Verificar config |
| `E004` | Transaccion declinada | Intentar otro metodo |
| `E005` | Error de red | Verificar conexion |
| `E006` | Error de impresora | Verificar papel |
| `E007` | Turno no abierto | Abrir turno |
| `E008` | Orden no encontrada | Refresh |
| `E009` | Monto invalido | Verificar entrada |
| `E010` | Reembolso excede original | Ajustar monto |

---

## Apendice B: Flujos de Usuario Comunes

### B.1 Pago Rapido (Sin Orden)

```
WelcomeScreen
    ↓ tap "Pago Rapido"
FastPaymentEntryScreen
    ↓ ingresar monto
TipSelectionOverlay
    ↓ seleccionar propina
PaymentScreen
    ↓ insertar/tap tarjeta
ReceiptOptionsOverlay
    ↓ enviar recibo
WelcomeScreen
```

### B.2 Orden Completa

```
WelcomeScreen
    ↓ tap "Nueva Orden"
FloorPlanCanvasScreen
    ↓ seleccionar mesa
MenuScreen
    ↓ agregar items
OrderDetailScreen
    ↓ revisar y enviar
PaymentScreen
    ↓ procesar pago
WelcomeScreen
```

### B.3 Reembolso

```
WelcomeScreen
    ↓ tap "Historial"
PaymentsScreen
    ↓ seleccionar transaccion
TransactionDetailOverlay
    ↓ tap "Reembolsar"
RefundReasonDialog
    ↓ seleccionar motivo
RefundConfirmationScreen
    ↓ confirmar
PaymentsScreen
```

---

## Apendice C: Metricas de Performance

| Metrica | Objetivo | Actual |
|---------|----------|--------|
| Tiempo de inicio (cold) | < 3s | ~2.5s |
| Tiempo de inicio (warm) | < 1s | ~0.8s |
| Tiempo de procesamiento de pago | < 5s | ~3s |
| Tamano de APK | < 50MB | ~42MB |
| Uso de memoria (idle) | < 150MB | ~120MB |
| Bateria por hora (activo) | < 15% | ~12% |

---

*Documento generado automaticamente. Para actualizaciones, revisar el codebase directamente.*
