# Audit forense — AMEX chip falla con `FailureSecondGenerate` (-11) solo en sandbox

**Handoff para segunda opinión LLM. Fecha: 2026-05-06. App version: 1.13.6.**

> Este documento es puramente forense — síntoma, evidencia, hipótesis con probabilidades, lo que sabemos y lo que no. NO contiene recomendaciones de fix; ese análisis se delega al LLM auditor.

---

## 1. Síntoma

Cobro chip con tarjeta American Express en **sandbox** falla en `PASO 5 CompleteEmvTrans` con error SDK `-11 / FailureSecondGenerate`. Mismo flujo en **producción** funciona perfecto. Mismo merchant, misma tarjeta AMEX física, mismo APK.

**Mensaje exacto en logcat:**
```
2026-05-06 18:31:45.x E PaymentViewModel$continuePaymentFlow:
❌ [PHASE 5] Complete failed:
com.blumonpay.pax.shared.trans_process.domain.use_case.complete_emv_trans
.CompleteEmvTransFailure$FailureSecondGenerate@xxxx
```

---

## 2. Evidencia comparativa de hoy (2026-05-06)

### Test 1: SANDBOX — AMEX chip → FAIL (op 78396)

```
18:31:36 [MSI] Starting card payment with msi=DIRECT
18:31:44 POST sandbox-core.blumonpay.net/retail/present/charge
         body: {"amount":2.0,"currency":"484","entryMode":"chip",
                "idMembership":"","posId":388,"presentCardData":{...},
                "reference":"20260506183144"}   ← NO hay campo "msi" en el body
18:31:45 <-- 200 OK
         {"status":true,"id":"78396",
          "dataResponse":{"authorization":"D4626D",
            "arpc":"4C4612E7AA0B7999",        ← FAKE constante de simulation
            "renewKey":false,
            "description":"APROBADA",
            "membership":"9109196",
            "binInformation":{"bin":"376668","bank":"AMERICAN EXPRESS",
                              "product":"AMERICAN EXPRES","type":"AMEX",
                              "brand":"AMEX"},
            "reference":"569272876418",
            "batch":"20260506183144",
            "simulation":true,                  ← ⚠️
            "emvResponseCode":null}}            ← null en sandbox
[PHASE 4.5] AIP Checking - ARPC required: true (AIP: 3C00)
[PHASE 5] CompleteEmvTrans - Card requires ARPC, updating chip...
EMV_completeEmvTrans:
  emvResponseCode: 00 (default por null)
  authorization: D4626D
  arpc: 4C4612E7AA0B7999
  script7172: (empty)
  arpcResponseCode: 00
EmvProcess: validateIsArpcSupportedByAIP: AMEX
EmvProcess: arpc:4C4612E7AA0B79993030
❌ FailureSecondGenerate (-11)
```

### Test 2: PRODUCCIÓN — AMEX chip → SUCCESS (op 18086560)

```
18:32:55 POST core.blumonpay.net/retail/present/charge
         body: {"amount":1.00,...,"posId":5685,...}
18:32:57 <-- 200 OK
         {"status":true,"id":"18086560",
          "dataResponse":{"authorization":"815346",
            "arpc":"B4837CECF274F3B6",          ← REAL único firmado
            "emvResponseCode":"3030",            ← REAL (no null)
            "membership":"8481547982",           ← Otro merchant
            "binInformation":{"bin":"376668","bank":"AMERICAN EXPRESS",
                              "type":"AMEX","brand":"AMEX"},
            "reference":"581581292601",
            "batch":"20260506183255"}}
[PHASE 4.5] AIP Checking - ARPC required: true (AIP: 3C00)
[PHASE 5] CompleteEmvTrans - Card requires ARPC, updating chip...
EmvProcess: validateIsArpcSupportedByAIP: AMEX
EmvProcess: arpc:B4837CECF274F3B63030
EmvProcess: completeTransProcess,acType:1
✅ [PHASE 5] EMV completion SUCCESS!
🎉 PAYMENT APPROVED WITH ONLINE AUTHORIZATION!
```

### Test 3: SANDBOX — Mastercard chip → SUCCESS (op 78384, esta mañana)

Mismo merchant `2841548417`, mismo `simulation:true`, misma config MSI:

