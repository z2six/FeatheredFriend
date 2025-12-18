package net.z2six.featheredfriend.entity.raven;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
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
 * A small, safe wrapper so RavenEntity can play any SoundEvent cleanly,
 * server-side (broadcast) or client-side (local), with debug logs and no crashes.
 */
public final class RavenSoundEngine {
    private static final Logger LOG = LogUtils.getLogger();

    private final RavenEntity raven;

    public RavenSoundEngine(RavenEntity raven) {
        this.raven = raven;
    }

    public void play(SoundEvent sound) {
        play(sound, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    public void play(SoundEvent sound, SoundSource src) {
        play(sound, src, 1.0F, 1.0F);
    }

    public void play(SoundEvent sound, SoundSource src, float volume, float pitch) {
        try {
            if (raven == null) return;
            Level level = raven.level();
            if (level == null) return;
            if (sound == null) return;

            float vol = sanitizeVolume(volume);
            float pit = sanitizePitch(pitch);

            // Server: broadcast to nearby players. Client: local only.
            if (!level.isClientSide) {
                if (level instanceof ServerLevel sl) {
                    BlockPos pos = raven.blockPosition();
                    sl.playSound(
                            null, // null => broadcast
                            pos,
                            sound,
                            src,
                            vol,
                            pit
                    );
                }
            } else {
                // Client-side fallback: play locally at the raven.
                Vec3 p = raven.position();
                level.playLocalSound(p.x, p.y, p.z, sound, src, vol, pit, false);
            }

            if (raven.tickCount % 200 == 0) {
                LOG.debug("[RavenSoundEngine] play sound={} src={} vol={} pitch={} ravenId={} pos={}",
                        safeSoundId(sound), src, vol, pit, raven.getId(), raven.position());
            }

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 200 == 0) {
                LOG.warn("[RavenSoundEngine] play failed safely: {}", t.toString());
            }
        }
    }

    public void playAtPos(SoundEvent sound, SoundSource src, Vec3 pos, float volume, float pitch) {
        try {
            if (raven == null) return;
            Level level = raven.level();
            if (level == null) return;
            if (sound == null) return;
            if (pos == null) return;

            float vol = sanitizeVolume(volume);
            float pit = sanitizePitch(pitch);

            if (!level.isClientSide) {
                if (level instanceof ServerLevel sl) {
                    sl.playSound(null, BlockPos.containing(pos), sound, src, vol, pit);
                }
            } else {
                level.playLocalSound(pos.x, pos.y, pos.z, sound, src, vol, pit, false);
            }

            if (raven.tickCount % 200 == 0) {
                LOG.debug("[RavenSoundEngine] playAtPos sound={} src={} vol={} pitch={} pos={}",
                        safeSoundId(sound), src, vol, pit, pos);
            }

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 200 == 0) {
                LOG.warn("[RavenSoundEngine] playAtPos failed safely: {}", t.toString());
            }
        }
    }

    public void playWithRandomPitch(SoundEvent sound, SoundSource src, float volume, float pitchMin, float pitchMax) {
        try {
            if (raven == null) return;
            if (sound == null) return;

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

            float pitch = lo;
            try {
                RandomSource rnd = raven.getRandom();
                float t = rnd == null ? 0.5F : rnd.nextFloat();
                pitch = lo + (hi - lo) * t;
            } catch (Throwable ignored) {
                pitch = (lo + hi) * 0.5F;
            }

            play(sound, src, vol, pitch);

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 200 == 0) {
                LOG.warn("[RavenSoundEngine] playWithRandomPitch failed safely: {}", t.toString());
            }
        }
    }

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
            // SoundEvent#toString is usually fine; keep safe anyway.
            return String.valueOf(evt);
        } catch (Throwable t) {
            return "SoundEvent<?>"; // last resort
        }
    }
}
