// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenControl.java
package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenControl.java
 *
 * Per-entity command wrapper for RavenEntity.
 *
 * Why per-entity?
 *  - Each entity instance has its own navigation, AI, and GeckoLib state.
 *  - Sharing a single controller across multiple entities is not a sane pattern in Minecraft.
 *
 * This is intentionally thin: it exposes a stable API surface for future AI/interaction logic.
 */
public final class RavenControl {

    private static final Logger LOG = LogUtils.getLogger();

    private final RavenEntity raven;

    public RavenControl(RavenEntity raven) {
        this.raven = raven;
    }

    public RavenEntity entity() {
        return raven;
    }

    // -----------------
    // Variant commands
    // -----------------

    public void setVariant(RavenVariant variant) {
        try {
            raven.setRavenVariant(variant);
        } catch (Throwable t) {
            LOG.error("[RavenControl] Failed setVariant({})", variant, t);
        }
    }

    public void setVariantNormal() {
        setVariant(RavenVariant.NORMAL);
    }

    public void setVariantScroll() {
        setVariant(RavenVariant.SCROLL);
    }

    // -----------------
    // Animation commands
    // -----------------

    public void setAnimMode(RavenAnimMode mode) {
        try {
            raven.setAnimMode(mode);
        } catch (Throwable t) {
            LOG.error("[RavenControl] Failed setAnimMode({})", mode, t);
        }
    }

    public void animAuto() {
        setAnimMode(RavenAnimMode.AUTO);
    }

    public void animNoAir() {
        setAnimMode(RavenAnimMode.NO_AIR);
    }

    public void animInAir() {
        setAnimMode(RavenAnimMode.IN_AIR);
    }

    // -----------------
    // Movement commands
    // -----------------

    /**
     * Command the raven to pathfind to a target block position using vanilla navigation.
     * This does NOT disable AI; it uses the same pathing system AI uses.
     *
     * @return true if navigation accepted the request
     */
    public boolean moveTo(BlockPos pos, double speed) {
        try {
            return raven.commandMoveTo(pos, speed);
        } catch (Throwable t) {
            LOG.error("[RavenControl] Failed moveTo({}, {})", pos, speed, t);
            return false;
        }
    }

    public void stopMoving() {
        try {
            raven.commandStopMoving();
        } catch (Throwable t) {
            LOG.error("[RavenControl] Failed stopMoving()", t);
        }
    }
}
