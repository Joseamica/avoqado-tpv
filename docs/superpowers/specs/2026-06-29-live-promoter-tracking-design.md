# Seguimiento de promotor "cambaceo" — Diseño

**Fecha:** 2026-06-29
**Repos:** avoqado-server (backend) · avoqado-tpv (TPV/PAX) · avoqado-web-dashboard (dashboard)
**Origen:** Asana "ADMIN y Supervisor tengan visibilidad de la geolocalización de un promotor" (PlayTelecom, creado por Isaac Mayoral, task 1216095149541822)

## Problema

Un promotor de PlayTelecom hará venta sin tienda fija, promoviendo en sitios públicos
("cambaceo"). Supervisor (rol MANAGER) y ADMIN/OWNER necesitan **visibilidad de su
geolocalización durante el día**, no solo dónde fichó entrada/salida.

Hoy solo existe `PROMOTERS_AUDIT` (white-label), que muestra la ubicación del
**clock-in / clock-out** (snapshots de asistencia con foto+GPS) en `PromoterLocationModal`.
No existe seguimiento periódico durante la jornada.

## Decisiones tomadas (cerradas)

| Tema | Decisión | Fuente |
|------|----------|--------|
| Alcance | **Opción C**: pin en vivo (posición actual) **+** recorrido del día (ruta) | Isaac |
| Frecuencia | **Cada hora**, solo ventana **11:00–18:00** hora del venue | Isaac |
| Hardware | Terminal `2840744194` tiene **SIM Telcel** → resuelve ubicación sin WiFi | Isaac |
| Gating | Dentro del **white-label existente** (`PROMOTERS_AUDIT` + `requireWhiteLabel`), sin tier nuevo | Founder |
| Tiempo real | **No** se usa Socket.IO — cadencia horaria = *near-live* por REST + polling | Diseño |

### Restricción técnica conocida
La captura de ubicación solo resuelve bien con **Telcel** cuando no hay WiFi
(Altán-sin-WiFi devuelve 404 en `/tpv/geolocation/cell-towers`). Por eso el terminal de
cambaceo debe ser Telcel. Ver `memory/geolocation-altan-cell-only-fails.md`.

## No-objetivos (YAGNI)

- No tracking en tiempo real / streaming continuo (cada-segundos).
- No tracking fuera de 11:00–18:00.
- No mapa embebido en el dashboard en v1 (se usan links a Google Maps; embebido = v2).
- No cola offline persistente en TPV v1 (basta reintento de WorkManager; Room queue = mejora).

## Gating

- Reusa `verifyAccess({ featureCode: 'PROMOTERS_AUDIT', requireWhiteLabel: true })`.
- Roles con visibilidad: **MANAGER, ADMIN, OWNER**.
- Flag por venue en `TpvSettings` para prender la **captura periódica** (distinto de la
  auditoría de asistencia, que ya existe):
  - `trackPromoterLocation: boolean` (default `false`)
  - `trackWindowStart: "11:00"`, `trackWindowEnd: "18:00"`
  - `trackIntervalMin: 60`
  (configurables; defaults arriba)

## Arquitectura

### 1. Captura — avoqado-tpv

Nuevo `PromoterLocationWorker` (WorkManager `PeriodicWorkRequest`). En cada corrida:

