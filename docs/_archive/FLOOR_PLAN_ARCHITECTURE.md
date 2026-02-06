# Floor Plan Architecture - Design Document

> **Status**: Reviewed & Validated ✅
> **Date**: 2025-01-15
> **Pattern**: Toast POS / Square POS Floor Plan

---

## 🎯 Executive Summary

**Decision**: Implement **GLOBAL COORDINATE SYSTEM** with **AREA FILTERS**

- ✅ One canvas per venue (NOT one canvas per area)
- ✅ Coordinates are GLOBAL (0.0 - 1.0 relative to venue)
- ✅ Areas are LOGICAL FILTERS (like tabs in Toast POS)
- ✅ Floor elements (walls, bars) use same global coordinates

---

## 📐 Coordinate System

### Global Coordinates (0.0 - 1.0)

```
Venue Canvas (0.0 - 1.0)
┌─────────────────────────────────────┐  y=0.0
│                                     │
│  Area: Interior                     │
│  ┌────────────────┐                 │  y=0.2
│  │ Table 1 (0.2, 0.3)              │
│  │ Table 2 (0.5, 0.3)              │
│  └────────────────┘                 │  y=0.5
│                                     │
│  ════════ WALL ═════════           │  y=0.5 (divider)
│                                     │
│  Area: Terraza                      │
│  ┌────────────────┐                 │  y=0.6
│  │ Table 3 (0.2, 0.7)              │
│  │ Table 4 (0.5, 0.7)              │
│  └────────────────┘                 │
│                                     │
└─────────────────────────────────────┘  y=1.0
x=0.0                               x=1.0
```

**Key Points:**
- All tables use SAME coordinate space (venue-wide)
- Areas are LABELS, not separate canvases
- A wall at y=0.5 can separate Interior from Terraza
- Tabs FILTER visibility, don't change coordinates

---

## 🗄️ Data Model

### Current Schema (✅ Correct)

```prisma
model Table {
  id      String @id
  venueId String  // ← Belongs to VENUE (global space)
  areaId  String? // ← OPTIONAL: logical grouping

  // GLOBAL coordinates (0-1 relative to venue)
  positionX Float?
  positionY Float?

  shape    TableShape
  rotation Int
  status   TableStatus

  @@unique([venueId, number])  // ✅ Correct
}

model Area {
  id      String @id
  venueId String
  name    String  // "Interior", "Terraza", "Barra"
  tables  Table[] // Mesas in this logical group
}
```

### New Model Needed (FloorElement)

```prisma
model FloorElement {
  id        String @id @default(cuid())
  venueId   String  // ← GLOBAL coordinates
  venue     Venue   @relation(...)

  areaId    String? // ← OPTIONAL: for visual filtering
  area      Area?   @relation(...)

  type      FloorElementType

  // GLOBAL coordinates (same as Table)
  positionX Float
  positionY Float

  // For rectangles (BAR_COUNTER, SERVICE_AREA)
  width     Float?
  height    Float?
  rotation  Int @default(0)

  // For WALL (line)
  endX      Float?
  endY      Float?

  label     String?
  color     String?
}

enum FloorElementType {
  WALL
  BAR_COUNTER
  SERVICE_AREA
  LABEL
  DOOR
}
```

---

## 🎨 UI Architecture

### Screen Structure

```
FloorPlanScreen
├─ TopBar: "Floor Plan" + [Edit Mode] button
├─ TabRow: [All] [Interior] [Terraza] [Bar]  ← Area filters
├─ Canvas (Global 0-1 space)
│  ├─ Zoom/Pan gestures
│  ├─ Render all elements (filtered by selected area)
│  └─ Tap table → Select/Assign
└─ BottomBar (Edit Mode): [+Table] [+Wall] [+Bar] [+Area]
```

### Area Tabs Behavior

**Tab "All"** (default):
- Shows ALL tables and floor elements
- Full venue view