```
{"status":true,"id":"78384",
  "dataResponse":{"authorization":"86246B",
    "arpc":"4C4612E7AA0B7999",                  ← MISMO arpc fake
    "binInformation":{"bin":"512912","bank":"GENERAL","brand":"MASTERCARD"},
    "simulation":true}}
PHASE 5 CompleteEmvTrans → ✅ SUCCESS (Mastercard chip OK)
```

### Test 4: SANDBOX — AMEX contactless → SUCCESS (op 78392)

```
[CONTACTLESS PHASE 1] StartCtlssTransUseCase
[CONTACTLESS PHASE 2] RESULT_REQ_ONLINE
[CONTACTLESS ONLINE PHASE 2] Calling SaleCtls...
✅ [CONTACTLESS ONLINE PHASE 2] Online authorization SUCCESS!
ℹ️  [CONTACTLESS ONLINE] Skipping CompleteEmvTrans (not required for contactless)
🎉 PAYMENT APPROVED
```

### Test 5: SANDBOX con MSI deshabilitado en nuestra UI — AMEX chip → FAIL IGUAL

Cambio aplicado: `enableMsiPromotions = true → false` en `PaymentScreen.kt:512`.
Resultado: mismo `FailureSecondGenerate` (op 78396 después del cambio).
**Conclusión parcial**: el toggle UI de MSI no afecta. Pero MSI configurado en Blumon backend del merchant **sí podría afectar** (no podemos cambiarlo desde nuestra app).

---

## 3. Variables que difieren entre sandbox (FAIL) y producción (OK)

| Variable | Sandbox AMEX chip | Producción AMEX chip |
|---|---|---|
| Endpoint Blumon | `sandbox-core.blumonpay.net` | `core.blumonpay.net` |
| `simulation` flag en response | `true` | `false` |
| `arpc` value | constante `4C4612E7AA0B7999` | dinámico real |
| `emvResponseCode` value | `null` | `"3030"` |
| `membership` | `9109196` | `8481547982` |
| `posId` | `388` | `5685` |
| **MSI plans configurados en merchant Blumon** | **Activos (3/6/9/12 meses)** | **Desactivados** |
| Resultado | ❌ FailureSecondGenerate | ✅ SUCCESS |

Y entre sandbox AMEX (FAIL) y sandbox MC (OK), todo es igual EXCEPTO la marca y el BIN. Eso aísla el factor distintivo a algo que SOLO afecta al kernel AMEX.

---

## 4. Estructura del código relevante

`app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt`

### PASO 4.5: AIP Checking (líneas 2784-2806)

```kotlin
// Extract AIP (Application Interchange Profile) tag 0x82
val aipParams = GetTagValueParams(tag = 0x82, cardTech = CardTech.CHIP)
val aipResult = getTagValueUseCase.run(aipParams)
val aipHex = if (aipResult.isRight) aipResult.rightValue().tagValue ?: "" else ""

val arpcRequired = if (aipHex.length >= 2) {
    val firstByte = aipHex.substring(0, 2).toInt(16)
    (firstByte and 0x04) != 0  // Bit 3 (0x04) indicates ARPC support
} else false

Timber.i("[PHASE 4.5] AIP Checking - ARPC required: $arpcRequired (AIP: $aipHex)")
```

Para AMEX, `aipHex = "3C00"` (logs confirman). Bit 3 está set → `arpcRequired = true`.

### PASO 5: CompleteEmvTrans (líneas 2808-2832)

```kotlin
if (arpcRequired) {
    Timber.i("[PHASE 5] CompleteEmvTrans - Card requires ARPC, updating chip...")
    val completeParams = CompleteEmvTransParams(
        emvResponseCode = saleData.emvResponseCode ?: "00",  // En sandbox = "00" (default por null)
        authorization = saleData.authorization ?: "",        // "D4626D"
        arpc = saleData.arpc ?: "",                          // "4C4612E7AA0B7999" (fake constante)
        script7172 = saleData.script ?: "",                  // "" (vacío)
        arpcResponseCode = "00"                              // Hardcoded comment: "Sandbox requires this parameter"
    )
    val completeResult = completeEmvTransUseCase.run(completeParams)

    if (completeResult.isLeft) {
        val error = completeResult.leftValue()
        Timber.e("❌ [PHASE 5] Complete failed: $error")  // ← Acá llega
        ...
    }
}
```

