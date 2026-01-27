# Changelog

All notable changes to this project will be documented in this file.

---
## [1.3.7]
- Ravens will need a mix of iron/gold nuggets with different cawing sounds (to avoid people spam right clicking with golden nuggets in hand)
- Fixed server config on the .toml side of things (hot loaded, now properly works with chat disabling)
- Potential bug that could cause Ravens to keep spawning indefinitely, resulting in hundreds of Ravens
- Improved Raven spawning (it didn't work as I intended before, has a "ravens per player" new server config)
- Reviewed and made small adjustments to Raven death logic

## [1.3.6] [Forge-exclusive]
- Fixed auto-summon/whistle & chat disablement config not syncing correctly with server

## [1.3.5] [Forge-exclusive]
- Fixed a bug where recipes on Forge didn't register properly

## [1.3.4]
- More logging removed from Raven entity
- Removed/added commands regarding tamed ravens

## [1.3.3]
- Fixed chat-enabling via settings screen (C2S2C)
- Added auto/manual whistle to client config toml
- Added chat enabling to server config toml
- Added keybind ID's to lang.json

## [1.3.2]
- Added multilanguage support by using Minecraft's default font as fallback.

## [1.3.1]
- Fixed & revamped known players data for the recipient field.

## [1.3.0]
- If a job failed (when the courier raven timed out), mark it as failed on the server and don’t remove the job.
- On resummoning the raven by the sender, give the summoned raven the unretrieved scroll.

## [1.2.0]
- Majorly revamped courier/tamed raven spawn logic.
- Now also works in caves (unless very narrow).
- Mark job as “failed” but don’t remove it when the raven times out.
- Death/retrieve still removes the job.

## [1.1.7]
- Calendar config refactoring.
- Fortifying server-side effect.
- Added “days per month” config (to be synced with e.g. Serene Seasons)

## [1.1.6]
- Turned off several spammy pathfinding logs used for debugging (but some remain).

## [1.1.5]
- Spawn logic revamped & fixed.

## [1.1.4]
- Major bug fix for dedicated server: network and payload registrations.

## [1.1.3]
- Custom text widget: fixed text-wrapping and width detection.
- Scroll writing and stamp screen: fixed text width.

## [1.1.2]
- Seal stamp screen text polish.

## [1.1.1]
- Clear tamed raven data on player upon death + include death notification.

## [1.0.1]
- Fixed spawn logic (used to be super high spawn for test environment).