**Tab "Interior"**:
- Filters: `tables.where(t => t.areaId == interiorAreaId)`
- Filters: `floorElements.where(e => e.areaId == interiorAreaId || e.areaId == null)`
- Same canvas, different visibility

**Zoom behavior**:
- Always operates on GLOBAL canvas
- Zoom out → See entire venue
- Zoom in → Focus on specific area

---

## 🎯 Toast POS Pattern Analysis

From user's image:

```
┌─────────────────────────────────────┐
│ ← Table Service        ⚙️ ⊕ 📋 ⋮  │
├─────────────────────────────────────┤
│ [Dining Room] [Patio] [Walk-in]    │  ← Area tabs (filters)
├─────────────────────────────────────┤
│                                     │
│  105  104  103  102  101  100      │  ← All in same space
│                                     │
│  204  203  202  201  200           │
│                                     │
│  303  302           301  300        │
│                                     │
│  ═══════════  ▓▓▓                  │  ← Walls, bar
│  │ 1 │                             │
│  │ 2 │  ████ BAR                   │
│  │ 3 │                             │
│  ═══════════                        │
│                                     │
│  105  104  103  102  101  100      │
│                                     │
│  ○ ○ ○   ○ ○ ○                    │  ← Round tables
│                                     │
│  604  605  604  605  602  601  600 │
│                                     │
└─────────────────────────────────────┘
```

**Key Observations:**
- ✅ One canvas showing ALL areas
- ✅ Tabs filter visibility
- ✅ Walls/bars are global elements
- ✅ Coordinates are continuous (not per-area)

---

## 🔧 Implementation Phases

### Phase 1: Canvas Viewer (3h)

**What:**
- Render global canvas with zoom/pan
- Display tables at positionX/Y
- Color by status: 🟢 Green = Available, 🔴 Red = Occupied, 🟡 Yellow = Reserved
- Area tabs for filtering

**Files:**
- `FloorPlanCanvasScreen.kt`
- `FloorPlanViewModel.kt`
- `TableRepository.getTables()` (already exists)

### Phase 2: Table Editor (2h)

**What:**
- Drag & drop tables on canvas
- Add new table (tap empty space)
- Edit table (tap existing table)
- Delete table

**API:**
- `PUT /tpv/venues/{venueId}/tables/{tableId}/position`
- `POST /tpv/venues/{venueId}/tables`
- `DELETE /tpv/venues/{venueId}/tables/{tableId}`

### Phase 3: Floor Elements Backend (2h)

**What:**
- Add `FloorElement` model to schema
- Migration: `npx prisma migrate dev`
- CRUD endpoints for floor elements

**Endpoints:**
- `GET /tpv/venues/{venueId}/floor-elements`
- `POST /tpv/venues/{venueId}/floor-elements`
- `PUT /tpv/venues/{venueId}/floor-elements/{id}`
- `DELETE /tpv/venues/{venueId}/floor-elements/{id}`

### Phase 4: Floor Elements Editor (3h) ✅ COMPLETE

**What:**
- ✅ Add wall (tap & drag)
- ✅ Add bar counter (tap & place)
- ✅ Add service area (tap & place)
- ✅ Add door (tap & place)
- ⚠️ Add label (tap to place position, text input dialog TODO)
- ✅ Edit element dimensions (draggable inline editor with real-time preview)
- ✅ Move/reposition elements (drag & drop)
- ✅ Delete elements (via dialog)

**UI:**
- ✅ FAB with creation menu (7 element types)
- ✅ Touch gestures for drawing (tap-and-drag for walls, tap-to-place for others)
- ✅ Edit mode toggle (existing)
- ✅ Creation mode overlay with instructions
- ✅ Real-time preview (wall line preview, element resize preview)

---

## ⚠️ Common Pitfalls to Avoid

### ❌ WRONG: Per-Area Coordinates

```kotlin
// ❌ BAD: Each area has own coordinate space
Area("Interior").canvas {
    Table(pos = 0.2, 0.3)  // Relative to Interior canvas
}
Area("Terraza").canvas {
    Table(pos = 0.2, 0.3)  // Same coords, different space
}
```

