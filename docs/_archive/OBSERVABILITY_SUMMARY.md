# Sistema de Observabilidad - Implementación Completa

**Implementado:** 2025-12-18
**Status:** ✅ Listo para testing

---

## 🎯 Resumen Ejecutivo

Hemos implementado un **sistema de observabilidad enterprise-grade** para monitorear terminales TPV en producción, inspirado en los sistemas de Toast, Square y Clover.

### ✅ Qué se implementó

1. **Firebase Crashlytics** - Reportes automáticos de crashes
2. **Remote Logging** - Logs en tiempo real via Socket.IO al backend
3. **File Logging** - Buffer offline para cuando no hay conexión
4. **Health Monitoring** - Métricas de salud cada 5 minutos (memoria, batería, uptime)
5. **Backend Endpoints** - Socket.IO handlers para recibir logs y heartbeats
6. **Base de datos** - Modelos Prisma para TerminalLog y TerminalHealth
7. **Hilt Module** - Dependency injection configurado
8. **Documentación completa** - Guía de uso en `docs/OBSERVABILITY_GUIDE.md`

---

## 📁 Archivos Creados/Modificados

### Android (avoqado-tpv)

**Nuevos archivos:**
```
app/src/main/java/com/jaac/avoqado_tpv/core/observability/
├── ObservabilityManager.kt                    (Coordinador central)
├── logger/
│   ├── RemoteLogger.kt                        (Socket.IO streaming)
│   └── FileLogger.kt                          (Offline buffer)
├── monitor/
│   └── HealthMonitor.kt                       (Health metrics)
└── di/
    └── ObservabilityModule.kt                 (Hilt DI)

docs/
└── OBSERVABILITY_GUIDE.md                     (Guía completa de uso)
```

**Archivos modificados:**
```
app/build.gradle.kts                           (+ Crashlytics plugin & dependencies)
build.gradle.kts                               (+ Crashlytics plugin version)
```

### Backend (avoqado-server)

**Nuevos archivos:**
```
src/communication/sockets/controllers/
└── observability.controller.ts                (Socket.IO handlers)

prisma/migrations/
└── 20251218200644_add_terminal_observability/ (Database schema)
```

**Archivos modificados:**
```
prisma/schema.prisma                           (+ TerminalLog, TerminalHealth models)
src/communication/sockets/managers/socketManager.ts  (+ ObservabilityController)
```

---

## 🚀 Cómo Usar

### Paso 1: Inicializar después del login

```kotlin
// En LoginViewModel o después de auth exitosa
@Inject lateinit var observabilityManager: ObservabilityManager

observabilityManager.initialize(
    venueId = authContext.venueId,
    terminalId = terminalId,
    userId = authContext.userId
)
```

### Paso 2: Usar en ViewModels

```kotlin
// En PaymentViewModel, MenuViewModel, etc.
@Inject lateinit var observability: ObservabilityManager

try {
    val result = blumonSDK.processPayment(amount)
    observability.logInfo("Payment", "Payment successful", mapOf(
        "amount" to amount.toString()
    ))
} catch (e: Exception) {
    observability.logError("Payment", "Payment failed", e, mapOf(
        "amount" to amount.toString()
    ))
}
```

### Paso 3: Ver logs en dashboard (futuro)

Los logs se envían automáticamente al backend y se pueden visualizar en tiempo real en el dashboard web.

---

## 📊 Flujo de Datos

```
Terminal TPV (Android)
  ↓
ObservabilityManager
  ├→ Firebase Crashlytics (crashes)
  ├→ RemoteLogger → Socket.IO → Backend → PostgreSQL
  ├→ FileLogger → Local disk (offline buffer)
  └→ HealthMonitor → Socket.IO → Backend → PostgreSQL
                                      ↓
                            Dashboard Web (React)
                            - Logs en tiempo real
                            - Health metrics
```

---

## 🔧 Tareas Pendientes

### Alta Prioridad
- [ ] **Inicializar ObservabilityManager** después del login exitoso
- [ ] **Agregar logging** en PaymentViewModel (payment flows críticos)
- [ ] **Agregar logging** en MenuViewModel (sync errors)
- [ ] **Probar** con errores simulados en terminal productiva

### Media Prioridad
- [ ] **Dashboard web** - UI para visualizar logs en tiempo real
- [ ] **Alertas** - Notificaciones Slack/Email para errores críticos
- [ ] **Analytics** - Dashboard de métricas (crash rate, memory trends)

### Baja Prioridad
- [ ] Exportar logs como CSV
- [ ] Búsqueda y filtrado avanzado
- [ ] Integración con Sentry o Datadog

---

## 🧪 Testing

### Test Manual Rápido

1. **Inicializar el sistema:**
```kotlin
observability.initialize("venue123", "terminal456", "user789")
```

