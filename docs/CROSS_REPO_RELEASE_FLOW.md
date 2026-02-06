# Cross-Repo Release Flow

Flujo completo para releases del TPV Android que involucran cambios en múltiples repositorios.

---

## Por qué es crítico

| Repo | Tiempo de deploy |
|------|------------------|
| avoqado-server | Minutos |
| avoqado-web-dashboard | Minutos |
| **avoqado-tpv** | **3-5 días** (requiere firma Blumon/PAX) |

**Implicación:** El backend debe soportar la versión vieja Y nueva del TPV durante ~1 semana.

---

## Flujo de Release

```
┌─────────────────────────────────────────────────────────────┐
│  1. DESARROLLO                                              │
├─────────────────────────────────────────────────────────────┤
│  • Hacer cambios en TPV                                     │
│  • Hacer cambios en Server (si necesario)                   │
│  • Hacer cambios en Dashboard (si necesario)                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  2. COMMIT & PUSH (en orden)                                │
├─────────────────────────────────────────────────────────────┤
│  • Server:    git add . && git commit && git push           │
│  • Dashboard: git add . && git commit && git push           │
│  • TPV:       git add . && git commit && git push           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  3. DEPLOY BACKEND                                          │
├─────────────────────────────────────────────────────────────┤
│  • Push a main en avoqado-server                            │
│  • Verificar que el deploy a producción sea exitoso         │
│  • Probar endpoints críticos en producción                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  4. VERIFICACIÓN PRE-APK                                    │
├─────────────────────────────────────────────────────────────┤
│  Ejecutar:                                                  │
│  $ ./scripts/check-cross-repo.sh                            │
│                                                             │
│  Exit codes:                                                │
│  • 0 = ✅ Todo listo, puede generar APK                     │
│  • 1 = ❌ Errores críticos, NO generar APK                  │
│  • 2 = ⚠️  Advertencias, revisar antes de continuar         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  5. GENERAR APK                                             │
├─────────────────────────────────────────────────────────────┤
│  $ ./gradlew assembleProductionRelease                      │
│                                                             │
│  APK generado en:                                           │
│  app/build/outputs/apk/production/release/                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  6. FIRMAR Y ENVIAR A BLUMON                                │
├─────────────────────────────────────────────────────────────┤
│  • Firmar con apksigner (Signature Scheme v2)               │
│  • Enviar APK a Blumon                                      │
│  • Esperar 3-5 días para firma PAX                          │
│  • Guardar APK en iCloud (ver CLAUDE.md sección 10)         │
└─────────────────────────────────────────────────────────────┘
```

---

## Script de Verificación

### Ubicación
```
avoqado-tpv/scripts/check-cross-repo.sh
```

### Qué verifica automáticamente

| Verificación | Bloquea | Descripción |
|--------------|---------|-------------|
| Repos existen | ✅ Sí | Verifica que avoqado-server y avoqado-web-dashboard estén en ../  |
| TPV sin cambios | ✅ Sí | No debe haber cambios sin commitear en TPV |
| TPV pusheado | ✅ Sí | No debe haber commits locales sin pushear |
| Backend online | ✅ Sí | api.avoqado.io debe responder HTTP 200 |
| Server limpio | ⚠️ No | Advierte si hay cambios sin commitear |
| Dashboard limpio | ⚠️ No | Advierte si hay cambios sin commitear |
| Server pusheado | ⚠️ No | Advierte si hay commits sin pushear |
| Dashboard pusheado | ⚠️ No | Advierte si hay commits sin pushear |

### Qué NO verifica (requiere revisión manual)

- ¿El TPV usa endpoints nuevos del backend?
- ¿El backend ya tiene esos endpoints en producción?
- ¿Los cambios del backend son backwards-compatible?
- ¿Hay features cross-repo que requieran coordinación?

---

## Verificación Manual (Claude debe preguntar)

Cuando el script pasa o tiene solo advertencias, Claude debe confirmar:

```
1. ¿Esta versión del TPV usa algún endpoint NUEVO del backend?
   → Si sí, ¿ya está desplegado en producción?

2. ¿Hay cambios en el backend que REQUIERAN esta versión del TPV?
   → Si sí, el backend debe soportar versión vieja Y nueva

3. ¿Los commits recientes del server/dashboard afectan al TPV?
   → Revisar los últimos commits mostrados por el script
```

---

## Reglas de Backwards Compatibility

### Backend SIEMPRE debe:

```typescript
// ✅ CORRECTO - Agregar campo nuevo como opcional
interface OrderResponse {
  id: string
  total: number
  newField?: string  // Opcional, TPV viejo lo ignora
}

// ❌ INCORRECTO - Quitar campo existente
interface OrderResponse {
  id: string
  // total: number  ← TPV viejo crashea
}

// ❌ INCORRECTO - Hacer campo requerido
interface CreateOrderRequest {
  items: Item[]
  newRequiredField: string  // TPV viejo no lo envía
}
```

### Usar header de versión para comportamiento condicional

```typescript
// En el backend
const appVersion = req.headers['x-app-version-code']

if (appVersion && parseInt(appVersion) >= 18) {
  // Comportamiento nuevo para TPV 1.4.5+
} else {
  // Comportamiento legacy para TPV < 1.4.5
}
```

---

## Timeline Típico

| Día | Acción |
|-----|--------|
| 1 | Commit cambios en los 3 repos |
| 1 | Push y deploy backend a producción |
| 1 | Push y deploy dashboard a producción |
| 1 | Verificar backend estable |
| 1 | Generar APK y enviar a Blumon |
| 2-4 | Blumon procesa y firma APK |
| 5 | APK disponible en terminales |
| 5-12 | Backend soporta versión vieja Y nueva |

---

## Troubleshooting

### El script bloquea por "cambios sin commitear en TPV"

```bash
git status                    # Ver qué cambios hay
git add -A && git commit -m "mensaje"
git push origin main
./scripts/check-cross-repo.sh # Reintentar
```

### El script bloquea por "backend no responde"

1. Verificar https://api.avoqado.io/health en el navegador
2. Si está caído, esperar o investigar el deploy
3. Si responde pero el script falla, puede ser timeout de red

### Advertencia de "commits sin pushear en server"

Preguntar: ¿El TPV necesita esos commits?
- **Sí** → Pushear server primero, esperar deploy, luego generar APK
- **No** → Ignorar advertencia y continuar

---

## Referencias

- `CLAUDE.md` sección 10 - Release Build Checklist
- `CLAUDE.md` sección 12 - Cross-Repo Consistency
- `docs/PRODUCTION_DEPLOYMENT.md` - Deployment completo con Blumon
