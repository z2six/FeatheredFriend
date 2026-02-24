// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenAIState.java
package net.z2six.featheredfriend.entity.raven;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenAIState.java
 *
 * Simple server-side AI state machine for RavenEntity.
 *
 * IDLE_GROUND:
 *  - Raven sits on the ground, does not move (no walking animation).
 *  - Plays "no_air".
 *
 * ROAM_FLY:
 *  - Raven flies within home bounds.
 *  - Plays "in_air".
 *
 * FOLLOW_OWNER:
 *  - Raven flies near owner (only active if raven is tamed and has owner UUID).
 *  - Still respects home bounds; out-of-bounds triggers cooldown + return.
 *
 * AVOID_PLAYER:
 *  - Raven is actively fleeing from a nearby player (avoidance override).
 *  - Log/debug-only state in current implementation; movement logic is driven
 *    by the player-avoidance override in RavenEntity.
 *
 * RAVEN_CHEST_PERCH:
 *  - Hard-locked perch state for Raven Chest integration/debug alignment.
 *  - Keeps raven perched in place with NO_AIR animation and skips roaming/follow/
 *    avoidance/teleport-recovery logic.
 */
public enum RavenAIState {
    IDLE_GROUND(0),
    ROAM_FLY(1),
    FOLLOW_OWNER(2),
    AVOID_PLAYER(3),
    RAVEN_CHEST_PERCH(4);

    private final int id;

    RavenAIState(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RavenAIState fromId(int id) {
        for (RavenAIState s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        return IDLE_GROUND;
    }
}
