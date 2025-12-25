# Guía de Testing - Sistema de Observabilidad

**Cómo probar el sistema en Sandbox/Debug**

---

## 🎯 Resumen Rápido

**SÍ, puedes ver logs desde sandbox.** Solo necesitas habilitar el sistema con `enableInDebug=true`.

---

## 📋 Paso 1: Inicializar en Sandbox

### Opción A: En tu ViewModel de login (después de auth exitosa)

```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val observability: ObservabilityManager
) : ViewModel() {

    fun onLoginSuccess(authContext: AuthContext, terminalId: String) {
        // ✅ Habilitar observabilidad en sandbox para testing
        observability.initialize(
            venueId = authContext.venueId,
            terminalId = terminalId,
            userId = authContext.userId,
            enableInDebug = true  // ⭐ Esto lo habilita en sandbox
        )
    }
}
```

### Opción B: Crear un screen de testing

```kotlin
@Composable
fun ObservabilityTestScreen(
    observability: ObservabilityManager = hiltViewModel(),
    tester: ObservabilityTester = hiltViewModel()
) {
    Column {
        Button(onClick = {
            // Inicializar con datos de prueba
            observability.initialize(
                venueId = "test-venue-123",
                terminalId = "test-terminal-456",
                userId = "test-user-789",
                enableInDebug = true
            )
        }) {
            Text("1. Inicializar Observabilidad")
        }

        Button(onClick = {
            viewModelScope.launch {
                tester.runFullTest()
            }
        }) {
            Text("2. Run Full Test")
        }

        Button(onClick = {
            viewModelScope.launch {
                tester.quickTest()
            }
        }) {
            Text("3. Quick Test")
        }

        Button(onClick = {
            viewModelScope.launch {
                tester.testPaymentError()
            }
        }) {
            Text("4. Test Payment Error")
        }
    }
}
```

---

## 🔍 Paso 2: Ver los Logs

### 1️⃣ Logcat (Android Studio)

```bash
# Opción A: Ver todos los logs de observabilidad
adb logcat -c && adb logcat -s ObservabilityManager,RemoteLogger,HealthMonitor,ObservabilityTest

# Opción B: Ver todos los logs Timber
adb logcat -c && adb logcat *:S Timber:V

# Opción C: Filtrar por tag específico
adb logcat -c && adb logcat -s QuickTest,Payment
```

**Deberías ver:**
```
D/ObservabilityManager: ✅ Observability ENABLED (debug=true, release=false)
D/ObservabilityManager: 🚀 Avoqado TPV initialized in DEBUG mode
D/ObservabilityTest: 🧪 Iniciando test de observabilidad...
I/ObservabilityTest: [ObservabilityTest] Test INFO log
W/ObservabilityTest: [ObservabilityTest] Test WARNING log
E/ObservabilityTest: [ObservabilityTest] Test ERROR log
```

### 2️⃣ Backend Logs (Terminal del servidor)

En tu terminal donde corre `avoqado-server`:

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run dev
```

**Deberías ver:**
```
📡 TPV log received
[Terminal test-terminal-456] [ObservabilityTest] Test INFO log
[Terminal test-terminal-456] [ObservabilityTest] Test WARNING log
[Terminal test-terminal-456] [ObservabilityTest] Test ERROR log
📡 TPV heartbeat received (health: 85/100)
```

### 3️⃣ PostgreSQL (Base de datos)

```bash
# Conectar a la DB
psql -U postgres -d av-db-25

# Ver últimos logs
SELECT
  "terminalId",
  level,
  tag,
  message,
  "createdAt"
FROM "TerminalLog"
ORDER BY "createdAt" DESC
LIMIT 20;

# Ver logs de test específicamente
SELECT * FROM "TerminalLog"
WHERE tag = 'ObservabilityTest'
ORDER BY timestamp DESC;

# Ver health metrics
SELECT
  "terminalId",
  "healthScore",
  "memoryUsagePercent",
  "batteryLevel",
  "createdAt"
FROM "TerminalHealth"
ORDER BY "createdAt" DESC
LIMIT 10;
```

**Ejemplo de resultado:**
```
       terminalId       | level |       tag         |      message
------------------------|-------|-------------------|-------------------
 test-terminal-456      | INFO  | ObservabilityTest | Test INFO log
 test-terminal-456      | WARN  | ObservabilityTest | Test WARNING log
 test-terminal-456      | ERROR | ObservabilityTest | Test ERROR log
```

### 4️⃣ Firebase Crashlytics (Para errores críticos)

1. Ve a: https://console.firebase.google.com/
2. Selecciona tu proyecto Avoqado
3. Crashlytics → Events
4. **Nota:** Los crashes tardan 5-10 minutos en aparecer

**Verás:**
```
Exception: TestException
Message: This is a critical test exception
Stack trace: ...
Custom keys:
  - venue_id: test-venue-123
  - terminal_id: test-terminal-456
  - testNumber: 4
  - type: critical
```

### 5️⃣ File Logs (Local en el dispositivo)

Los logs también se guardan localmente:

```bash
# Pull logs desde el dispositivo
adb shell "run-as com.jaac.avoqado_tpv.sandbox cat /data/data/com.jaac.avoqado_tpv.sandbox/files/logs/*.log"

