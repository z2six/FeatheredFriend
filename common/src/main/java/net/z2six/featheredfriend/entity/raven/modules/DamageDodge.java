// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/DamageDodge.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

/**
 * Handles "combat blink" behavior:
 *  - Raven is hit
 *  - Configured chance to dodge (cancel damage)
 *  - Teleport is attempted except when the hit is both non-dodged and lethal
 *  - After teleport sequence, Raven is forced into ROAM_FLY
 *
 * All heavy logic stays in RavenEntity, this class is only orchestration.
 */
public final class DamageDodge {
    private static final Logger LOG = LogUtils.getLogger();

    private DamageDodge() {
    }

    /**
     * Called from RavenEntity#hurt.
     *
     * @return true if damage should be cancelled (dodged), false if damage should proceed.
     */
    public static boolean handleHurt(RavenEntity raven,
                                     DamageSource source,
                                     float amount,
                                     boolean wouldDieIfLanded,
                                     float dodgeChance) {
        try {
            if (raven == null) return false;

            // Server-side only: client should not decide damage or teleport.
            if (raven.level() == null || raven.level().isClientSide) {
                return false;
            }

            if (!raven.isAlive()) {
                return false;
            }

            boolean teleportStartedOrQueued = false;
            float clampedDodgeChance = Math.max(0.0F, Math.min(1.0F, dodgeChance));
            // Dodge roll using raven's effective stats.
            RandomSource rnd = raven.getRandom();
            boolean dodge = (rnd.nextFloat() < clampedDodgeChance);

            // If this hit would kill and wasn't dodged, do not blink first.
            boolean shouldAttemptTeleport = dodge || !wouldDieIfLanded;

            if (shouldAttemptTeleport) {
                // Avoid recursion / spam: if we've already started a teleport sequence, we still want
                // the "post-teleport roam" intent, but we won't start a second sequence.
                // We'll ask RavenEntity to "ensure post teleport -> roam flight".
                try {
                    net.z2six.featheredfriend.entity.raven.modules.Teleportation tp = null;
                    try {
                        tp = raven.getTeleportation();
                    } catch (Throwable ignored) {
                        tp = null;
                    }

                    if (tp != null) {
                        teleportStartedOrQueued = tp.requestDamageBlinkTeleport(source, amount, "hurt", raven);
                    } else {
                        if (raven.tickCount % 40 == 0) {
                            // FIX: RavenEntity.LOG is private; use this class' logger.
                            LOG.warn("[DamageDodge] DamageBlink skipped: teleportation module null. pos={} src={} amt={}",
                                    raven.position(),
                                    (source == null ? "null" : source.toString()),
                                    amount);
                        }
                        teleportStartedOrQueued = false;
                    }
                } catch (Throwable t) {
                    if (raven.tickCount % 40 == 0) {
                        // FIX: RavenEntity.LOG is private; use this class' logger.
                        LOG.warn("[DamageDodge] DamageBlink failed safely: {}", t.toString());
                    }
                    teleportStartedOrQueued = false;
                }
            }

            if (raven.tickCount % 20 == 0) {
                LOG.debug("[DamageDodge] handleHurt: dodge={} chance={} wouldDieIfLanded={} teleportAttempted={} amount={} src={} teleportStartedOrQueued={} pos={} ai={}",
                        dodge, clampedDodgeChance, wouldDieIfLanded, shouldAttemptTeleport,
                        amount,
                        (source == null ? "null" : source.toString()),
                        teleportStartedOrQueued,
                        raven.position(),
                        raven.getAIState()
                );
            }

            // dodge decides whether damage is cancelled.
            return dodge;

        } catch (Throwable t) {
            // Fail-safe: never crash, never block damage if handler fails.
            try {
                if (raven != null && raven.tickCount % 80 == 0) {
                    LOG.warn("[DamageDodge] handleHurt failed safely: {}", t.toString());
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