1. ¿`trackPromoterLocation` activo en el venue? si no → `Result.success()` (no-op).
2. ¿Hora **del venue** dentro de `[trackWindowStart, trackWindowEnd)`?
   Usa `VenueTimeZone.get(secureStorage)` — **nunca** `ZoneId.systemDefault()` (regla #18).
   Si no → no-op.
3. ¿Sesión de promotor activa (`authRepository.getAuthContext()`)? si no → no-op.
4. `LocationService.getCurrentLocation()` (reúsa el path celda+WiFi existente).
5. Coordenadas → `POST /tpv/promoter-location`. `null` → se omite (no bloquea), breadcrumb
   a Crashlytics.

- **Agendado:** al iniciar sesión de promotor en venue con tracking activo; cancelado en logout.
- **Cadencia:** WorkManager mínimo del sistema = 15 min; corremos a `trackIntervalMin` (60)
  y auto-gateamos por ventana. Sin loops tight.
- **Offline v1:** reintento/backoff de WorkManager. Cola en Room = mejora futura.
- **Timezone:** invalidar cache (`VenueTimeZone.invalidateCache()`) en cambio de venue, como ya se hace.

### 2. Backend — avoqado-server

**Modelo Prisma `PromoterLocationPing`:**
```
id          String   @id @default(cuid())
venueId     String
staffId     String
latitude    Decimal  @db.Decimal(10, 8)
longitude   Decimal  @db.Decimal(11, 8)
accuracy    Float?
capturedAt  DateTime
source      PromoterLocationSource @default(PERIODIC)  // PERIODIC | CLOCK_IN | CLOCK_OUT
createdAt   DateTime @default(now())

@@index([venueId, staffId, capturedAt])
@@index([capturedAt])  // para retención
```
FKs a `Venue` y `Staff`. Migración Prisma estándar.

**Ingesta:** `POST /tpv/geolocation/promoter-ping` (bajo el namespace `geolocation`
existente), `authenticateTokenMiddleware`. Valida feature activa + payload; escribe 1 ping.
Dedupe opcional por `(staffId, hora de capturedAt)`.

**Lectura (dashboard):** extender el endpoint de PROMOTERS_AUDIT
(`GET /dashboard/venues/:venueId/promoters/:promoterId`, o sub-ruta `/track?date=YYYY-MM-DD`)
para devolver:
- `track.points[]`: pings del día ordenados por `capturedAt` (la ruta).
- `track.latest`: el último ping (posición actual) + su hora.
Mismo `verifyAccess` white-label.

**Retención:** job/cron opcional que purga pings > 90 días.

**MCP:** agregar/extender un tool en `avoqado-server/scripts/mcp/` para exponer la
ubicación/ruta del promotor (regla de mantener el MCP en sync).

### 3. Dashboard — avoqado-web-dashboard

Extender `pages/playtelecom/PromotersAudit/` (`PromotersAuditPage` + `PromoterLocationModal`):
- **Posición actual:** marcador + "última actualización HH:mm".
- **Ruta del día:** los ~8 puntos en orden.
  - **v1:** botón "Ver Ruta" → URL de Google Maps con waypoints (reúsa el patrón actual de
    links externos, **0 dependencias nuevas**) + lista de puntos con su hora.
  - **v2 (opcional):** mapa embebido (requeriría una lib de mapas — decisión posterior).
- **Refresco:** refetch al abrir + polling suave (cada pocos min). Sin socket.
- **Estado vacío:** "Sin ubicaciones registradas hoy".

## Flujo de datos

```
TPV PromoterLocationWorker (cada hora, 11-18 venue tz, promotor activo)
  └─ LocationService.getCurrentLocation()  (celda Telcel + WiFi)
       └─ POST /tpv/.../promoter-ping  ──►  PromoterLocationPing (Postgres)
                                                  ▲
Dashboard PromotersAudit  ──GET .../promoters/:id/track──┘
  └─ pinta latest (pin) + points[] (ruta) ; polling cada pocos min
```

## Manejo de errores

| Caso | Comportamiento |
|------|----------------|
| `getCurrentLocation()` → null (sin resolver) | Se omite el ping; breadcrumb Crashlytics; no bloquea |
| POST falla (red) | Reintento/backoff WorkManager |
| Fuera de ventana / feature off / sin sesión | Worker no-op (`Result.success`) |
| Sin pings en el día | Dashboard muestra estado vacío |
| Payload inválido / feature inactiva | Backend 4xx |

## Privacidad

Tracking acotado a 11:00–18:00 (jornada laboral) y solo a promotores en venues con la
feature activa. Se documenta como seguimiento laboral; conviene aviso/consentimiento al
promotor. No se rastrea fuera de la ventana ni en venues sin la feature.

## Testing

- **TPV:** unit test de la decisión del worker — ventana/timezone, feature flag, sesión
  activa, manejo de `null` (mock `LocationService` + reloj inyectable). Sin `SystemClock`.
- **Backend:** tests de ingesta (escribe ping), lectura (ruta + latest), y gating white-label.
- **Manual:** PAX A910S físico con SIM Telcel — captura real en ventana, verificación en dashboard.

## Fases de entrega

- **Fase 0 — Ops (inmediata, destraba a Isaac):** crear venue virtual "Cambaceo" (modelo
  "Cubre Descanso") + `VenuePaymentConfig` (merchant compartido PlayTelecom
  `cmlah9251000ik628rhkkwhp0`) + reasignar a **ISELA CHÁVEZ** (cuidando custodia de SIMs en
  `PROMOTER_HELD` para no dejarlas huérfanas) + migrar terminal `2840744194`.
  *Script de producción aparte — requiere OK explícito. No es parte del código de la feature.*
- **Fase 1 — Backend + dashboard:** modelo + endpoints + ruta visible. Deploy en minutos.
- **Fase 2 — TPV worker:** captura automática. +3–5 días por firma Blumon/PAX.

## Riesgos

- Timezone del venue en el worker (no usar device tz).
- Custodia de SIMs de ISELA al cambiarla de tienda.
- Backend debe soportar TPVs viejas: el endpoint de ingesta es nuevo y opcional; nada se
  rompe si una TPV vieja no lo llama. No se elimina ningún campo de respuesta existente.

## Items abiertos (con recomendación)

1. **Mapa en dashboard:** v1 links a Google Maps + lista *(recomendado)* vs mapa embebido.
2. **Offline TPV:** v1 reintento WorkManager *(recomendado)* vs cola en Room.
