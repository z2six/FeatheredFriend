// forge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/RavenSoundEngine.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * RavenSoundEngine
 *
 * Minimal, centralized, SAFE sound helper that does not depend on any project-local classes.
 *
 * Callers are responsible for deciding *what* to play and *when*.
 * This class is only responsible for:
 *  - actually spawning the sound at a position in a Level
 *  - basic volume/pitch sanitization
 *  - optional random pitch helper
 *  - logging and safe try/catch around playback
 *
 * Supported "which sound" representations:
 *  - SoundEvent: you already have one (vanilla or registered).
 *  - String id: full sounds.json id, e.g. "featheredfriend:raven.air_woosh".
 *
 * Typical usages:
 *
 *  // 1) Using a SoundEvent you already have:
 *  RavenSoundEngine.playAt(
 *      level,
 *      SoundEvents.ENDERMAN_TELEPORT,
 *      SoundSource.NEUTRAL,
 *      someVec,
 *      1.0F,
 *      1.0F
 *  );
 *
 *  // 2) Using a sounds.json id directly:
 *  RavenSoundEngine.playAt(
 *      level,
 *      "featheredfriend:raven.air_woosh",
 *      SoundSource.NEUTRAL,
 *      someVec,
 *      0.6F,
 *      1.05F
 *  );
 */
public final class RavenSoundEngine {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Utility class; no instances.
     */
    private RavenSoundEngine() {
    }

    // -------------------------------------------------------------------------------------------------
    // CORE STATIC HELPERS - SoundEvent
    // -------------------------------------------------------------------------------------------------

    /**
     * Simplest overload for SoundEvent:
     *  - src: SoundSource (e.g. SoundSource.NEUTRAL)
     *  - pos: where to spawn the sound
     *  - volume = 1.0, pitch = 1.0
     */
    public static void playAt(Level level, SoundEvent sound, SoundSource src, Vec3 pos) {
        playAt(level, sound, src, pos, 1.0F, 1.0F);
    }

    /**
     * Full helper when you already have a SoundEvent.
     */
    public static void playAt(Level level,
                              SoundEvent sound,
                              SoundSource src,
                              Vec3 pos,
                              float volume,
                              float pitch) {
        try {
            if (level == null) {
                LOG.warn("[RavenSoundEngine] playAt(SoundEvent): level is null for sound={}", safeSoundId(sound));
                return;
            }
            if (pos == null) {
                LOG.warn("[RavenSoundEngine] playAt(SoundEvent): pos is null for sound={}", safeSoundId(sound));
                return;
            }
            if (sound == null) {
                LOG.warn("[RavenSoundEngine] playAt(SoundEvent): sound is null");
                return;
            }

            float vol = sanitizeVolume(volume);
            float pit = sanitizePitch(pitch);

            doPlay(level, sound, src, pos, vol, pit);

            LOG.debug("[RavenSoundEngine] playAt(SoundEvent) sound={} src={} vol={} pitch={} pos={}",
                    safeSoundId(sound), src, vol, pit, pos);
        } catch (Throwable t) {
            LOG.warn("[RavenSoundEngine] playAt(SoundEvent) failed safely for sound={}: {}",
                    safeSoundId(sound), t.toString());
        }
    }

    /**
     * Overload with raw coordinates for convenience.
     */
    public static void playAt(Level level,
                              SoundEvent sound,
                              SoundSource src,
                              double x,
                              double y,
                              double z,
                              float volume,
                              float pitch) {
        playAt(level, sound, src, new Vec3(x, y, z), volume, pitch);
    }

    // -------------------------------------------------------------------------------------------------
    // CORE STATIC HELPERS - String id (sounds.json key)
    // -------------------------------------------------------------------------------------------------

    /**
     * Simplest overload:
     *  - soundId: full sounds.json id, e.g. "featheredfriend:raven.teleport"
     *  - src: SoundSource (e.g. SoundSource.NEUTRAL)
     *  - pos: where to spawn the sound
     *  - volume = 1.0, pitch = 1.0
     */
    public static void playAt(Level level, String soundId, SoundSource src, Vec3 pos) {
        playAt(level, soundId, src, pos, 1.0F, 1.0F);
    }

    /**
     * Full helper when using sounds.json id strings.
     *
     * NOTE:
     *  We do *all* sound-event creation internally using SoundEvent.createVariableRangeEvent.
     *  No external accessors, no caching, no custom registries.
     */
    public static void playAt(Level level,
                              String soundId,
                              SoundSource src,
                              Vec3 pos,
                              float volume,
                              float pitch) {
        try {
            if (level == null) {
                LOG.warn("[RavenSoundEngine] playAt(String): level is null for soundId='{}'", soundId);
                return;
            }
            if (pos == null) {
                LOG.warn("[RavenSoundEngine] playAt(String): pos is null for soundId='{}'", soundId);
                return;
            }
            if (soundId == null || soundId.isEmpty()) {
                LOG.warn("[RavenSoundEngine] playAt(String): soundId is null/empty");
                return;
            }

            ResourceLocation rl = ResourceLocation.tryParse(soundId);
            if (rl == null) {
                LOG.warn("[RavenSoundEngine] playAt(String): invalid ResourceLocation '{}'", soundId);
                return;
            }

            // Directly create a SoundEvent bound to this id.
            SoundEvent sound = SoundEvent.createVariableRangeEvent(rl);
            if (sound == null) {
                LOG.warn("[RavenSoundEngine] playAt(String): SoundEvent.createVariableRangeEvent returned null for id='{}'", soundId);
                return;
            }

            float vol = sanitizeVolume(volume);
            float pit = sanitizePitch(pitch);

            doPlay(level, sound, src, pos, vol, pit);

            LOG.debug("[RavenSoundEngine] playAt(String) soundId={} src={} vol={} pitch={} pos={}",
                    soundId, src, vol, pit, pos);
        } catch (Throwable t) {
            LOG.warn("[RavenSoundEngine] playAt(String) failed safely for soundId='{}': {}", soundId, t.toString());
        }
    }

