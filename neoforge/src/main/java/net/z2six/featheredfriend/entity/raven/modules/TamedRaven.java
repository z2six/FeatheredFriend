// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/TamedRaven.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.registry.FFNeoForgeParticles;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Handles post-taming behavior for Ravens:
 *  - Trigger naming GUI when the golden nugget cost is fully paid.
 *  - Receive the chosen name from the client.
 *  - Store a "bound raven" record tied to the player (for later summoning).
 *  - Play a partial teleport FX + fade-out and then despawn the entity.
 *
 * This module is intentionally small and focused on tamed-raven lifecycle.
 * All nugget-counting / lure-follow logic remains in LureFollowTame.
 */
public final class TamedRaven {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int MAX_NAME_CHARS = 26;

    /**
     * How long the fade-out lasts once we start despawn FX.
     *  - 10 ticks = 0.5s at 20 TPS.
     *  - Uses same alpha range (0..255) as Teleportation logic.
     */
    private static final int DESPAWN_FADE_TICKS = 10;

    private final RavenEntity raven;

    // Guard against double-taming / double-GUI
    private boolean tamingCompleted = false;
    private boolean namingGuiRequested = false;

    @Nullable
    private UUID pendingOwnerUuid = null;

    // Despawn-with-FX state (server only)
    private boolean despawnWithFxActive = false;
    private int despawnWithFxTicks = 0;

    public TamedRaven(RavenEntity raven) {
        this.raven = raven;
    }

