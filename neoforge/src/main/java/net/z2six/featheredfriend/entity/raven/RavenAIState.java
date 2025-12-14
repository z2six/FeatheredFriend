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
 */
public enum RavenAIState {
    IDLE_GROUND(0),
    ROAM_FLY(1),
    FOLLOW_OWNER(2);

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