2. **Simular un error crítico:**
```kotlin
observability.logCritical("Test", "Test critical error",
    Exception("Simulated error"),
    mapOf("test" to true)
)
```

3. **Verificar:**
- Firebase Console → Crashlytics (debería aparecer en 5-10 min)
- Backend logs → Debería ver "[Terminal terminal456] [Test] Test critical error"
- PostgreSQL → Query `SELECT * FROM "TerminalLog" ORDER BY "createdAt" DESC LIMIT 10`

---

## 💡 Ejemplos de Uso Real

### Payment Flow (Crítico)
```kotlin
try {
    observability.logInfo("Payment", "Payment initiated", mapOf("amount" to amount))
    val result = processPayment(amount)
    observability.logInfo("Payment", "Payment success", mapOf("txId" to result.id))
} catch (e: BlumonException) {
    observability.logCritical("Payment", "Payment failed", e, mapOf(
        "errorCode" to e.errorCode,
        "amount" to amount.toString()
    ))
}
```

### Menu Sync
```kotlin
try {
    val menu = menuRepository.syncMenu()
    observability.logInfo("Menu", "Sync successful", mapOf("items" to menu.size))
} catch (e: NetworkException) {
    observability.logWarning("Menu", "Sync failed (network)", e)
}
```

### Socket.IO Connection
```kotlin
socket.on(Socket.EVENT_CONNECT) {
    observability.logInfo("Socket", "Connected")
}

socket.on(Socket.EVENT_DISCONNECT) {
    observability.logWarning("Socket", "Disconnected")
}
```

---

## 📈 Métricas Recolectadas

### Logs (TerminalLog)
- Level: INFO, WARN, ERROR
- Tag: Componente (Payment, Menu, Socket, etc.)
- Message: Descripción del evento
- Error: Stack trace (si aplica)
- Metadata: Contexto adicional (amount, orderId, etc.)

### Health (TerminalHealth - cada 5 minutos)
- **Memory:** Total, disponible, % uso
- **Storage:** Total, disponible, % uso
- **Battery:** Nivel, cargando, temperatura
- **Connectivity:** Socket.IO conectado, online
- **Device:** Manufacturer, model, OS, app version
- **Uptime:** Minutos desde inicio de app
- **Health Score:** 0-100 (90+ = sano, 70-89 = degradado, <70 = crítico)

---

## 🎓 Documentación

**Guía completa:** `docs/OBSERVABILITY_GUIDE.md`

Incluye:
- Arquitectura detallada
- API reference
- Ejemplos de uso
- Integración con dashboard
- Troubleshooting
- Production checklist

---

## ⚙️ Configuración Actual

### Android
- **Crashlytics:** ✅ Configurado (solo en release builds)
- **Timber:** ✅ Ya existía (solo en debug)
- **ObservabilityManager:** ✅ Implementado (producción: Crashlytics + Remote + File + Health)
- **Debug builds:** Solo Timber (sin overhead de producción)

### Backend
- **Socket.IO events:** `tpv:log`, `tpv:heartbeat`
- **Database:** PostgreSQL (via Prisma)
- **Retention:** Sin límite (configurar cleanup job en futuro)

---

## 🔒 Seguridad & Privacy

- ❌ **NO loggear:** Números de tarjeta, PINs, contraseñas
- ✅ **SÍ loggear:** Error codes, transaction IDs, metadata no-sensible
- 🔐 **Logs cifrados:** En tránsito (Socket.IO sobre HTTPS)
- 🗑️ **Retención:** Configurar política (default: sin límite)

---

## 📞 Soporte

**Problemas comunes:**

1. **Logs no aparecen en backend**
   - Verificar que ObservabilityManager está inicializado
   - Verificar conexión Socket.IO
   - Verificar que es release build (debug = logs deshabilitados)

2. **Crashlytics no reporta crashes**
   - Verificar `google-services.json` existe
   - Verificar que es release build
   - Esperar 5-10 minutos (Crashlytics tiene delay)

3. **HealthMonitor no envía heartbeats**
   - Verificar que ObservabilityManager.initialize() fue llamado
   - Verificar conexión Socket.IO
   - Esperar 5 minutos (intervalo de heartbeat)

---

## 🎉 Próximos Pasos

1. **Testing inmediato:**
   - Compilar APK productivo
   - Inicializar observability después de login
   - Simular errores y verificar en Firebase + backend

2. **Integración PaymentViewModel:**
   - Agregar logging en todos los payment flows
   - Especialmente en error cases

3. **Dashboard web:**
   - Crear UI para visualizar logs
   - Real-time updates via Socket.IO

4. **Alerting:**
   - Configurar Slack/Email para errores críticos
   - Alertas de salud (batería baja, memoria baja)

---

**¿Preguntas?** Consulta `docs/OBSERVABILITY_GUIDE.md` para la guía completa.