    /**
     * Overload for raw coordinates with sounds.json id.
     */
    public static void playAt(Level level,
                              String soundId,
                              SoundSource src,
                              double x,
                              double y,
                              double z,
                              float volume,
                              float pitch) {
        playAt(level, soundId, src, new Vec3(x, y, z), volume, pitch);
    }

    // -------------------------------------------------------------------------------------------------
    // RANDOM PITCH HELPERS
    // -------------------------------------------------------------------------------------------------

    /**
     * Random-pitch helper when you already have a SoundEvent.
     */
    public static void playAtWithRandomPitch(Level level,
                                             SoundEvent sound,
                                             SoundSource src,
                                             Vec3 pos,
                                             float volume,
                                             float pitchMin,
                                             float pitchMax,
                                             RandomSource rnd) {
        try {
            if (sound == null) {
                LOG.warn("[RavenSoundEngine] playAtWithRandomPitch(SoundEvent): sound is null");
                return;
            }

            float vol = sanitizeVolume(volume);

            float lo = pitchMin;
            float hi = pitchMax;
            if (lo > hi) {
                float tmp = lo;
                lo = hi;
                hi = tmp;
            }

            lo = sanitizePitch(lo);
            hi = sanitizePitch(hi);

            float pitch;
            try {
                float t = (rnd != null) ? rnd.nextFloat() : 0.5F;
                pitch = lo + (hi - lo) * t;
            } catch (Throwable ignored) {
                pitch = (lo + hi) * 0.5F;
            }

            playAt(level, sound, src, pos, vol, pitch);

        } catch (Throwable t) {
            LOG.warn("[RavenSoundEngine] playAtWithRandomPitch(SoundEvent) failed safely for sound={}: {}",
                    safeSoundId(sound), t.toString());
        }
    }

    /**
     * Random-pitch helper using a sounds.json id.
     */
    public static void playAtWithRandomPitch(Level level,
                                             String soundId,
                                             SoundSource src,
                                             Vec3 pos,
                                             float volume,
                                             float pitchMin,
                                             float pitchMax,
                                             RandomSource rnd) {
        try {
            if (soundId == null || soundId.isEmpty()) {
                LOG.warn("[RavenSoundEngine] playAtWithRandomPitch(String): soundId is null/empty");
                return;
            }

            float vol = sanitizeVolume(volume);

            float lo = pitchMin;
            float hi = pitchMax;
            if (lo > hi) {
                float tmp = lo;
                lo = hi;
                hi = tmp;
            }

            lo = sanitizePitch(lo);
            hi = sanitizePitch(hi);

            float pitch;
            try {
                float t = (rnd != null) ? rnd.nextFloat() : 0.5F;
                pitch = lo + (hi - lo) * t;
            } catch (Throwable ignored) {
                pitch = (lo + hi) * 0.5F;
            }

            playAt(level, soundId, src, pos, vol, pitch);

        } catch (Throwable t) {
            LOG.warn("[RavenSoundEngine] playAtWithRandomPitch(String) failed safely for soundId='{}': {}", soundId, t.toString());
        }
    }

    // -------------------------------------------------------------------------------------------------
    // INTERNAL PLAYBACK
    // -------------------------------------------------------------------------------------------------

    /**
     * Shared internal implementation to actually push the sound into the world.
     */
    private static void doPlay(Level level,
                               SoundEvent sound,
                               SoundSource src,
                               Vec3 pos,
                               float volume,
                               float pitch) {
        try {
            if (level == null || sound == null || pos == null) {
                return;
            }

            if (!level.isClientSide) {
                if (level instanceof ServerLevel sl) {
                    sl.playSound(
                            null, // null => broadcast to nearby players
                            BlockPos.containing(pos),
                            sound,
                            src,
                            volume,
                            pitch
                    );
                }
            } else {
                level.playLocalSound(pos.x, pos.y, pos.z, sound, src, volume, pitch, false);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenSoundEngine] doPlay failed safely for sound={}: {}",
                    safeSoundId(sound), t.toString());
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Sanitization + debug helpers
    // -------------------------------------------------------------------------------------------------

    private static float sanitizeVolume(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1.0F;
        return Mth.clamp(v, 0.0F, 4.0F);
    }

    private static float sanitizePitch(float p) {
        if (Float.isNaN(p) || Float.isInfinite(p)) return 1.0F;
        return Mth.clamp(p, 0.25F, 2.0F);
    }

    private static String safeSoundId(SoundEvent evt) {
        try {
            return String.valueOf(evt);
        } catch (Throwable t) {
            return "SoundEvent<?>"; // last resort
        }
    }
}
