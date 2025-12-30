<!-- TODO.md -->

# Feathered Friend — TODO

A living backlog of planned work for the mod.  
(Items may shift as systems evolve.)

---

## Major

### Raven Gear: Dodge % Scaling
- Lower the **base/default dodge chance** (example target: ~50%).
- Introduce **gear-based dodge bonuses** that raise dodge chance by tier:
    - Iron / Gold / Diamond / Netherite (etc.)
    - Example progression targets:
        - 50% → 60% → 70% (final numbers TBD)
- Ensure upgrades are:
    - Clearly communicated in-game (tooltip/UI)
    - Balanced (no permanent near-invulnerability)
    - Compatible with existing combat / AI behavior

---

### Raven Status GUI — “Badge”
Small, movable horizontal HUD bar with animated textures indicating raven state.  
**Do not show** if the player has **no bound/tamed raven**.

**States / events to represent (visual + optional icons/badges):**
- Idle / Available
- In queue (recipient player not online)
- In delivery (inFlight)
- Arrived at recipient
- Successful delivery
    - Intended recipient accepted
    - Or another player accepted (RMB)
- Timed out (failed delivery)
- Got hit by player / mob / entity
- Died
- Detected hostile mob or another player (within configurable radius)
- Catch-all issue / needs attention ⚠

**Notes**
- Prefer: one primary state + optional small badges (to avoid combinatorial texture explosion)
- Add hover tooltip or tiny subtext only if it stays unobtrusive

---

### Raven Status GUI — Text Log
A lightweight, in-game status/event log using the **same event stream** as the badge HUD.
- Append new entries as events occur
- Keep it readable (timestamps optional)
- Optional filters (errors-only / delivery-only / combat-only)

---

## Minor

### Sound FX / Ambient Behavior
- Add more frequent and varied raven events:
    - More caws
    - Additional situational sound cues
    - Misc. small audio feedback improvements

---
