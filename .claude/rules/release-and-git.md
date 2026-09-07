# Release Build & Git Policy

## Release Build Checklist

When user asks to build a production APK:

### 1. Version Bump

Ask about version bump. Location: `app/build.gradle.kts` -> `android.defaultConfig`

- `versionCode` += 1 (ALWAYS increment for release)
- `versionName` = semver (MAJOR.MINOR.PATCH)
- Dev/testing: don't change version. Production release: ALWAYS bump.

### 2. Build APK

```bash
./gradlew assembleProductionRelease
```

### 3. Sign with apksigner (v2 scheme REQUIRED)

targetSdk 34+ requires APK Signature Scheme v2. Use `apksigner`, NOT `jarsigner`.

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out ~/Desktop/avoqado-tpv-VERSION-production-signed.apk \
  app/build/outputs/apk/production/release/app-production-release-unsigned.apk
```

### 4. Verify Signature

```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --verbose APK_FILE.apk
# Must show: "Verified using v2 scheme: true"
```

### 5. Save to iCloud (MANDATORY)

**El APK se archiva bajo la carpeta del PROCESADOR de esa variante, no en una sola.**

PAX / Blumon (variantes `sandbox`, `production`):

```
/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Blumon/APK/
  <version>/sandbox/avoqado-tpv-<version>-sandbox.apk
  <version>/production/avoqado-tpv-<version>-production.apk
  <version>/PAXFIRMADO/  (manually added after PAX signs)
```

Nexgo / AngelPay (variantes `nexgo`, `nexgoProd`) — **NUNCA bajo `Blumon/`**:

```
/Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/APK/
  <version>/nexgoProd/avoqado-tpv-<version>-nexgoProd.apk