### Comentario crítico preexistente (línea 2186)

```kotlin
* ⚠️ CRITICAL: CompleteEmvTrans is ONLY called if the card requires ARPC (AIP bit 3 = 1)
* This prevents error -11 (FailureSecondGenerate) for cards that don't need ARPC.
```

Sugiere que `-11 / FailureSecondGenerate` es un error conocido del kernel cuando se llama `CompleteEmvTrans` a una tarjeta que NO lo necesita. PERO en este caso AMEX SÍ lo necesita (AIP bit 3 = 1), así que la condición está bien y aún así falla.

### Inspección de bytecode del SDK (kernel call)

El log nativo del SDK muestra:
```
EMV_completeEmvTrans:
  emvResponseCode: 3030 (en prod)  /  00 (en sandbox por null default)
  authorization: ...
  arpc: <16 hex chars>
  script7172: (empty)
EmvProcess: validateIsArpcSupportedByAIP: AMEX
EmvProcess: arpc:<arpc>3030  ← concatena arpc + emvResponseCode
EmvProcess: completeTransProcess,acType:1  ← solo en éxito
```

`acType:1` significa TC (transaction certificate) — chip aceptó la transacción.
En sandbox se queda antes de eso → chip rechazó verificación.

---

## 5. Hipótesis con probabilidad estimada

| # | Hipótesis | Probabilidad | Evidencia a favor | Evidencia en contra |
|---|---|---|---|---|
| H1 | Kernel AMEX K4 (ExpressPay) verifica criptográficamente el ARPC contra issuer key real, rechaza el `4C4612E7AA0B7999` fake | **Alta** | (a) Sandbox y prod difieren solo en arpc/emvResponseCode/simulation, (b) MC kernel K2 acepta mismo arpc fake (más permisivo), (c) AMEX kernel históricamente más estricto en EMV verification | Sin docs internos del kernel AMEX no podemos confirmar al 100% el algoritmo de verificación |
| H2 | MSI plans configurados en merchant Blumon backend afectan AMEX EMV processing | **Media** | (a) Variable confirmadamente distinta entre sandbox y prod (sandbox tiene MSI activo, prod no), (b) AMEX en MX usa PPI no MSI clásico — config de MSI podría meter flags incompatibles | (a) MC chip pasa con MSI activo en mismo merchant — si MSI rompiera todo chip, también afectaría MC, (b) El POST body NO lleva campo `msi` cuando user pica DIRECT |
| H3 | El `emvResponseCode: null` que devuelve sandbox (vs `"3030"` en prod) genera un default `"00"` que el kernel interpreta distinto | **Media-baja** | Difference confirmada en data | Ambos eventualmente serializan a "00"/"3030" string. Default `?: "00"` vs explícito `"3030"` no debería ser equivalente al kernel |
| H4 | El campo `script7172` siempre vacío en sandbox vs prod | **Baja** | No tenemos evidencia de prod con `script` no-vacío para AMEX | Si fuera el bug, MC también fallaría (mismo script vacío) |
| H5 | `arpcResponseCode = "00"` hardcodeado es incorrecto para AMEX | **Baja** | Comentario en código dice "Sandbox requires this parameter" pero no documenta valor correcto | Producción usa el mismo `"00"` y funciona. No es la diferencia |

---

## 6. Lo que NO sabemos

1. **El algoritmo exacto de verificación ARPC del kernel AMEX K4 dentro del SDK Blumon** — closed-source AAR.
2. **Si Blumon sandbox firma el arpc con alguna clave testing** o si es un valor literalmente hardcoded sin firma.
3. **Si el merchant config de MSI en Blumon backend** afecta los EMV tags que se procesan o el algoritmo de ARPC generation. Edgardo podría verificar.
4. **Por qué el `emvResponseCode` viene `null` en sandbox** vs `"3030"` en prod. ¿Es feature, bug, o irrelevante?
5. **Si históricamente este flow funcionó** en sandbox (antes de algún cambio en Blumon backend reciente). El usuario recuerda que sí, pero no tenemos logs históricos para confirmar.

---

## 7. Lo que SÍ sabemos con certeza

