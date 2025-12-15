// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/RavenDamageDodgeHandler.java
package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import org.slf4j.Logger;

/**
 * Handles "combat blink" behavior:
 *  - Raven is hit
 *  - ALWAYS attempts a teleport sequence (100%)
 *  - 75% chance to dodge (cancel damage)
 *  - After teleport sequence, Raven is forced into ROAM_FLY
 *
 * All heavy logic stays in RavenEntity, this class is only orchestration.
 */
public final class RavenDamageDodgeHandler {
    private static final Logger LOG = LogUtils.getLogger();

    // 75% chance to dodge
    public static final float DODGE_CHANCE = 0.75f;

    private RavenDamageDodgeHandler() {
    }

    /**
     * Called from RavenEntity#hurt.
     *
     * @return true if damage should be cancelled (dodged), false if damage should proceed.
     */
    public static boolean handleHurt(RavenEntity raven, DamageSource source, float amount) {
        try {
            if (raven == null) return false;

            // Server-side only: client should not decide damage or teleport.
            if (raven.level() == null || raven.level().isClientSide) {
                return false;
            }

            if (!raven.isAlive()) {
                return false;
            }

            // Avoid recursion / spam: if we've already started a teleport sequence, we still want
            // the "post-teleport roam" intent, but we won't start a second sequence.
            // We'll ask RavenEntity to "ensure post teleport -> roam flight".
            boolean teleportStartedOrQueued = raven.requestDamageBlinkTeleport(source, amount, "hurt");

            // 75% dodge roll
            RandomSource rnd = raven.getRandom();
            boolean dodge = (rnd.nextFloat() < DODGE_CHANCE);

            if (raven.tickCount % 20 == 0) {
                LOG.info("[RavenDamageDodgeHandler] handleHurt: dodge={} chance={} amount={} src={} teleportStartedOrQueued={} pos={} ai={}",
                        dodge, DODGE_CHANCE, amount,
                        (source == null ? "null" : source.toString()),
                        teleportStartedOrQueued,
                        raven.position(),
                        raven.getAIState()
                );
            }

            // IMPORTANT: teleport is ALWAYS attempted, independent of dodge.
            // dodge decides whether damage is cancelled.
            return dodge;

        } catch (Throwable t) {
            // Fail-safe: never crash, never block damage if handler fails.
            try {
                if (raven != null && raven.tickCount % 80 == 0) {
                    LOG.warn("[RavenDamageDodgeHandler] handleHurt failed safely: {}", t.toString());
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