    /**
     * Called by LureFollowTame the moment the taming gold-nugget cost
     * has been fully paid (remaining less than 0) by the current lure player.
     *
     * This only does anything on the SERVER side. The client can log, but
     * the GUI open request is driven from server → client via FFNetwork.
     */
    public void onTamingFullyPaid(Player player) {
        try {
            if (player == null) return;
            if (raven == null) return;
            if (raven.level() == null) return;

            boolean isClientSide = raven.level().isClientSide;

            if (!(player instanceof ServerPlayer serverPlayer)) {
                if (isClientSide && raven.tickCount % 40 == 0) {
                    LOG.debug("[TamedRaven] onTamingFullyPaid: non-ServerPlayer on client; ignoring. player={}",
                            player.getName().getString());
                }
                return;
            }

            if (tamingCompleted || namingGuiRequested) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[TamedRaven] onTamingFullyPaid: already handled (tamingCompleted={} namingGuiRequested={}) id={} pos={}",
                            tamingCompleted, namingGuiRequested, raven.getId(), raven.position());
                }
                return;
            }

            if (isClientSide) {
                // We only *drive* the GUI from the server; client-side calls are ignored.
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[TamedRaven] onTamingFullyPaid: CLIENT side call ignored. player={} id={} pos={}",
                            serverPlayer.getGameProfile().getName(), raven.getId(), raven.position());
                }
                return;
            }

            // Server: mark and send GUI-open packet.
            this.namingGuiRequested = true;
            this.pendingOwnerUuid = serverPlayer.getUUID();

            LOG.debug("[TamedRaven] onTamingFullyPaid: triggering naming GUI for player={} ravenId={} pos={}",
                    serverPlayer.getGameProfile().getName(), raven.getId(), raven.position());

            // S2C: open naming screen for this raven entity.
            net.z2six.featheredfriend.network.FFNetwork.sendOpenRavenNamingScreen(serverPlayer, raven.getId());

        } catch (Throwable t) {
            LOG.error("[TamedRaven] onTamingFullyPaid failed safely", t);
        }
    }

    /**
     * Called by RavenNameChosenPacket on SERVER when the client has submitted
     * a raven name via the naming GUI.
     */
    public void onNameChosenFromClient(ServerPlayer player, String rawName) {
        try {
            if (player == null) return;
            if (raven == null) return;
            if (raven.level() == null) return;
            if (!(raven.level() instanceof ServerLevel serverLevel)) return;

            if (pendingOwnerUuid == null || !pendingOwnerUuid.equals(player.getUUID())) {
                if (raven.tickCount % 40 == 0) {
                    LOG.warn("[TamedRaven] onNameChosenFromClient: owner mismatch or no pending owner. expected={} got={}",
                            pendingOwnerUuid, player.getUUID());
                }
                return;
            }

            // Sanitize name
            String name = sanitizeName(rawName, player);
            this.tamingCompleted = true;
            this.namingGuiRequested = false;

            LOG.debug("[TamedRaven] onNameChosenFromClient: name='{}' player={} ravenId={} pos={}",
                    name, player.getGameProfile().getName(), raven.getId(), raven.position());

            // Store bound raven data tied to player (for later summoning).
            storeTamedRavenForPlayer(player, name);

            // Play FX + fade-out, then despawn via tickServer().
            beginDespawnWithFx(serverLevel, player, name);

        } catch (Throwable t) {
            LOG.error("[TamedRaven] onNameChosenFromClient failed safely", t);
        }
    }

    /**
     * Called when the player cancels the naming GUI.
     * Despawns the raven with the usual FX, without storing any tamed data.
     */
    public void onNamingCancelled(ServerPlayer player) {
        try {
            if (player == null) return;
            if (raven == null) return;
            if (raven.level() == null) return;
            if (!(raven.level() instanceof ServerLevel serverLevel)) return;

            if (pendingOwnerUuid == null || !pendingOwnerUuid.equals(player.getUUID())) {
                if (raven.tickCount % 40 == 0) {
                    LOG.warn("[TamedRaven] onNamingCancelled: owner mismatch or no pending owner. expected={} got={}",
                            pendingOwnerUuid, player.getUUID());
                }
                return;
            }

            this.namingGuiRequested = false;
            this.tamingCompleted = false;
            this.pendingOwnerUuid = null;

            String name = buildDefaultName(player);
            beginDespawnWithFx(serverLevel, player, name);

        } catch (Throwable t) {
            LOG.error("[TamedRaven] onNamingCancelled failed safely", t);
        }
    }

    /**
     * Server-side tick: drives the "fade out and then despawn" sequence
     * after naming is completed or when scroll-despawn requests FX.
     *
     * Must be called from RavenEntity's server tick/AI step:
     *
     *   TamedRaven tamed = this.getTamedRavenModule();
     *   if (tamed != null) tamed.tickServer();
     */
    public void tickServer() {
        try {
            if (!despawnWithFxActive) return;
            if (raven == null) return;
            if (raven.level() == null) return;
            if (!(raven.level() instanceof ServerLevel)) return;
            if (!raven.isAlive()) {
                despawnWithFxActive = false;
                return;
            }

            despawnWithFxTicks++;
            int total = Math.max(1, DESPAWN_FADE_TICKS);

            float k = Mth.clamp((float) despawnWithFxTicks / (float) total, 0.0F, 1.0F);
            int alpha = (int) Mth.lerp(k, 255.0F, 0.0F);

            try {
                Teleportation tp = raven.getTeleportation();
                if (tp != null) {
                    tp.setTeleportFadeAlpha(alpha, raven);
                }
            } catch (Throwable t) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn("[TamedRaven] tickServer: setTeleportFadeAlpha failed safely: {}", t.toString());
                }
            }

            if (despawnWithFxTicks >= total) {
                Vec3 pos = raven.position();
                LOG.debug("[TamedRaven] tickServer: despawn complete after fade; removing raven id={} pos={}",
                        raven.getId(), pos);
                raven.discard();
                despawnWithFxActive = false;
            }

        } catch (Throwable t) {
            LOG.error("[TamedRaven] tickServer failed safely", t);
            despawnWithFxActive = false;
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private String sanitizeName(@Nullable String raw, Player owner) {
        try {
            String s = (raw == null) ? "" : raw.trim();
            if (s.isEmpty()) {
                s = buildDefaultName(owner);
            }

            if (s.length() > MAX_NAME_CHARS) {
                s = s.substring(0, MAX_NAME_CHARS);
            }

            return s;
        } catch (Throwable t) {
            LOG.warn("[TamedRaven] sanitizeName failed; falling back to default. err={}", t.toString());
            return buildDefaultName(owner);
        }
    }

    private String buildDefaultName(Player owner) {
        try {
            String base = owner.getName().getString();
            if (base == null || base.isEmpty()) base = "Raven";

            String candidate = base + "'s Raven";
            if (candidate.length() > MAX_NAME_CHARS) {
                candidate = candidate.substring(0, MAX_NAME_CHARS);
            }
            return candidate;
        } catch (Throwable t) {
            LOG.warn("[TamedRaven] buildDefaultName failed; using 'Raven' as fallback: {}", t.toString());
            return "Raven";
        }
    }

    /**
     * Store a simple "bound raven" record in the player's persistent data.
     * For step 1 we only store:
     *  - Owner UUID
     *  - Raven name
     *  - A basic marker that the player has a tamed raven.
     *
     * We can extend this later with variant, home info, cosmetics, etc.
     */
    private void storeTamedRavenForPlayer(ServerPlayer player, String ravenName) {
        try {
            UUID boundId = null;
            try {
                boundId = (raven != null) ? raven.getUUID() : null;
            } catch (Throwable ignored) {
            }

            TamedRavenPlayerData.storeTamedRavenInfo(player, ravenName, boundId);

        } catch (Throwable t) {
            LOG.error("[TamedRaven] storeTamedRavenForPlayer failed safely", t);
        }
    }

    /**
     * Start the despawn FX + fade-out.
     *  - Spawns the Enderpop burst (server-side).
     *  - Spawns FEATHER particles (server-side); client renders them via FeatherParticles.
     *  - Sets fade alpha to fully visible (255).
     *  - Arms the short fade sequence; actual fade/despawn is driven by tickServer().
     *
     * This is used both when:
     *  - The initial tame/name completes, and
     *  - A scroll-summoned raven should be dismissed with the same FX.
     */
    public void beginDespawnWithFx(ServerLevel serverLevel, ServerPlayer owner, String name) {
        try {
            if (serverLevel == null) return;
            if (raven == null) return;

            Vec3 pos = raven.position();
            double x = pos.x();
            double y = pos.y() + 0.6D;
            double z = pos.z();

            long seed = raven.getUUID().getLeastSignificantBits()
                    ^ (long) raven.tickCount
                    ^ owner.getUUID().getMostSignificantBits()
                    ^ name.hashCode();

            // Server-side Enderpop burst (already visible to nearby players)
            Teleportation tp = raven.getTeleportation();
            if (tp != null) {
                tp.spawnEnderpopBurst(
                        serverLevel,
                        x, y, z,
                        seed,
                        "tamed_raven_store",
                        raven
                );
                // Start fully visible; tickServer() will drive 255 → 0.
                tp.setTeleportFadeAlpha(255, raven);
            }

            // FEATHER burst: count is controlled centrally in FeatherParticles.
            try {
                int count = FeatherParticles.getFeathersPerBurst();
                serverLevel.sendParticles(
                        FFNeoForgeParticles.FEATHER.get(),
                        x, y, z,
                        count,
                        0.4D, 0.25D, 0.4D, // spread
                        0.0D               // speed; motion variance handled by vanilla + provider
                );
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[TamedRaven] beginDespawnWithFx: spawned FEATHER particles at {} count={}", pos, count);
                }
            } catch (Throwable tFeathers) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn("[TamedRaven] beginDespawnWithFx: FEATHER particle spawn failed safely: {}",
                            tFeathers.toString());
                }
            }

            this.despawnWithFxActive = true;
            this.despawnWithFxTicks = 0;

            LOG.debug("[TamedRaven] beginDespawnWithFx: owner={} name='{}' id={} pos={}",
                    owner.getGameProfile().getName(), name, raven.getId(), pos);

        } catch (Throwable t) {
            LOG.error("[TamedRaven] beginDespawnWithFx failed safely", t);
            this.despawnWithFxActive = false;
        }
    }
}