# O si tienes acceso root
adb pull /data/data/com.jaac.avoqado_tpv.sandbox/files/logs/ ./terminal-logs/
```

**Formato:**
```json
{"timestamp":1734567890,"datetime":"2025-12-18 14:30:00","level":"ERROR","tag":"Payment","message":"Payment failed","error":"...","metadata":{...}}
```

---

## 🧪 Tests Disponibles

### Test 1: Full Test (Completo)

```kotlin
viewModelScope.launch {
    observabilityTester.runFullTest()
}
```

**Qué hace:**
- Envía 1 log INFO
- Envía 1 log WARNING
- Envía 1 log ERROR
- Envía 1 log CRITICAL
- Agrega breadcrumbs
- Flush final

**Verificar en:**
- Logcat: Todos los logs
- Backend: 5 logs en consola
- PostgreSQL: 5 filas en `TerminalLog`
- Firebase: 1 crash (5-10 min)

### Test 2: Quick Test (Rápido)

```kotlin
viewModelScope.launch {
    observabilityTester.quickTest()
}
```

**Qué hace:**
- Envía 1 log CRITICAL
- Flush

**Verificar en:**
- Backend logs (inmediato)
- PostgreSQL (5 segundos)

### Test 3: Payment Flow (Simulado)

```kotlin
viewModelScope.launch {
    observabilityTester.testPaymentFlow()
}
```

**Qué hace:**
- Simula un pago exitoso completo
- Logs: initiated → card detected → successful

### Test 4: Payment Error (Simulado)

```kotlin
viewModelScope.launch {
    observabilityTester.testPaymentError()
}
```

**Qué hace:**
- Simula un error de pago
- Log CRITICAL con errorCode "NA_002"

---

## ✅ Checklist de Verificación

Después de correr un test, verifica:

### Inmediato (0-5 segundos)
- [ ] Logcat muestra los logs
- [ ] Backend console muestra `[Terminal xxx] [TestTag] ...`

### Corto plazo (5-10 segundos)
- [ ] PostgreSQL tiene las filas en `TerminalLog`
- [ ] PostgreSQL tiene heartbeat en `TerminalHealth` (si pasaron 5 min)

### Largo plazo (5-10 minutos)
- [ ] Firebase Crashlytics muestra el error crítico

---

## 🐛 Troubleshooting

### ❌ No veo logs en Logcat

**Problema:** Timber no está mostrando logs
**Solución:**
```bash
# Verifica que Timber esté inicializado
adb logcat -s Timber:V

# Si no ves nada, verifica AvoqadoTPVApplication.kt
```

### ❌ No llegan logs al backend

**Verificar:**
1. ¿Socket.IO está conectado?
```kotlin
// En tu código
Timber.d("Socket connected: ${socketService.isConnected()}")
```

2. ¿El backend está corriendo?
```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run dev
```

3. ¿La URL es correcta?
```kotlin
// BuildConfig debe tener SOCKET_URL correcto
Timber.d("Socket URL: ${BuildConfig.SOCKET_URL}")
```

### ❌ No veo logs en PostgreSQL

**Verificar:**
1. Conexión a DB correcta
```bash
psql -U postgres -d av-db-25
```

2. La tabla existe
```sql
\d "TerminalLog"
```

3. Los logs se están guardando
```sql
SELECT COUNT(*) FROM "TerminalLog";
```

### ❌ Firebase Crashlytics no muestra crashes

**Verificar:**
1. `google-services.json` existe en `app/`
2. Build variant es `sandboxDebug` (Crashlytics funciona en debug también)
3. Esperar 5-10 minutos (delay normal de Firebase)
4. Verificar en Firebase console que el proyecto está activo

---

## 📊 Ejemplo de Flujo de Testing Completo

```kotlin
@HiltViewModel
class TestingViewModel @Inject constructor(
    private val observability: ObservabilityManager,
    private val tester: ObservabilityTester
) : ViewModel() {

    init {
        // Paso 1: Inicializar
        observability.initialize(
            venueId = "test-venue",
            terminalId = "test-terminal",
            userId = "test-user",
            enableInDebug = true
        )
    }

    fun runTests() = viewModelScope.launch {
        Timber.d("🚀 Iniciando batería de tests...")

        // Test 1: Quick test
        tester.quickTest()
        delay(2000)

        // Test 2: Payment flow
        tester.testPaymentFlow()
        delay(2000)

        // Test 3: Payment error
        tester.testPaymentError()
        delay(2000)

        // Test 4: Full test
        tester.runFullTest()

        Timber.d("✅ Todos los tests completados")
    }
}
```

**Usar en un botón:**
```kotlin
Button(onClick = { viewModel.runTests() }) {
    Text("Run All Tests")
}
```

---

## 🎓 Siguiente Paso: Producción

Una vez que verifiques que funciona en sandbox:

1. **Compilar release build:**
```bash
./gradlew assembleProductionRelease
```

2. **Instalar en terminal productiva:**
```bash
adb install app/build/outputs/apk/production/release/app-production-release.apk
```

3. **Inicializar después del login:**
```kotlin
// Sin enableInDebug - se habilita automáticamente en release
observability.initialize(venueId, terminalId, userId)
```

4. **Ver logs en producción:**
- Firebase Crashlytics (crashes)
- PostgreSQL (logs completos)
- Dashboard web (futuro - real-time)

---

## 📞 Soporte

**Problemas?**
- Revisa `docs/OBSERVABILITY_GUIDE.md` para guía completa
- Revisa backend logs para errores de Socket.IO
- Verifica que las migraciones de Prisma se ejecutaron

---

**Última actualización:** 2025-12-18
**Versión:** 1.0
