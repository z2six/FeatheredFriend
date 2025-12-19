// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/Teleportation.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Teleportation / blink / stuck recovery + teleport FX + fade alpha.
 *
 * Design goals for refactor:
 * - Keep Teleportation self-contained: do NOT rely on RavenEntity.LOG or RavenEntity private fields directly.
 * - Avoid "duplicate methods" in RavenEntity by using reflection helpers where needed.
 * - Only require minimal RavenEntity public surface area (Entity.getEntityData(), etc.).
 * - Fail safely: if mappings/fields move, we log and skip instead of crashing.
 */
public final class Teleportation {

    // -------------------------------------------------------------------------------------------------
    // Core wiring
    // -------------------------------------------------------------------------------------------------

    private static final Logger LOG = LogUtils.getLogger();
    private final RavenEntity raven;

    public Teleportation(RavenEntity raven) {
        this.raven = raven;
    }

    // -------------------------------------------------------------------------------------------------
    // Synched data (registered against RavenEntity class)
    // -------------------------------------------------------------------------------------------------

    // Teleport FX: server tells client to play bursts + seed.
    public static final EntityDataAccessor<Integer> DATA_TELEPORT_FX_TICKS =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    public static final EntityDataAccessor<Long> DATA_TELEPORT_FX_SEED =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.LONG);

    // Teleport fade alpha (0..255), renderer reads this to fade.
    public static final EntityDataAccessor<Integer> DATA_TELEPORT_FADE_ALPHA =
            SynchedEntityData.defineId(RavenEntity.class, EntityDataSerializers.INT);

    // -------------------------------------------------------------------------------------------------
    // Teleport sequence state (fade-out -> invisible hold -> teleport -> invisible hold -> fade-in)
    // -------------------------------------------------------------------------------------------------

    public enum TeleportSeqPhase {
        NONE,
        FADING_OUT,
        TELEPORTING,
        FADING_IN
    }

    public TeleportSeqPhase teleportSeqPhase = TeleportSeqPhase.NONE;
    private int teleportSeqTicks = 0;

    @Nullable
    private Vec3 teleportSeqTarget = null;

    @Nullable
    private String teleportSeqReason = null;

    // Fade timing (match particle window)
    private static final int TELEPORT_FADE_TICKS_OUT = 6;
    private static final int TELEPORT_FADE_TICKS_IN = 6;

    private static final int TELEPORT_INVISIBLE_HOLD_BEFORE_TICKS = 6;
    private static final int TELEPORT_INVISIBLE_HOLD_AFTER_TICKS = 8;

    // -------------------------------------------------------------------------------------------------
    // Teleport recovery tuning
    // -------------------------------------------------------------------------------------------------

    private static final int TELEPORT_CHECK_INTERVAL_TICKS = 20; // every 1s
    private static final double TELEPORT_MIN_MOVED_DIST = 1.0D;  // less than 1 block => stuck
    private static final int TELEPORT_COOLDOWN_TICKS = 6 * 20;   // 6s
    private static final int TELEPORT_MAX_SEARCH_RADIUS = 6;     // blocks around current position
    private static final int TELEPORT_MAX_CANDIDATES = 48;       // cap attempts

    // Sampling state
    @Nullable
    private Vec3 teleportSampleLastPos = null;

    private int teleportSampleTicker = 0;
    private int teleportCooldownTicks = 0;
    private int teleportStuckSamples = 0;

    // -------------------------------------------------------------------------------------------------
    // Teleport FX tuning
    // -------------------------------------------------------------------------------------------------

    private static final int TELEPORT_FX_DURATION_TICKS = 10;

    private static final int TELEPORT_FX_BURST_MIN = 1;
    private static final int TELEPORT_FX_BURST_MAX = 1;

    private static final int TELEPORT_FX_BURST_SPACING_MIN_TICKS = 1;
    private static final int TELEPORT_FX_BURST_SPACING_MAX_TICKS = 3;

    private static final int TELEPORT_FX_PARTICLES_PER_BURST_MIN = 3;
    private static final int TELEPORT_FX_PARTICLES_PER_BURST_MAX = 3;

    private static final double TELEPORT_FX_SPREAD_XZ = 0.55D;
    private static final double TELEPORT_FX_SPREAD_Y = 0.85D;
    private static final double TELEPORT_FX_SPEED = 0.02D;

    // Scheduler state (server only)
    private int teleportFxBurstsRemaining = 0;
    private int teleportFxNextBurstInTicks = 0;
    private long teleportFxServerSeed = 0L;

    @Nullable
    private Vec3 teleportFxOriginA = null; // start pos (pre-teleport)

    @Nullable
    private Vec3 teleportFxOriginB = null; // end pos (destination)

    // -------------------------------------------------------------------------------------------------
    // Public-ish tiny helpers (keep simple, safe)
    // -------------------------------------------------------------------------------------------------

    public int getTeleportFxTicks(RavenEntity ravenEntity) {
        try {
            return ravenEntity.getEntityData().get(DATA_TELEPORT_FX_TICKS);
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] getTeleportFxTicks failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    public long getTeleportFxSeed(RavenEntity ravenEntity) {
        try {
            return ravenEntity.getEntityData().get(DATA_TELEPORT_FX_SEED);
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] getTeleportFxSeed failed safely: {}", t.toString());
            }
            return 0L;
        }
    }

    public int getTeleportFadeAlphaPublic(RavenEntity ravenEntity) {
        try {
            return ravenEntity.getEntityData().get(DATA_TELEPORT_FADE_ALPHA);
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] getTeleportFadeAlphaPublic failed safely: {}", t.toString());
            }
            return 255;
        }
    }

    public void setTeleportFadeAlpha(int alpha, RavenEntity ravenEntity) {
        try {
            ravenEntity.getEntityData().set(DATA_TELEPORT_FADE_ALPHA, Mth.clamp(alpha, 0, 255));
        } catch (Throwable t) {
            if (ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] setTeleportFadeAlpha failed safely: {}", t.toString());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Teleport sequence driver (server)
    // -------------------------------------------------------------------------------------------------

    public void tickTeleportSequenceServer(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;
            if (!(ravenEntity.level() instanceof ServerLevel serverLevel)) return;

            if (teleportSeqPhase == TeleportSeqPhase.NONE) return;

            // Freeze motion during sequence
            ravenEntity.setDeltaMovement(Vec3.ZERO);
            ravenEntity.hurtMarked = true;

            teleportSeqTicks++;

            switch (teleportSeqPhase) {
                case FADING_OUT -> {
                    int t = teleportSeqTicks;
                    int total = Math.max(1, TELEPORT_FADE_TICKS_OUT);

                    float k = Mth.clamp((float) t / (float) total, 0.0F, 1.0F);
                    int alpha = (int) Mth.lerp(k, 255.0F, 0.0F);
                    setTeleportFadeAlpha(alpha, ravenEntity);

                    if (t >= total) {
                        teleportSeqPhase = TeleportSeqPhase.TELEPORTING;
                        teleportSeqTicks = 0;
                        setTeleportFadeAlpha(0, ravenEntity);
                    }
                }

                case TELEPORTING -> {
                    setTeleportFadeAlpha(0, ravenEntity);

                    Vec3 target = teleportSeqTarget;
                    if (target == null) {
                        if (ravenEntity.tickCount % 20 == 0) {
                            LOG.warn("[Teleportation] TeleportSequence TELEPORTING but target=null. Aborting.");
                        }
                        teleportSeqPhase = TeleportSeqPhase.NONE;
                        setTeleportFadeAlpha(255, ravenEntity);
                        endTeleportPhase("teleport target null", ravenEntity);
                        return;
                    }

                    int holdBefore = Math.max(0, TELEPORT_INVISIBLE_HOLD_BEFORE_TICKS);
                    int holdAfter = Math.max(0, TELEPORT_INVISIBLE_HOLD_AFTER_TICKS);

                    int teleportTickIndex = holdBefore + 1;
                    int endOfAfterHoldTick = holdBefore + 1 + holdAfter;

                    boolean shouldTeleportNow = (teleportSeqTicks == teleportTickIndex);

                    if (shouldTeleportNow) {
                        boolean teleported = false;
                        Vec3 before = ravenEntity.position();

                        try {
                            // NOTE: Neo/vanilla signature may vary by mappings; this call has worked in your previous code.
                            teleported = ravenEntity.teleportTo(serverLevel, target.x, target.y, target.z, Set.of(), ravenEntity.getYRot(), ravenEntity.getXRot());
                        } catch (Throwable t) {
                            if (ravenEntity.tickCount % 20 == 0) {
                                LOG.warn("[Teleportation] TeleportSequence teleportTo failed safely: {}", t.toString());
                            }
                            teleported = false;
                        }

                        if (!teleported) {
                            // Fallback snap (won't update all server bookkeeping like teleportTo)
                            ravenEntity.setPos(target.x, target.y, target.z);
                        }

                        ravenEntity.setDeltaMovement(Vec3.ZERO);
                        ravenEntity.hurtMarked = true;

                        // Destination FX ONLY after teleport happens
                        try {
                            long fxSeed = getTeleportFxSeed(ravenEntity) ^ 0xD15C0FFEE0DDF00DL ^ (long) ravenEntity.tickCount;
                            startTeleportFxServer(fxSeed, null, ravenEntity.position(), ravenEntity);
                        } catch (Throwable t) {
                            if (ravenEntity.tickCount % 20 == 0) {
                                LOG.warn("[Teleportation] TeleportSequence destination FX failed safely: {}", t.toString());
                            }
                        }

                        if (ravenEntity.tickCount % 20 == 0) {
                            LOG.info("[Teleportation] TeleportSequence TELEPORTED reason={} teleported={} from={} newPos={} (holdBefore={} holdAfter={})",
                                    teleportSeqReason, teleported, before, ravenEntity.position(), holdBefore, holdAfter);
                        }
                    }

                    if (teleportSeqTicks >= endOfAfterHoldTick) {
                        teleportSeqPhase = TeleportSeqPhase.FADING_IN;
                        teleportSeqTicks = 0;
                        setTeleportFadeAlpha(0, ravenEntity);
                    }
                }

                case FADING_IN -> {
                    int t = teleportSeqTicks;
                    int total = Math.max(1, TELEPORT_FADE_TICKS_IN);

                    float k = Mth.clamp((float) t / (float) total, 0.0F, 1.0F);
                    int alpha = (int) Mth.lerp(k, 0.0F, 255.0F);
                    setTeleportFadeAlpha(alpha, ravenEntity);

                    if (t >= total) {
                        setTeleportFadeAlpha(255, ravenEntity);

                        TeleportSeqPhase old = teleportSeqPhase;
                        Vec3 oldTarget = teleportSeqTarget;
                        String oldReason = teleportSeqReason;

                        teleportSeqPhase = TeleportSeqPhase.NONE;
                        teleportSeqTicks = 0;
                        teleportSeqTarget = null;
                        teleportSeqReason = null;

                        endTeleportPhase("sequence done", ravenEntity);

                        // Clear stuck bookkeeping (private in RavenEntity) safely via reflection
                        setPrivateInt(ravenEntity, "stuckTicks", 0);
                        setPrivateDouble(ravenEntity, "lastDistToTarget", Double.NaN);
                        setPrivateInt(ravenEntity, "avoidanceCooldownTicks", 0);

                        // Reissue intent (if RavenEntity has this method)
                        invokeVoid1String(ravenEntity, "reissueMovementIntentAfterTeleport",
                                (oldReason == null ? "teleport sequence" : oldReason),
                                "[Teleportation] reissueMovementIntentAfterTeleport missing/failed");

                        if (ravenEntity.tickCount % 20 == 0) {
                            LOG.info("[Teleportation] TeleportSequence END phase={} reason={} target={}", old, oldReason, oldTarget);
                        }
                    }
                }

                default -> {
                    // no-op
                }
            }

        } catch (Throwable t) {
            LOG.error("[Teleportation] tickTeleportSequenceServer failed", t);

            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            teleportSeqTicks = 0;

            setTeleportFadeAlpha(255, ravenEntity);
            endTeleportPhase("failsafe tickTeleportSequenceServer", ravenEntity);
        }
    }

    public void startTeleportSequence(@Nullable Vec3 target, long fxSeed, String reason, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;
            if (target == null) return;

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.debug("[Teleportation] startTeleportSequence ignored (already active). phase={} reason={}", teleportSeqPhase, reason);
                }
                return;
            }

            teleportSeqTarget = target;
            teleportSeqReason = reason;
            teleportSeqTicks = 0;
            teleportSeqPhase = TeleportSeqPhase.FADING_OUT;

            beginTeleportPhase(reason, ravenEntity);
            setTeleportFadeAlpha(255, ravenEntity);

            // Start FX at origin immediately, destination FX only after teleport moment
            startTeleportFxServer(fxSeed, ravenEntity.position(), null, ravenEntity);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] TeleportSequence START reason={} pos={} target={} fadeOutTicks={} fadeInTicks={}",
                        reason, ravenEntity.position(), target, TELEPORT_FADE_TICKS_OUT, TELEPORT_FADE_TICKS_IN);
            }

        } catch (Throwable t) {
            LOG.error("[Teleportation] startTeleportSequence failed (reason={})", reason, t);

            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            teleportSeqTicks = 0;

            setTeleportFadeAlpha(255, ravenEntity);
            endTeleportPhase("failsafe startTeleportSequence", ravenEntity);
        }
    }

    public void beginTeleportPhase(String reason, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;

            ravenEntity.setDeltaMovement(Vec3.ZERO);
            ravenEntity.hurtMarked = true;

            // Invuln while fading/teleporting (your previous behavior)
            ravenEntity.setInvulnerable(true);

            // Gravity off to avoid fall jitter during teleport window
            ravenEntity.setNoGravity(true);

            // 1.21.1: no public setNoPhysics. Use the protected field (accessible because we are not a subclass).
            // We therefore MUST do this via reflection.
            setPrivateBoolean(ravenEntity, "noPhysics", true);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.debug("[Teleportation] beginTeleportPhase: id={} reason={} pos={} invuln={} fadeAlpha={}",
                        ravenEntity.getId(), reason, ravenEntity.position(), ravenEntity.isInvulnerable(), getTeleportFadeAlphaPublic(ravenEntity));
            }
        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] beginTeleportPhase failed safely: {}", t.toString());
            }
        }
    }

    public void endTeleportPhase(String reason, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;

            setPrivateBoolean(ravenEntity, "noPhysics", false);

            ravenEntity.setInvulnerable(false);
            ravenEntity.setInvisible(false); // we rely on alpha fade

            ravenEntity.hurtMarked = true;

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.debug("[Teleportation] endTeleportPhase: id={} reason={} pos={} invuln={} fadeAlpha={}",
                        ravenEntity.getId(), reason, ravenEntity.position(), ravenEntity.isInvulnerable(), getTeleportFadeAlphaPublic(ravenEntity));
            }
        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] endTeleportPhase failed safely: {}", t.toString());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Teleport FX scheduler (server)
    // -------------------------------------------------------------------------------------------------

    private void startTeleportFxServer(long seed, @Nullable Vec3 startPos, @Nullable Vec3 endPos, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;

            // Update synced seed and reset ticks.
            ravenEntity.getEntityData().set(DATA_TELEPORT_FX_SEED, seed);
            ravenEntity.getEntityData().set(DATA_TELEPORT_FX_TICKS, 0);

            teleportFxServerSeed = seed;

            // Allow either origin to be null (schedule A now, B later)
            if (startPos != null) {
                teleportFxOriginA = startPos.add(0.0D, 0.6D, 0.0D);
            }
            if (endPos != null) {
                teleportFxOriginB = endPos.add(0.0D, 0.6D, 0.0D);
            }

            int min = Math.min(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);
            int max = Math.max(TELEPORT_FX_BURST_MIN, TELEPORT_FX_BURST_MAX);

            int bursts;
            if (min == max) {
                bursts = min;
            } else {
                RandomSource rnd = RandomSource.create(seed ^ 0xC0FFEE1234ABCDEFL);
                bursts = min + rnd.nextInt(Math.max(1, max - min + 1));
            }

            bursts = Mth.clamp(bursts, 1, 12);

            // If FX is already running, extend bursts (smooth)
            if (teleportFxBurstsRemaining > 0) {
                teleportFxBurstsRemaining = Mth.clamp(teleportFxBurstsRemaining + bursts, 1, 12);
                teleportFxNextBurstInTicks = Math.min(teleportFxNextBurstInTicks, 1);
            } else {
                teleportFxBurstsRemaining = bursts;
                teleportFxNextBurstInTicks = 0;
            }

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] TeleportFX scheduled: id={} bursts={} seed={} originA={} originB={}",
                        ravenEntity.getId(), teleportFxBurstsRemaining, seed, teleportFxOriginA, teleportFxOriginB);
            }
        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] startTeleportFxServer failed safely: {}", t.toString());
            }
            teleportFxBurstsRemaining = 0;
            teleportFxNextBurstInTicks = 0;
            teleportFxOriginA = null;
            teleportFxOriginB = null;
        }
    }

    public void tickTeleportFxServer(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;
            if (!(ravenEntity.level() instanceof ServerLevel serverLevel)) return;

            if (teleportFxBurstsRemaining <= 0) return;

            if (teleportFxNextBurstInTicks > 0) {
                teleportFxNextBurstInTicks--;
                return;
            }

            // One scheduler fire => spawn at BOTH origins (start + end), if present
            if (teleportFxOriginA != null) {
                spawnEnderpopBurst(serverLevel, teleportFxOriginA.x, teleportFxOriginA.y, teleportFxOriginA.z,
                        teleportFxServerSeed ^ 0xA1A1A1A1A1A1A1A1L, "teleportFx A", ravenEntity);
            }

            if (teleportFxOriginB != null) {
                spawnEnderpopBurst(serverLevel, teleportFxOriginB.x, teleportFxOriginB.y, teleportFxOriginB.z,
                        teleportFxServerSeed ^ 0xB2B2B2B2B2B2B2B2L, "teleportFx B", ravenEntity);
            }

            teleportFxBurstsRemaining--;

            if (teleportFxBurstsRemaining > 0) {
                RandomSource rnd = RandomSource.create(teleportFxServerSeed ^ 0x55AA55AA55AA55AAL ^ (long) teleportFxBurstsRemaining);
                int min = Math.min(TELEPORT_FX_BURST_SPACING_MIN_TICKS, TELEPORT_FX_BURST_SPACING_MAX_TICKS);
                int max = Math.max(TELEPORT_FX_BURST_SPACING_MIN_TICKS, TELEPORT_FX_BURST_SPACING_MAX_TICKS);
                int gap = (min == max) ? min : (min + rnd.nextInt(Math.max(1, max - min + 1)));
                teleportFxNextBurstInTicks = Mth.clamp(gap, 0, 20);
            } else {
                teleportFxNextBurstInTicks = 0;
                teleportFxOriginA = null;
                teleportFxOriginB = null;
            }

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] tickTeleportFxServer failed safely: {}", t.toString());
            }
            teleportFxBurstsRemaining = 0;
            teleportFxNextBurstInTicks = 0;
            teleportFxOriginA = null;
            teleportFxOriginB = null;
        }
    }

    public void spawnEnderpopBurst(ServerLevel level, double x, double y, double z, long seed, String why, RavenEntity ravenEntity) {
        try {
            if (level == null || ravenEntity == null) return;

            RandomSource rnd = RandomSource.create(seed ^ (long) ravenEntity.getId() * 0x9E3779B97F4A7C15L ^ (long) ravenEntity.tickCount);

            int perMin = Math.min(TELEPORT_FX_PARTICLES_PER_BURST_MIN, TELEPORT_FX_PARTICLES_PER_BURST_MAX);
            int perMax = Math.max(TELEPORT_FX_PARTICLES_PER_BURST_MIN, TELEPORT_FX_PARTICLES_PER_BURST_MAX);

            int count = (perMin == perMax) ? perMin : (perMin + rnd.nextInt(Math.max(1, perMax - perMin + 1)));
            count = Mth.clamp(count, 1, 64);

            level.sendParticles(
                    net.z2six.featheredfriend.registry.FFNeoForgeParticles.ENDERPOP.get(),
                    x, y, z,
                    count,
                    TELEPORT_FX_SPREAD_XZ, TELEPORT_FX_SPREAD_Y, TELEPORT_FX_SPREAD_XZ,
                    TELEPORT_FX_SPEED
            );

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.debug("[Teleportation] EnderpopBurst: id={} why={} count={} spread=({}, {}, {}) speed={} pos=({}, {}, {}) seed={}",
                        ravenEntity.getId(),
                        why,
                        count,
                        String.format("%.2f", TELEPORT_FX_SPREAD_XZ),
                        String.format("%.2f", TELEPORT_FX_SPREAD_Y),
                        String.format("%.2f", TELEPORT_FX_SPREAD_XZ),
                        String.format("%.3f", TELEPORT_FX_SPEED),
                        String.format("%.2f", x),
                        String.format("%.2f", y),
                        String.format("%.2f", z),
                        seed
                );
            }

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] spawnEnderpopBurst failed safely: {}", t.toString());
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Teleport target finding (1x1x1 and 3x3x3 pockets)
    // -------------------------------------------------------------------------------------------------

    @Nullable
    public BlockPos findNearbyEmptyTeleportBlock(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null || ravenEntity.level() == null) return null;

            BlockPos base = ravenEntity.blockPosition();
            RandomSource rnd = ravenEntity.getRandom();

            for (int i = 0; i < TELEPORT_MAX_CANDIDATES; i++) {
                int rx = rnd.nextInt(TELEPORT_MAX_SEARCH_RADIUS * 2 + 1) - TELEPORT_MAX_SEARCH_RADIUS;
                int rz = rnd.nextInt(TELEPORT_MAX_SEARCH_RADIUS * 2 + 1) - TELEPORT_MAX_SEARCH_RADIUS;
                int ry = rnd.nextInt(5) - 2;

                BlockPos p = base.offset(rx, ry, rz);

                if (!ravenEntity.level().isEmptyBlock(p)) continue;
                if (!ravenEntity.level().isEmptyBlock(p.above())) continue;
                if (!ravenEntity.level().getFluidState(p).isEmpty()) continue;

                Vec3 center = new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (invokeIsOutOfHomeBounds(ravenEntity, center)) continue;

                // Optional: allow leaves below, but note it.
                BlockState below = ravenEntity.level().getBlockState(p.below());
                if (below != null && below.is(BlockTags.LEAVES)) {
                    // allowed
                }

                return p;
            }

            return null;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 60 == 0) {
                LOG.warn("[Teleportation] findNearbyEmptyTeleportBlock failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    public BlockPos findNearbyEmptyTeleportBlock3x3x3(int radiusBlocks, int maxCandidates, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null || ravenEntity.level() == null) return null;

            int r = Math.max(1, radiusBlocks);
            int candidates = Math.max(1, maxCandidates);

            BlockPos base = ravenEntity.blockPosition();
            RandomSource rnd = ravenEntity.getRandom();

            int yBand = 4;

            for (int i = 0; i < candidates; i++) {
                int rx = rnd.nextInt(r * 2 + 1) - r;
                int rz = rnd.nextInt(r * 2 + 1) - r;
                int ry = rnd.nextInt(yBand * 2 + 1) - yBand;

                BlockPos p = base.offset(rx, ry, rz);

                if (!ravenEntity.level().isEmptyBlock(p)) continue;
                if (!ravenEntity.level().isEmptyBlock(p.above())) continue;
                if (!ravenEntity.level().getFluidState(p).isEmpty()) continue;

                boolean ok = isEmptyTeleportPocket3x3x3At(p, ravenEntity);
                if (!ok) continue;

                return p;
            }

            return null;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 60 == 0) {
                LOG.warn("[Teleportation] findNearbyEmptyTeleportBlock3x3x3 failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    public BlockPos findEmptyTeleportBlock3x3x3Near(BlockPos center, int radiusBlocks, int maxCandidates, long seed, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null || ravenEntity.level() == null) return null;
            if (center == null) return null;

            int r = Math.max(1, radiusBlocks);
            int candidates = Math.max(1, maxCandidates);

            RandomSource rnd = RandomSource.create(seed ^ 0xA11CE5ED1234L);
            int yBand = 4;

            for (int i = 0; i < candidates; i++) {
                int rx = rnd.nextInt(r * 2 + 1) - r;
                int rz = rnd.nextInt(r * 2 + 1) - r;
                int ry = rnd.nextInt(yBand * 2 + 1) - yBand;

                BlockPos p = center.offset(rx, ry, rz);

                if (!ravenEntity.level().isEmptyBlock(p)) continue;
                if (!ravenEntity.level().isEmptyBlock(p.above())) continue;
                if (!ravenEntity.level().getFluidState(p).isEmpty()) continue;

                Vec3 pv = new Vec3(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (invokeIsOutOfHomeBounds(ravenEntity, pv)) continue;

                if (!isEmptyTeleportPocket3x3x3At(p, ravenEntity)) continue;

                return p;
            }

            if (ravenEntity.tickCount % 60 == 0) {
                LOG.debug("[Teleportation] findEmptyTeleportBlock3x3x3Near: no pocket found center={} r={} candidates={} seed={}",
                        center, r, candidates, seed);
            }

            return null;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 60 == 0) {
                LOG.warn("[Teleportation] findEmptyTeleportBlock3x3x3Near failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isEmptyTeleportPocket3x3x3At(BlockPos anchor, RavenEntity ravenEntity) {
        try {
            if (anchor == null || ravenEntity == null || ravenEntity.level() == null) return false;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos p = anchor.offset(dx, dy, dz);
                        if (!ravenEntity.level().isEmptyBlock(p)) return false;
                        if (!ravenEntity.level().getFluidState(p).isEmpty()) return false;
                    }
                }
            }

            return true;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 120 == 0) {
                LOG.warn("[Teleportation] isEmptyTeleportPocket3x3x3At failed safely: {}", t.toString());
            }
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Teleport recovery (stuck sampler + attempt)
    // -------------------------------------------------------------------------------------------------

    public void tickTeleportRecoverySampler(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;

            if (teleportCooldownTicks > 0) {
                teleportCooldownTicks--;
            }

            RavenAIState st = ravenEntity.getAIState();

            boolean excludedByPhase =
                    (teleportSeqPhase != TeleportSeqPhase.NONE)
                            || (st == RavenAIState.IDLE_GROUND);

            boolean eligible;
            try {
                // If RavenEntity has its own method, prefer it.
                eligible = invokeBoolean0(ravenEntity, "isTeleportRecoveryEligible", true);
            } catch (Throwable t) {
                eligible = true;
            }

            if (excludedByPhase || !eligible) {
                teleportSampleLastPos = ravenEntity.position();
                teleportSampleTicker = 0;
                teleportStuckSamples = 0;

                if (excludedByPhase && ravenEntity.tickCount % 80 == 0) {
                    Object landingPhase = getPrivateObject(ravenEntity, "landingPhase");
                    int idleLockTicks = getPrivateInt(ravenEntity, "idleLockTicks", -1);
                    LOG.debug("[Teleportation] TeleportRecovery: sampling skipped. phase={} ai={} landingPhase={} idleLockTicks={} pos={}",
                            teleportSeqPhase, st, landingPhase, idleLockTicks, ravenEntity.position());
                }
                return;
            }

            teleportSampleTicker++;
            if (teleportSampleTicker < TELEPORT_CHECK_INTERVAL_TICKS) {
                return;
            }
            teleportSampleTicker = 0;

            Vec3 now = ravenEntity.position();
            if (teleportSampleLastPos == null) {
                teleportSampleLastPos = now;
                teleportStuckSamples = 0;
                return;
            }

            double moved = now.distanceTo(teleportSampleLastPos);
            teleportSampleLastPos = now;

            if (teleportCooldownTicks > 0) {
                teleportStuckSamples = 0;
                return;
            }

            boolean movedEnough = moved >= TELEPORT_MIN_MOVED_DIST;
            if (movedEnough) {
                if (teleportStuckSamples > 0 && ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] TeleportRecovery: progress resumed; reset stuckSamples. moved={} >= {} ai={} pos={} vel={}",
                            String.format("%.3f", moved),
                            TELEPORT_MIN_MOVED_DIST,
                            st,
                            now,
                            ravenEntity.getDeltaMovement());
                }
                teleportStuckSamples = 0;
                return;
            }

            teleportStuckSamples++;

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] TeleportRecovery: stuck sample {}/3 (moved={} < {}) ai={} pos={} vel={}",
                        teleportStuckSamples,
                        String.format("%.3f", moved),
                        TELEPORT_MIN_MOVED_DIST,
                        st,
                        now,
                        ravenEntity.getDeltaMovement());
            }

            if (teleportStuckSamples < 3) {
                return;
            }

            teleportStuckSamples = 0;

            boolean ok = attemptTeleportRecovery("stuck 3x (3s) moved<1", ravenEntity);
            teleportCooldownTicks = ok ? TELEPORT_COOLDOWN_TICKS : Math.min(TELEPORT_COOLDOWN_TICKS, 2 * 20);

        } catch (Throwable t) {
            LOG.error("[Teleportation] tickTeleportRecoverySampler failed", t);
            if (ravenEntity != null) {
                teleportSampleLastPos = ravenEntity.position();
            } else {
                teleportSampleLastPos = null;
            }
            teleportSampleTicker = 0;
            teleportStuckSamples = 0;
        }
    }

    public boolean attemptTeleportRecovery(String reason, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return false;
            if (ravenEntity.level() == null) return false;
            if (ravenEntity.level().isClientSide) return false;
            if (!(ravenEntity.level() instanceof ServerLevel)) return false;

            boolean eligible = invokeBoolean0(ravenEntity, "isTeleportRecoveryEligible", true);
            if (!eligible) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] TeleportRecovery skipped (not eligible). reason={} state={} pos={}",
                            reason, ravenEntity.getAIState(), ravenEntity.position());
                }
                return false;
            }

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.debug("[Teleportation] TeleportRecovery ignored (sequence active). phase={} reason={}", teleportSeqPhase, reason);
                }
                return true; // treated as handled
            }

            BlockPos targetPos = findNearbyEmptyTeleportBlock(ravenEntity);
            if (targetPos == null) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.warn("[Teleportation] TeleportRecovery: no empty 1x1x1 found near pos={} reason={}", ravenEntity.position(), reason);
                }
                return false;
            }

            Vec3 end = new Vec3(targetPos.getX() + 0.5D, targetPos.getY(), targetPos.getZ() + 0.5D);
            long fxSeed = ravenEntity.getUUID().getLeastSignificantBits() ^ (long) ravenEntity.tickCount ^ targetPos.asLong();

            startTeleportSequence(end, fxSeed, reason, ravenEntity);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] TeleportRecovery armed sequence reason={} targetBlock={} endPos={}",
                        reason, targetPos, end);
            }

            return true;

        } catch (Throwable t) {
            LOG.error("[Teleportation] attemptTeleportRecovery failed (reason={})", reason, t);

            teleportSeqPhase = TeleportSeqPhase.NONE;
            teleportSeqTarget = null;
            teleportSeqReason = null;
            teleportSeqTicks = 0;

            setTeleportFadeAlpha(255, ravenEntity);
            endTeleportPhase("failsafe attemptTeleportRecovery", ravenEntity);

            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Panic / damage blink / random flight blink
    // NOTE: Many of your previous implementations touched RavenEntity private fields heavily.
    //       Here we keep behavior but use reflection to avoid bloating RavenEntity's public API.
    // -------------------------------------------------------------------------------------------------

    public void requestPanicTeleportAwayFromPlayer(@Nullable Player player, double distToPlayer, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;
            if (!ravenEntity.isAlive()) return;

            // Suppress when follow/lure active (prefer your existing behavior)
            boolean followOverride = invokeBoolean0(ravenEntity, "isFollowOverrideActive", false);
            boolean lureActive = invokeBoolean0(ravenEntity, "isLureFollowActive", false);

            if ((followOverride && ravenEntity.getAIState() == RavenAIState.FOLLOW_OWNER) || lureActive) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] requestPanicTeleportAwayFromPlayer suppressed (follow/lure active). player={} dist={} pos={} ai={} followOverride={} lureActive={}",
                            (player == null ? "null" : player.getName().getString()),
                            String.format("%.2f", distToPlayer),
                            ravenEntity.position(),
                            ravenEntity.getAIState(),
                            followOverride,
                            lureActive);
                }
                return;
            }

            if (player == null || !player.isAlive() || player.isSpectator()) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] requestPanicTeleportAwayFromPlayer: invalid player (skip)");
                }
                return;
            }

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] requestPanicTeleportAwayFromPlayer: teleportSeqPhase={} (skip)", teleportSeqPhase);
                }
                return;
            }

            // Use lastPanicTeleportTick if present, else don't interval-throttle.
            final int PANIC_MIN_INTERVAL_TICKS = 30;
            int lastPanic = getPrivateInt(ravenEntity, "lastPanicTeleportTick", Integer.MIN_VALUE);
            int dt = (lastPanic == Integer.MIN_VALUE) ? Integer.MAX_VALUE : (ravenEntity.tickCount - lastPanic);
            if (dt >= 0 && dt < PANIC_MIN_INTERVAL_TICKS) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] PanicTeleport suppressed by interval: dt={} < {} dist={} player={} pos={}",
                            dt, PANIC_MIN_INTERVAL_TICKS, String.format("%.2f", distToPlayer), player.getName().getString(), ravenEntity.position());
                }
                return;
            }

            final Vec3 ravenPos = ravenEntity.position();
            final Vec3 playerPos = player.position();

            // Teleport behind player's look direction (rear 180° cone)
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double lLen = Math.sqrt(lx * lx + lz * lz);

            if (lLen < 1.0E-4D) {
                RandomSource rnd = ravenEntity.getRandom();
                double ang = rnd.nextDouble() * (Math.PI * 2.0D);
                lx = Math.cos(ang);
                lz = Math.sin(ang);
                lLen = 1.0D;
            }

            double bx = -lx / lLen;
            double bz = -lz / lLen;

            RandomSource rnd = ravenEntity.getRandom();
            double yawOffset = (rnd.nextDouble() * Math.PI) - (Math.PI * 0.5D); // [-90..+90] deg

            double cos = Math.cos(yawOffset);
            double sin = Math.sin(yawOffset);

            double rx = bx * cos - bz * sin;
            double rz = bx * sin + bz * cos;

            double rLen = Math.sqrt(rx * rx + rz * rz);
            if (rLen < 1.0E-4D) {
                rx = bx;
                rz = bz;
                rLen = 1.0D;
            }
            rx /= rLen;
            rz /= rLen;

            final double TELEPORT_DIST = 30.0D;

            double tx = playerPos.x + rx * TELEPORT_DIST;
            double tz = playerPos.z + rz * TELEPORT_DIST;

            // Y policy: keep near raven Y band, clamp to home bounds via RavenEntity methods if present
            int tyInt = invokeClampYToHomeBounds(ravenEntity, Mth.floor(ravenPos.y));
            double ty = tyInt + 0.75D;

            Vec3 raw = new Vec3(tx, ty, tz);
            Vec3 clamped = invokeClampTargetToHomeBounds(ravenEntity, raw);

            BlockPos center = BlockPos.containing(clamped.x, clamped.y, clamped.z);

            long seed =
                    ravenEntity.getUUID().getLeastSignificantBits()
                            ^ (long) ravenEntity.tickCount
                            ^ player.getUUID().getMostSignificantBits()
                            ^ center.asLong()
                            ^ 0x5AC1F1EDBEEFL;

            BlockPos targetBlock = findEmptyTeleportBlock3x3x3Near(center, 10, 260, seed, ravenEntity);
            if (targetBlock == null) {
                targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 90, ravenEntity);
            }
            if (targetBlock == null) {
                targetBlock = findNearbyEmptyTeleportBlock(ravenEntity);
            }

            if (targetBlock == null) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.warn("[Teleportation] PanicTeleport: no valid teleport target found. player={} dist={} ravenPos={} raw={} clamped={}",
                            player.getName().getString(),
                            String.format("%.2f", distToPlayer),
                            ravenPos,
                            raw,
                            clamped);
                }
                setPrivateInt(ravenEntity, "lastPanicTeleportTick", ravenEntity.tickCount);
                return;
            }

            Vec3 end = new Vec3(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D);

            if (invokeIsOutOfHomeBounds(ravenEntity, end)) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.warn("[Teleportation] PanicTeleport: selected end out of home bounds. end={} basePos={}", end, ravenEntity.position());
                }
                setPrivateInt(ravenEntity, "lastPanicTeleportTick", ravenEntity.tickCount);
                return;
            }

            // After panic teleport, prefer roam flight (if PostTeleportIntent exists)
            setPostTeleportIntentIfPossible(ravenEntity, "ROAM_FLIGHT");

            // Cancel current movement intent via reflection
            invokeVoid0(ravenEntity, "clearFlyTarget", "[Teleportation] clearFlyTarget missing/failed");
            invokeVoid1String(ravenEntity, "clearPlannedPath", "panic teleport", "[Teleportation] clearPlannedPath missing/failed");

            // Clear avoidance override state if present
            setPrivateInt(ravenEntity, "playerAvoidanceOverrideTicks", 0);
            setPrivateInt(ravenEntity, "playerAvoidanceRearmCooldownTicks", 20);

            long fxSeed =
                    ravenEntity.getUUID().getLeastSignificantBits()
                            ^ (long) ravenEntity.tickCount
                            ^ targetBlock.asLong()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ 0xD15C0FFEE0DDF00DL;

            String why = "panic teleport behind: player=" + player.getName().getString()
                    + " dist=" + String.format("%.2f", distToPlayer)
                    + " yawOffsetDeg=" + String.format("%.1f", (yawOffset * (180.0D / Math.PI)));

            setPrivateInt(ravenEntity, "lastPanicTeleportTick", ravenEntity.tickCount);
            startTeleportSequence(end, fxSeed, why, ravenEntity);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] PanicTeleport STARTED: reason={} ravenPos={} playerPos={} behindDir=({}, {}) dist={} raw={} clamped={} targetBlock={} end={} fxSeed={}",
                        why,
                        ravenPos,
                        playerPos,
                        String.format("%.3f", rx),
                        String.format("%.3f", rz),
                        String.format("%.1f", TELEPORT_DIST),
                        raw,
                        clamped,
                        targetBlock,
                        end,
                        fxSeed);
            }

        } catch (Throwable t) {
            LOG.error("[Teleportation] requestPanicTeleportAwayFromPlayer failed safely", t);
            try {
                if (ravenEntity != null) {
                    setPrivateInt(ravenEntity, "lastPanicTeleportTick", ravenEntity.tickCount);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean requestDamageBlinkTeleport(@Nullable net.minecraft.world.damagesource.DamageSource source, float amount, String reasonTag, RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return false;
            if (ravenEntity.level() == null) return false;
            if (ravenEntity.level().isClientSide) return false;
            if (!ravenEntity.isAlive()) return false;

            // Queue post-teleport behavior if possible
            setPostTeleportIntentIfPossible(ravenEntity, "ROAM_FLIGHT");

            if (teleportSeqPhase != TeleportSeqPhase.NONE) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.debug("[Teleportation] requestDamageBlinkTeleport: sequence already active; intent=ROAM_FLIGHT reasonTag={} phase={} pos={}",
                            reasonTag, teleportSeqPhase, ravenEntity.position());
                }
                return true;
            }

            // Interval gate if RavenEntity has it; if not, just proceed.
            int lastTick = getPrivateInt(ravenEntity, "lastDamageBlinkTick", Integer.MIN_VALUE);
            int minInterval = getPrivateStaticInt(RavenEntity.class, "DAMAGE_BLINK_MIN_INTERVAL_TICKS", 0);
            if (lastTick != Integer.MIN_VALUE && minInterval > 0) {
                int dt = ravenEntity.tickCount - lastTick;
                if (dt < minInterval) {
                    if (ravenEntity.tickCount % 20 == 0) {
                        LOG.debug("[Teleportation] requestDamageBlinkTeleport: suppressed by interval dt={} < {} reasonTag={} pos={}",
                                dt, minInterval, reasonTag, ravenEntity.position());
                    }
                    return false;
                }
            }
            setPrivateInt(ravenEntity, "lastDamageBlinkTick", ravenEntity.tickCount);

            BlockPos targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 80, ravenEntity);
            if (targetBlock == null) {
                targetBlock = findNearbyEmptyTeleportBlock(ravenEntity);
            }

            if (targetBlock == null) {
                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.warn("[Teleportation] requestDamageBlinkTeleport: no valid teleport target found. reasonTag={} src={} amt={} pos={}",
                            reasonTag,
                            (source == null ? "null" : source.toString()),
                            amount,
                            ravenEntity.position());
                }
                return false;
            }

            Vec3 end = new Vec3(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D);

            final long DAMAGE_BLINK_SALT = 0xD0D6E5EEDL;
            long fxSeed =
                    ravenEntity.getUUID().getLeastSignificantBits()
                            ^ (long) ravenEntity.tickCount
                            ^ targetBlock.asLong()
                            ^ DAMAGE_BLINK_SALT
                            ^ (long) (Float.floatToIntBits(amount));

            startTeleportSequence(end, fxSeed, "damage blink: " + reasonTag, ravenEntity);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] requestDamageBlinkTeleport: STARTED reasonTag={} src={} amt={} fromPos={} toBlock={} end={} fxSeed={}",
                        reasonTag,
                        (source == null ? "null" : source.toString()),
                        amount,
                        ravenEntity.position(),
                        targetBlock,
                        end,
                        fxSeed);
            }

            return true;

        } catch (Throwable t) {
            LOG.error("[Teleportation] requestDamageBlinkTeleport failed reasonTag={}", reasonTag, t);
            return false;
        }
    }

    public void tickRandomFlightTeleportBlink(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return;
            if (ravenEntity.level() == null) return;
            if (ravenEntity.level().isClientSide) return;
            if (!ravenEntity.isAlive()) return;

            if (teleportSeqPhase != TeleportSeqPhase.NONE) return;

            // getAIStateForDebug might be private; fallback to getAIState
            RavenAIState st;
            try {
                Object v = invokeObject0(ravenEntity, "getAIStateForDebug");
                st = (v instanceof RavenAIState) ? (RavenAIState) v : ravenEntity.getAIState();
            } catch (Throwable ignored) {
                st = ravenEntity.getAIState();
            }

            boolean inFlight =
                    (st == RavenAIState.ROAM_FLY)
                            || (st == RavenAIState.FOLLOW_OWNER)
                            || (st == RavenAIState.AVOID_PLAYER);

            if (!inFlight) {
                // If these session fields exist in RavenEntity, reset them; otherwise ignore.
                setPrivateObject(ravenEntity, "flightTeleportLastAI", null);
                setPrivateInt(ravenEntity, "flightTeleportBudget", 0);
                setPrivateInt(ravenEntity, "flightTeleportUsed", 0);
                setPrivateInt(ravenEntity, "flightTeleportCheckCooldownTicks", 0);
                setPrivateInt(ravenEntity, "flightTeleportHardCooldownTicks", 0);
                return;
            }

            Object lastAI = getPrivateObject(ravenEntity, "flightTeleportLastAI");
            int budget = getPrivateInt(ravenEntity, "flightTeleportBudget", 0);
            int used = getPrivateInt(ravenEntity, "flightTeleportUsed", 0);
            int checkCd = getPrivateInt(ravenEntity, "flightTeleportCheckCooldownTicks", 0);
            int hardCd = getPrivateInt(ravenEntity, "flightTeleportHardCooldownTicks", 0);

            boolean newSession = (lastAI == null) || (lastAI != st);
            if (newSession) {
                setPrivateObject(ravenEntity, "flightTeleportLastAI", st);

                int roll = ravenEntity.getRandom().nextInt(100);
                if (roll < 55) budget = 0;
                else if (roll < 90) budget = 1;
                else budget = 2;

                used = 0;

                checkCd = 40 + ravenEntity.getRandom().nextInt(60);
                hardCd = 0;

                setPrivateInt(ravenEntity, "flightTeleportBudget", budget);
                setPrivateInt(ravenEntity, "flightTeleportUsed", used);
                setPrivateInt(ravenEntity, "flightTeleportCheckCooldownTicks", checkCd);
                setPrivateInt(ravenEntity, "flightTeleportHardCooldownTicks", hardCd);

                if (ravenEntity.tickCount % 20 == 0) {
                    LOG.debug("[Teleportation] FlightBlink session started ai={} budget={} pos={}", st, budget, ravenEntity.position());
                }
            }

            if (budget <= 0) return;
            if (used >= budget) return;

            if (hardCd > 0) {
                hardCd--;
                setPrivateInt(ravenEntity, "flightTeleportHardCooldownTicks", hardCd);
            }

            if (checkCd > 0) {
                checkCd--;
                setPrivateInt(ravenEntity, "flightTeleportCheckCooldownTicks", checkCd);
                return;
            }

            checkCd = 20 + ravenEntity.getRandom().nextInt(25);
            setPrivateInt(ravenEntity, "flightTeleportCheckCooldownTicks", checkCd);

            Vec3 vel = ravenEntity.getDeltaMovement();
            if (vel.lengthSqr() < 0.004D) {
                return;
            }

            double p = 0.26D;
            if (ravenEntity.getRandom().nextDouble() > p) {
                return;
            }

            if (hardCd > 0) {
                return;
            }

            BlockPos targetBlock = findNearbyEmptyTeleportBlock3x3x3(10, 60, ravenEntity);
            if (targetBlock == null) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] FlightBlink: no valid 3x3x3 empty space found near pos={} ai={} used={}/{}",
                            ravenEntity.position(), st, used, budget);
                }
                return;
            }

            Vec3 end = new Vec3(targetBlock.getX() + 0.5D, targetBlock.getY(), targetBlock.getZ() + 0.5D);
            if (invokeIsOutOfHomeBounds(ravenEntity, end)) {
                if (ravenEntity.tickCount % 40 == 0) {
                    LOG.debug("[Teleportation] FlightBlink: candidate out of home bounds end={} basePos={}", end, ravenEntity.position());
                }
                return;
            }

            final long BLINK_SALT = 0xB11E5EEDL;
            long fxSeed = ravenEntity.getUUID().getLeastSignificantBits()
                    ^ (long) ravenEntity.tickCount
                    ^ targetBlock.asLong()
                    ^ BLINK_SALT;

            startTeleportSequence(end, fxSeed, "random flight blink", ravenEntity);

            used++;
            setPrivateInt(ravenEntity, "flightTeleportUsed", used);

            hardCd = 60 + ravenEntity.getRandom().nextInt(80);
            setPrivateInt(ravenEntity, "flightTeleportHardCooldownTicks", hardCd);

            if (ravenEntity.tickCount % 20 == 0) {
                LOG.info("[Teleportation] FlightBlink TRIGGERED used={}/{} ai={} fromPos={} toBlock={} end={} vel={}",
                        used, budget, st, ravenEntity.position(), targetBlock, end, vel);
            }

        } catch (Throwable t) {
            LOG.error("[Teleportation] tickRandomFlightTeleportBlink failed", t);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Client lerp introspection (optional helpers)
    // -------------------------------------------------------------------------------------------------

    public int getClientLerpStepsPublic(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return 0;
            if (ravenEntity.level() == null) return 0;
            if (!ravenEntity.level().isClientSide) return 0;

            // Vanilla field on Entity; may shift with mappings.
            Object v = getAnyFieldValue(ravenEntity, "lerpSteps", "lerpStepsRemaining", "lerpSteps_");
            if (v instanceof Integer) return (Integer) v;

            return 0;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] getClientLerpStepsPublic failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    public double getClientLerpTargetDistSqrPublic(RavenEntity ravenEntity) {
        try {
            if (ravenEntity == null) return 0.0D;
            if (ravenEntity.level() == null) return 0.0D;
            if (!ravenEntity.level().isClientSide) return 0.0D;

            Double lerpX = (Double) getAnyFieldValue(ravenEntity, "lerpX", "xLerp", "targetX");
            Double lerpY = (Double) getAnyFieldValue(ravenEntity, "lerpY", "yLerp", "targetY");
            Double lerpZ = (Double) getAnyFieldValue(ravenEntity, "lerpZ", "zLerp", "targetZ");

            if (lerpX == null || lerpY == null || lerpZ == null) return 0.0D;

            double dx = lerpX - ravenEntity.getX();
            double dy = lerpY - ravenEntity.getY();
            double dz = lerpZ - ravenEntity.getZ();
            return dx * dx + dy * dy + dz * dz;

        } catch (Throwable t) {
            if (ravenEntity != null && ravenEntity.tickCount % 80 == 0) {
                LOG.warn("[Teleportation] getClientLerpTargetDistSqrPublic failed safely: {}", t.toString());
            }
            return 0.0D;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Reflection helpers (centralized; best-effort; never crash)
    // -------------------------------------------------------------------------------------------------

    private static void setPrivateInt(Object obj, String fieldName, int value) {
        try {
            if (obj == null || fieldName == null) return;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.setInt(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static int getPrivateInt(Object obj, String fieldName, int fallback) {
        try {
            if (obj == null || fieldName == null) return fallback;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return fallback;
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setPrivateDouble(Object obj, String fieldName, double value) {
        try {
            if (obj == null || fieldName == null) return;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.setDouble(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setPrivateBoolean(Object obj, String fieldName, boolean value) {
        try {
            if (obj == null || fieldName == null) return;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.setBoolean(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setPrivateObject(Object obj, String fieldName, @Nullable Object value) {
        try {
            if (obj == null || fieldName == null) return;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static Object getPrivateObject(Object obj, String fieldName) {
        try {
            if (obj == null || fieldName == null) return null;
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getPrivateStaticInt(Class<?> clazz, String fieldName, int fallback) {
        try {
            if (clazz == null || fieldName == null) return fallback;
            Field f = findField(clazz, fieldName);
            if (f == null) return fallback;
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Nullable
    private static Field findField(Class<?> clazz, String name) {
        try {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeVoid0(Object obj, String methodName, String logOnFail) {
        try {
            if (obj == null) return;
            Method m = findMethod(obj.getClass(), methodName);
            if (m == null) return;
            m.setAccessible(true);
            m.invoke(obj);
        } catch (Throwable ignored) {
            // keep silent; caller logs elsewhere as needed
        }
    }

    private static void invokeVoid1String(Object obj, String methodName, String arg, String logOnFail) {
        try {
            if (obj == null) return;
            Method m = findMethod(obj.getClass(), methodName, String.class);
            if (m == null) return;
            m.setAccessible(true);
            m.invoke(obj, arg);
        } catch (Throwable ignored) {
            // keep silent
        }
    }

    @Nullable
    private static Object invokeObject0(Object obj, String methodName) {
        try {
            if (obj == null) return null;
            Method m = findMethod(obj.getClass(), methodName);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeBoolean0(Object obj, String methodName, boolean fallback) {
        try {
            if (obj == null) return fallback;
            Method m = findMethod(obj.getClass(), methodName);
            if (m == null) return fallback;
            m.setAccessible(true);
            Object res = m.invoke(obj);
            if (res instanceof Boolean) return (Boolean) res;
            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Nullable
    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Class<?> c = clazz;
            while (c != null) {
                try {
                    return c.getDeclaredMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeIsOutOfHomeBounds(RavenEntity ravenEntity, Vec3 pos) {
        try {
            if (ravenEntity == null || pos == null) return false;

            // Prefer public method if you added one (recommended): isOutOfHomeBoundsPublic(Vec3)
            Method pub = findMethod(ravenEntity.getClass(), "isOutOfHomeBoundsPublic", Vec3.class);
            if (pub != null) {
                pub.setAccessible(true);
                Object res = pub.invoke(ravenEntity, pos);
                if (res instanceof Boolean) return (Boolean) res;
            }

            // Otherwise, call private isOutOfHomeBounds(Vec3) via reflection
            Method m = findMethod(ravenEntity.getClass(), "isOutOfHomeBounds", Vec3.class);
            if (m != null) {
                m.setAccessible(true);
                Object res = m.invoke(ravenEntity, pos);
                if (res instanceof Boolean) return (Boolean) res;
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int invokeClampYToHomeBounds(RavenEntity ravenEntity, int y) {
        try {
            if (ravenEntity == null) return y;

            Method pub = findMethod(ravenEntity.getClass(), "clampYToHomeBoundsPublic", int.class);
            if (pub != null) {
                pub.setAccessible(true);
                Object res = pub.invoke(ravenEntity, y);
                if (res instanceof Integer) return (Integer) res;
            }

            Method m = findMethod(ravenEntity.getClass(), "clampYToHomeBounds", int.class);
            if (m != null) {
                m.setAccessible(true);
                Object res = m.invoke(ravenEntity, y);
                if (res instanceof Integer) return (Integer) res;
            }

            return y;
        } catch (Throwable ignored) {
            return y;
        }
    }

    private static Vec3 invokeClampTargetToHomeBounds(RavenEntity ravenEntity, Vec3 v) {
        try {
            if (ravenEntity == null || v == null) return v;

            Method pub = findMethod(ravenEntity.getClass(), "clampTargetToHomeBoundsPublic", Vec3.class);
            if (pub != null) {
                pub.setAccessible(true);
                Object res = pub.invoke(ravenEntity, v);
                if (res instanceof Vec3) return (Vec3) res;
            }

            Method m = findMethod(ravenEntity.getClass(), "clampTargetToHomeBounds", Vec3.class);
            if (m != null) {
                m.setAccessible(true);
                Object res = m.invoke(ravenEntity, v);
                if (res instanceof Vec3) return (Vec3) res;
            }

            return v;
        } catch (Throwable ignored) {
            return v;
        }
    }

    @Nullable
    private static Object getAnyFieldValue(Object obj, String... possibleNames) {
        try {
            if (obj == null || possibleNames == null) return null;
            for (String n : possibleNames) {
                if (n == null) continue;
                Field f = findField(obj.getClass(), n);
                if (f == null) continue;
                f.setAccessible(true);
                return f.get(obj);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setPostTeleportIntentIfPossible(RavenEntity ravenEntity, String enumConstantName) {
        try {
            if (ravenEntity == null || enumConstantName == null) return;

            // Look for nested enum RavenEntity.PostTeleportIntent and field postTeleportIntent
            Class<?>[] inner = ravenEntity.getClass().getDeclaredClasses();
            Class<?> intentEnum = null;
            for (Class<?> c : inner) {
                if (c != null && c.isEnum() && "PostTeleportIntent".equals(c.getSimpleName())) {
                    intentEnum = c;
                    break;
                }
            }
            if (intentEnum == null) return;

            Object desired = null;
            Object[] constants = intentEnum.getEnumConstants();
            if (constants != null) {
                for (Object k : constants) {
                    if (k != null && enumConstantName.equals(String.valueOf(k))) {
                        desired = k;
                        break;
                    }
                }
            }
            if (desired == null) return;

            Field f = findField(ravenEntity.getClass(), "postTeleportIntent");
            if (f == null) return;
            f.setAccessible(true);
            f.set(ravenEntity, desired);

        } catch (Throwable ignored) {
        }
    }
}
