# Changelog Policy (PRIORITY #1)

**Every code modification MUST be documented in `CHANGELOG.md` before committing.**

This is the highest-priority rule. No exceptions.

## Format

Entries go under `## [Unreleased]` in the appropriate section:

```markdown
## [Unreleased]

### **Added**
- **Feature name**: Brief description of what was added

### **Changed**
- **Area**: What changed and why

### **Fixed**
- **Bug name**: What was broken and how it was fixed
```

**Categories:**
- **Added** — New features, new screens, new components, new endpoints
- **Changed** — Modifications to existing behavior, UI tweaks, refactors, performance improvements
- **Fixed** — Bug fixes, crash fixes, visual glitches

**Entry format:** `- **Bold label**: Concise description` — One line per change. Group related sub-changes with indented bullets if needed.

## When to Write

- After completing any implementation (feature, fix, refactor, UI change)
- Before asking the user if they want to commit
- Even for "small" changes — if it modifies behavior or appearance, log it

## File Rotation

Active changelog: `CHANGELOG.md` (always write here)

When `CHANGELOG.md` exceeds ~50,000 characters (~50KB):
1. Move all entries **below** the current `## [Unreleased]` section to a new archive file
2. Archive naming: `CHANGELOG-archive-N.md` where N increments (1, 2, 3...)
3. **Highest N = most recent archive** (e.g., `CHANGELOG-archive-3.md` is newer than `CHANGELOG-archive-1.md`)
4. Add a note at the top of `CHANGELOG.md`: `> Older entries archived in CHANGELOG-archive-N.md`
5. Keep `## [Unreleased]` and the most recent released version in `CHANGELOG.md`

To check size: `wc -c CHANGELOG.md` — if > 50000, rotate.

## On Release

When a version is released:
1. Rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`
2. Add a fresh empty `## [Unreleased]` section above it