1. **Producción funciona** end-to-end para AMEX chip — validado en vivo el 2026-05-06 op 18086560 con $1 real.
2. **Mastercard chip funciona** en mismo merchant sandbox simulation. Solo falla cancel (causa distinta: BIN GENERAL no mapeado a banco real, ver `AUDIT_CHIP_HANG_DONA_SIMONA.md`).
3. **AMEX contactless funciona** en sandbox porque skip `CompleteEmvTrans` por contrato Blumon SDK (línea 4197).
4. **MSI desde nuestra app NO está en el wire** — `msi=null` cuando user pica DIRECT, el campo se omite del JSON.
5. **El AIP es `3C00`** para AMEX → bit 3 set → ARPC required → CompleteEmvTrans se llama (correctamente per design).

---

## 8. Acción operativa pendiente

Pedir a Edgardo de Blumon:

1. **Desactivar temporalmente los MSI plans del merchant sandbox `2841548417` / posId `388`**. Re-probar AMEX chip. Si pasa → confirmar H2. Si falla → confirmar H1.
2. **Sacar el merchant del modo simulation** (si es posible) para que devuelva `arpc` real verificable. Re-probar AMEX chip. Si pasa → confirmar H1.
3. Si ninguna de las anteriores: pedir explicación oficial del algoritmo de verificación ARPC del kernel AMEX en sandbox y por qué difiere de MC.

---

## 9. Pregunta para el LLM auditor

> Dada esta evidencia, ¿cuál es la causa raíz más probable del `FailureSecondGenerate` (-11) en AMEX chip sandbox cuando el mismo flujo funciona perfecto en producción y MC sandbox? ¿Es la diferencia del `arpc` (constante fake en sandbox vs dinámico real en prod), la configuración de MSI en el merchant backend, o algo más sutil? Si es el arpc fake, ¿por qué el kernel MC K2 acepta el mismo arpc fake mientras el kernel AMEX K4 lo rechaza? ¿Hay forma de validar AMEX chip en sandbox sin pedir cambios a Blumon, o es bloqueante hasta que ellos modifiquen merchant config?

---

## 10. Anexos para investigación

### Repos relacionados
- TPV: `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv` (rama `main`, commit `7a2d325`)
- SDK Blumon producción: `app/libs/lib_services-1.2.0.0-PROD.aar`
- SDK Blumon sandbox: `app/libs/lib-services-BP-SAND_1601.aar`

### Archivos clave de código
- `app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt`
  - Líneas 2752-2832: `continuePaymentFlow` chip flow
  - Líneas 2784-2806: AIP check (PASO 4.5)
  - Líneas 2808-2832: CompleteEmvTrans (PASO 5)
- `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt:512`
  - `enableMsiPromotions = true` (toggle UI MSI)

### Comandos para reproducir
```bash
# Compilar + instalar sandbox debug en device PAX A910S
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew installSandboxDebug

# Capturar log con TODOS los tags (no solo PaymentViewModel:*)
adb logcat -c
adb logcat -v time | grep -iE "PaymentViewModel|EMV|complete|Failure|charge|arpc"

# Reproducir
# 1. Login con cuenta sandbox
# 2. Cobro Rápido $2 con tarjeta AMEX 376668xxxxxx
# 3. Insertar chip (no contactless)
# 4. Observar PHASE 5 fallar con FailureSecondGenerate
```

### Custom keys de Crashlytics relevantes
- `app_terminal_serial: 2841548417` (testing terminal)
- `payment_emv_stage: START_EMV_TRANS_OK` (vs `*_FAILED` no aplica acá)
- `app_environment: SANDBOX` vs `PROD`

### Datos para Edgardo / soporte Blumon
- Merchant testing sandbox: `serial=2841548417`, `posId=388`, `membership=9109196`
- Tarjeta de pruebas: BIN `376668` AMEX, last4 `7182`
- Op IDs de hoy:
  - Sandbox AMEX chip FAIL: `78391`, `78396`, `78397` (todos FailureSecondGenerate)
  - Sandbox AMEX contactless OK: `78392`
  - Sandbox MC chip OK: `78384`
  - Producción AMEX chip OK: `18086560`
- Fechas: 2026-05-06, ventana 17:00-18:35 CST