**Problem:**
- Can't show full venue at once
- Can't have walls crossing areas
- Confusing when moving tables between areas

### ✅ CORRECT: Global Coordinates

```kotlin
// ✅ GOOD: One canvas, area is just a filter
Venue.canvas {
    Table(area = "Interior", pos = 0.2, 0.3)  // y=0.3
    Wall(start = (0.0, 0.5), end = (1.0, 0.5)) // Divider
    Table(area = "Terraza",  pos = 0.2, 0.7)  // y=0.7
}
```

---

## 🎨 Color Scheme

```kotlin
// Table status colors (UNIVERSAL STANDARD)
val AVAILABLE = Color(0xFF4CAF50)  // 🟢 Green
val OCCUPIED  = Color(0xFFF44336)  // 🔴 Red
val RESERVED  = Color(0xFFFFC107)  // 🟡 Yellow
val CLEANING  = Color(0xFF9E9E9E)  // ⚪ Gray

// Floor element colors
val WALL         = Color(0xFF424242)  // Dark gray
val BAR_COUNTER  = Color(0xFF795548)  // Brown (wood)
val SERVICE_AREA = Color(0xFFE0E0E0)  // Light gray
val LABEL        = Color(0xFF9E9E9E)  // Medium gray
```

---

## 📝 Migration Checklist

### Backend (avoqado-server)

- [ ] Add `FloorElement` model to `schema.prisma`
- [ ] Add `floorElements` relation to `Venue` model
- [ ] Add `floorElements` relation to `Area` model
- [ ] Run migration: `npx prisma migrate dev --name add_floor_elements`
- [ ] Create `floor-element.tpv.service.ts`
- [ ] Create `floor-element.tpv.controller.ts`
- [ ] Add routes to `tpv.routes.ts`
- [ ] Seed test data in `seed.ts`

### Android (avoqado-tpv)

- [ ] Create `FloorElement.kt` domain model
- [ ] Create `FloorElementDto.kt` and mappers
- [ ] Create `FloorElementApiService.kt`
- [ ] Create `FloorPlanRepository.kt`
- [ ] Create `FloorPlanViewModel.kt`
- [ ] Create `FloorPlanCanvasScreen.kt`
- [ ] Update `NavRoute.kt` with FloorPlan route
- [ ] Wire up navigation from TableService → FloorPlan
- [ ] Add to OrderingModule.kt for DI

---

## ✅ Implementation Status

**Architecture Status**: ✅ **APPROVED & IMPLEMENTED**

**Backend:**
- ✅ Schema is correctly designed (global coordinates)
- ✅ Area model is correct (logical grouping)
- ✅ Unique constraints are correct
- ✅ FloorElement model implemented
- ✅ CRUD endpoints for floor elements

**Android:**
- ✅ Phase 1: Canvas Viewer - COMPLETE
- ✅ Phase 2: Table Editor - COMPLETE
- ✅ Phase 3: Floor Elements Backend Integration - COMPLETE
- ✅ Phase 4: Floor Elements Editor - COMPLETE
  - ✅ Create: Tap-and-drag walls, tap-to-place bars/services/doors
  - ✅ Edit: Draggable dimension editor with real-time preview
  - ✅ Move: Drag & drop elements
  - ✅ Delete: Via dialog
  - ⚠️ Label text input dialog - TODO
- ⚠️ Zoom/Pan gestures - TODO (mentioned in code comments)

**UI Pattern**: ✅ Matches industry standard (Toast/Square)

---

## 📚 References

- Toast POS Floor Plan (user's reference image)
- Square POS Table Management
- [Jetpack Compose Canvas](https://developer.android.com/jetpack/compose/graphics/draw/overview)
- [Touch Gestures in Compose](https://developer.android.com/jetpack/compose/touch-input/pointer-input)

---

**Last Updated**: 2025-01-15
**Reviewed By**: Architecture Analysis
**Status**: Ready for Implementation ✅
