<!-- TODO.md -->

# Feathered Friend — TODO

A living backlog of planned work for the mod.  
(Items may shift as systems evolve.)

---

## Major

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

### Raven eyes & home station
A more fantasy version of security camera's.
- Add a `home base` where your Raven will stay (if you choose to)
- Allow for players to use their Raven to fly and look around their base (through the Raven's view)

---

## Minor

### Sound FX / Ambient Behavior
- Add more frequent and varied raven events:
    - More caws
    - Additional situational sound cues
    - Misc. small audio feedback improvements