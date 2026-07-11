# Serialized Inventory & SIM Custody (the PlayTelecom-driven features)

PlayTelecom (Bait SIM sales via promoters inside Walmart/Bodega Aurrerá Express stores —
full business context: `avoqado-server/.claude/rules/playtelecom-vertical.md`) is what drove
these two TPV features. Both are **generic, server-config-driven features** — there is no
hardcoded PlayTelecom (or any other client) branching in this repo's Kotlin code, and there
must never be. Mirror the same rule that already applies backend-side
(`avoqado-server/.claude/rules/critical-warnings.md` — "Industry Config: Never Hardcode
Client Names"): any PT-specific label ("SIM", "ICCID") or behavior comes from the server's
`Module`/`VenueModule` config, not a client/venue-slug check here.

## The two features

- **`features/serialized_sale/`** (`SerializedSaleScreen.kt`, `SerializedSaleViewModel.kt`) —
  quick-sell + batch-register flow for serialized goods at point of sale.
- **`features/sim_custody/`** (`MisSimsScreen.kt`, `MisSimsViewModel.kt`, `MySimsModels.kt`,
  `SimCustodyRepository`) — the promoter's "Mis SIMs" screen: pending-accept/reject queue and
  custody state for items assigned to them.

Both are gated by the server's `SERIALIZED_INVENTORY` module (see this repo's
`docs/MODULES_SYSTEM.md` for the generic client-side module mechanism, and
`avoqado-server/docs/features/SERIALIZED_INVENTORY.md` for the full backend model). Labels
shown on screen come from the module's config (`defaultConfig`/`presets`), never a hardcoded
string — this is what lets the same screens read "SIM"/"ICCID" for PlayTelecom and something
else entirely for a future non-telecom tenant on the same feature.

## Money-safety gotcha when moving a PT terminal between stores

Directly relevant to PlayTelecom ops (e.g. re-parenting a "Cubre Descanso" relief promoter's
terminal to the real store they covered): re-parenting a terminal WITHOUT a factory reset
leaves the device charging cards through the **old** venue's Blumon merchant while the server
books the sale under the **new** venue — a silent split-brain money-misrouting bug, invisible
in dev when both venues share a merchant, very real in prod when they don't.

- Merchant credentials (`MerchantAccount` serial+posId, `TerminalConfig.serialNumber`) load
  **in-memory only**, on app startup/activation/fallback-recovery
  (`TerminalConfigRepositoryImpl.fetchConfig`). The ~30s heartbeat does NOT re-fetch or
  overwrite merchants.
- Only `FACTORY_RESET` (clearAll + restart → refetches config for the device serial → picks
  up the new venue's merchants) reliably re-points a terminal. `REMOTE_ACTIVATE` rewrites
  `venueId` but does NOT reload merchants nor restart — the worst path, no visual cue.
  `UPDATE_MERCHANT`/`UPDATE_CONFIG` commands are stubs (TODO, do nothing).
- The dashboard/superadmin "migrate terminal" wizard already forces re-parent + factory
  reset + proof-of-wipe in the right order — prefer it over any bare terminal-edit / move
  path for any PlayTelecom terminal reassignment.
