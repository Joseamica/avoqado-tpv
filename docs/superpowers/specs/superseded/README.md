# Superseded specs

Specs aquí ya no son la fuente de verdad. Mantenidos para rastro de decisiones.

## Por qué

Los specs v2 y v3 (`2026-05-13-cobro-clase-reserva-pending-design-v[2|3].md`)
diseñaron una arquitectura compleja para que el TPV creara `Reservation` al
cobrar, con SlotHold, capacity checks con `FOR UPDATE`, idempotency middleware,
refund cascade vía PaymentAllocation, etc.

Re-análisis del ecosistema completo (10 repos, no 3) reveló que esa
arquitectura **ya estaba construida en otra dirección**:

- `avoqado-server/src/services/reservation/createOrderFromReservation.ts` — convierte
  `Reservation → Order` idempotente, SERIALIZABLE TX, ya desplegado (`Order.reservationId` FK
  en producción).
- `avoqado-server/src/services/dashboard/classSession.dashboard.service.ts#addAttendee` —
  crea Reservation con `productId`, `FOR UPDATE`, `SUM(partySize)`, ya implementado.
- `avoqado-android/.../reservations/` — wizard, walk-in flow, calendar día/semana,
  ClassSession scaffolding (list/create/update/AddAttendee), drag-to-reschedule.
- `avoqado-tpv` no necesita saber de reservations — recibe la Order ya armada.

El gap real era mucho menor: un solo flow integrado (Android sheet) que
inscribe + check-in + crea Order en un paso. Eso vive en el spec activo:
`2026-05-16-walkin-class-checkin-immediate-design.md`.

## Cómo leer estos archivos

- **v1** (en el directorio padre, no aquí): exploración inicial. Útil como
  registro de cómo se descubrió que el TPV ya mostraba clases sin cambios.
- **v2**: rewrite con 16 correcciones del audit Codex. Sigue diseñando en
  el repo equivocado.
- **v3**: rewrite con 9 hallazgos adicionales de Codex. Mismo problema base.

Las correcciones técnicas en v2/v3 (FOR UPDATE pattern, SUM(partySize),
venueId everywhere, etc.) ya están aplicadas en el código real del server.
No re-implementar.