```

Nexgo no pasa por el firmado de PAX (no hay `PAXFIRMADO/`): se firma con apksigner
y se entrega al equipo de AngelPay.

### 🔴 Dos canales de distribución distintos — no los cruces

| Flota | Cómo llega el APK a la terminal |
|---|---|
| **PAX / Blumon** | Sistema propio de Avoqado: se sube el APK → fila `AppUpdate` (Firebase Storage) → `check-update` / comando `INSTALL_VERSION` desde el dashboard |
| **Nexgo / AngelPay** | **Se le ENTREGA el APK firmado al equipo de AngelPay y ELLOS lo despliegan por su TMS.** El founder NO sube builds de Nexgo al sistema de Avoqado |

Por eso la tabla `AppUpdate` no tiene ninguna versión `-nexgo-prod` (2.6.3, 2.6.5, 2.7.0…):
esas nunca pasaron por ahí. Las terminales Nexgo reportan su versión por heartbeat
igual que las PAX, pero se actualizan por el TMS de AngelPay (se ve en logcat:
`tmsVersion`, `otaVersion=v1.4.x_Angelpay…`).

**Qué SÍ protege hoy a las Nexgo del sistema de updates de PAX (verificado 2026-07-29):**
`UpdateCheckManager.getEnvironment()` decide por el **package**, no por `BLUMON_ENV`:
`APPLICATION_ID.contains("sandbox") → "SANDBOX"`, si no `"PRODUCTION"`. Como
`nexgoProd` corre bajo `com.jaac.avoqado_tpv.sandbox`, **las Nexgo consultan como
SANDBOX** y la tabla `AppUpdate` solo tiene filas `PRODUCTION` → nunca les
coincide un APK de PAX. El aislamiento es por package, no por suerte.

**⚠️ La mina si algún día se sube un APK Nexgo al sistema de Avoqado:** `AppUpdate`
NO tiene campo de procesador/ABI y `check-update` solo filtra por `environment` +
`platform`. Si se subiera un APK Nexgo como **`PRODUCTION`** con `targetType=ALL`
(el default y lo que usan TODOS los releases PAX), **cada PAX lo vería como
actualización** → `ENABLE_PAX_SDK=false` + SDK AngelPay = PAX sin poder cobrar.
Targetear por venue tampoco basta: hay venues con AMBAS marcas (Amaena, Testarudo
Café). El único targeting seguro sería `targetType=TERMINALS`, hoy inerte en
`check-update` (ningún APK manda `X-Terminal-Serial`) pero funcional vía
`INSTALL_VERSION` (`getSpecificVersion` no filtra por audiencia). En la práctica
esto no debería ocurrir: las Nexgo se entregan a AngelPay, no se suben aquí.

Never save APKs to Desktop.

### 6. Send to Blumon -> PAX re-signs -> final APK for terminals

## 🔴 La llave de firma y el CI

**Android identifica una app por el CERTIFICADO con el que está firmada, no por el nombre del
keystore.** Todo lo que se ha entregado —Nexgo a AngelPay, PAX a Blumon, el `release-manifest`—
sale de `~/.android/debug.keystore` de la Mac del founder, cuyo certificado es:

```
c27a142019340122cfdbb601ee3128f3bfb057ac22ef098a37c4bbdca32e0a11
```

**Un APK con otra huella NO puede actualizar una terminal que ya tenga Avoqado instalado**
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Se instala perfecto en un aparato limpio, así que el
problema no se ve hasta que alguien intenta actualizar una terminal en la calle.

### Lo que pasó el 7-sep-2026 (por eso existe esta sección)

El workflow `.github/workflows/signed-builds.yml` **generaba una keystore nueva** con
`keytool -genkeypair` en cada corrida. Su comentario decía que la debug.keystore es «la misma en
cualquier SDK del mundo»: eso vale para el DN (`CN=Android Debug,O=Android,C=US`) y para la
contraseña (`android`), **no para el par de llaves**, que `keytool` genera al azar cada vez.
Medido: `app-nexgo-eaf0d18-signed.apk` del release `build-eaf0d18` llevaba `8a4a15e0…`.

🔴 **La firma era VÁLIDA y el `apksigner verify` salía en verde** — la comprobación de esquema v2
no dice nada de con QUÉ llave se firmó. Por eso el defecto sobrevivió: nada en la corrida fallaba.

### Cómo comprobar la huella de un APK

```bash
~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs APK | grep 'SHA-256 digest'
# Signer #1 certificate SHA-256 digest: c27a1420…  ← la buena
```

### El secreto `DEBUG_KEYSTORE_BASE64` (paso del founder, una sola vez)

El CI ya no genera la llave: la **reconstruye** desde ese secreto, igual que hace con
`google-services.json`. Sin el secreto, la corrida **falla en el primer minuto** en vez de publicar
un APK inservible. Para subirlo:

```bash
base64 -i ~/.android/debug.keystore | pbcopy   # macOS lo deja en UNA línea (~3,492 caracteres)
```

Y pegarlo en **GitHub → repo `avoqado-tpv` → Settings → Secrets and variables → Actions →
New repository secret**, con el nombre exacto `DEBUG_KEYSTORE_BASE64`.

⚠️ **Esa llave privada es la identidad de la app en cada terminal instalada.** Que la contraseña
sea pública no la vuelve inofensiva: quien la tenga puede firmar un APK que las terminales
aceptarán como actualización legítima de Avoqado. Va como secreto de repositorio (no de entorno ni
de organización), y el repo es privado. **Nunca se imprime, nunca se commitea, y si se rota, la
flota entera deja de poder actualizarse** — habría que reinstalar terminal por terminal.

### El candado que impide que vuelva a pasar

`signed-builds.yml` comprueba la huella **dos veces** contra `HUELLA_ESPERADA`, que va en claro en
el propio workflow (es una llave pública; ponerla en un secreto permitiría cambiarla en silencio
para que cuadrara con la keystore equivocada):

1. **Al reconstruir el keystore**, antes de compilar — un secreto ausente o equivocado se caza en
   segundos, no tras 40 minutos de build.
2. **Sobre cada APK ya firmado** (`apksigner verify --verbose --print-certs`), justo después del
   chequeo de esquema v2.

Si alguna no coincide, la corrida falla y **no se publica ningún release**.

### Qué sirve para qué

| Origen del APK | ¿Actualiza una terminal en la calle? |
|---|---|
| Mac (ceremonia `/avoqado:release-production` o `assembleNexgoProdRelease` + `apksigner`) | **Sí** — es lo que se entrega a AngelPay y a Blumon |
| GitHub Actions **con** `DEBUG_KEYSTORE_BASE64` puesto | **Sí** — misma llave, misma huella |
| GitHub Actions **sin** el secreto | **No corre**: la corrida falla a propósito |
| Cualquier APK con huella ≠ `c27a1420…` | **No** — sólo instala en un aparato limpio |

## Version Bump Recommendations

**THE KEY QUESTION: Can the user do something they COULDN'T before?**

| Answer | Bump | Examples |
|--------|------|---------|
| Yes, new capability | **MINOR** | BLE payments, kiosk mode, new reports |
| No, improvement only | **PATCH** | Bug fix, UX improvement, refactor, performance |
| Breaks compatibility | **MAJOR** | Incompatible DB migration, API redesign |
| Docs/tests only | **No bump** | CLAUDE.md update, test additions |

Common mistake: seeing "lots of new code" and assuming MINOR. Code volume doesn't matter — user IMPACT does.

After each implementation, proactively recommend bump type with justification.

## Permission System for New Features

Every new TPV feature MUST have permissions:

1. **Backend**: Add to `avoqado-server/src/lib/permissions.ts` (`PERMISSION_CATEGORIES` + `DEFAULT_PERMISSIONS`)
2. **Backend**: Use `checkPermission()` middleware on endpoint
3. **TPV**: Validate with `PermissionsRepository.hasPermission()` before showing UI
4. **Dashboard**: Permission appears automatically in RolePermissions.tsx

Naming: `tpv-{resource}:{action}` (e.g., `tpv-payments:refund`, `tpv-shifts:create`)

**Critical**: EXACT same permission name in backend `checkPermission()` AND TPV `hasPermission()`. Name mismatches cause silent failures.

## Cross-Repo Consistency

TPV takes 3-5 days to update (Blumon/PAX signing). Backend/Dashboard deploy in minutes.

**Before generating production APK:**

```bash
./scripts/check-cross-repo.sh  # Exit 0 = ready, 1 = errors, 2 = warnings
```

| Principle | Rule |
|-----------|------|
| Backend supports old versions | Use `X-App-Version-Code` header for conditional behavior |
| Never remove API response fields | Add new fields, don't delete old ones |
| New fields must be optional | Include defaults for backwards compat |
| Deploy order | Backend first -> wait stable -> send APK |

Timeline: Day 1 deploy backend+dashboard, Day 1 send APK to Blumon, Day 3-5 APK on terminals. Backend must support old AND new TPV for ~1 week.

## Git Policy

**Never commit, push, or make git changes without explicit user permission.**

### Commit Format

```
feat(area): description    # New feature
fix(area): description     # Bug fix
release(vX.Y.Z): title     # Release
```

**Do NOT add `Co-Authored-By: Claude`** — commits should look like developer's work.

### Post-Implementation Flow

After completing any task, ask:
- **Commit** — normal commit with descriptive message
- **Release** — bump version + commit + tag + push + build instructions
- **WIP** — leave changes uncommitted

### Release Checklist (when user says "release")

1. Verify no compilation errors
2. Bump `versionCode` (+1) and `versionName` in build.gradle.kts
3. Commit with detailed release message
4. Create annotated tag `vX.Y.Z`
5. Push to origin (main + tags)
6. Give build + signing instructions
