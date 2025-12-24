// neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.entity.raven.RavenVariant;
import net.z2six.featheredfriend.entity.raven.modules.RavenSoundEngine;
import net.z2six.featheredfriend.entity.raven.modules.TamedRaven;
import net.z2six.featheredfriend.entity.raven.modules.Teleportation;
import net.z2six.featheredfriend.registry.FFNeoForgeEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/RavenCourierRuntime.java
 *
 * Server-side runtime for raven courier deliveries.
 *
 * Responsibilities:
 *  - Periodically scan RavenCourierData for pending delivery jobs.
 *  - For online recipients, spawn a "courier raven" that visually carries the scroll
 *    and follows the recipient.
 *  - Enforce concurrency constraints:
 *      * Multiple deliveries can be in-flight at once.
 *      * But never two jobs simultaneously FROM the same sender, nor TO the same recipient.
 *  - Handle interaction with courier ravens:
 *      * Any player can RMB the courier raven to retrieve its Sealed Scroll.
 *      * The scroll is reconstructed with the exact SealedScroll NBT payload from the job.
 *      * If inventory is full, the scroll is dropped at the player's feet.
 *  - Handle courier raven death:
 *      * The scroll is dropped at the raven's position.
 *      * The sender is notified (if online): "Your raven, <name>, has perished..."
 *      * The job is removed from RavenCourierData so the sender is free to send again.
 *
 * Implementation notes:
 *  - We do NOT reuse the scroll-summoned raven tag ("ff_scroll_summoned").
 *    Courier ravens use their own scoreboard tag + NBT fields:
 *      * Scoreboard tag: "ff_courier_raven"
 *      * NBT (under Constants.MOD_ID):
 *          - CourierActive : boolean
 *          - CourierJobId  : long
 *          - CourierSenderUUID    : UUID
 *          - CourierRecipientUUID : UUID
 *  - Job.inFlight is treated as a runtime-only flag:
 *      * It is saved to NBT for debugging/visibility.
 *      * It is reset to false on world load (see RavenCourierData.readFromNbt).
 */
public final class RavenCourierRuntime {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Scoreboard tag used to mark courier ravens.
     */
    private static final String TAG_COURIER_RAVEN = "ff_courier_raven";

    /**
     * Registry name of the sealed scroll item.
     * (Same as in TamedRavenScrollWatcher / RavenCourierData.)
     */
    private static final ResourceLocation SEALED_SCROLL_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "scroll_sealed");

    /**
     * How often (in server ticks) we attempt to dispatch new courier ravens.
     * 20 ticks = once per second.
     */
    private static final int DISPATCH_INTERVAL_TICKS = 20;

    /**
     * Hard cap on how many new courier ravens we will spawn in a single dispatch pass.
     * This still allows multiple players to get deliveries in parallel but prevents
     * pathological "explosions" if there is a large backlog.
     */
    private static final int MAX_NEW_DISPATCHES_PER_PASS = 4;

    /**
     * Simple server tick counter for throttling the dispatch logic.
     */
    private static int serverTickCounter = 0;

    private RavenCourierRuntime() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Public registration
    // ---------------------------------------------------------------------

    /**
     * Called from FeatheredFriend main class:
     *
     *   RavenCourierRuntime.register();
     *
     * This wires up:
     *  - ServerTickEvent.Post for periodic dispatch of courier ravens.
     *  - PlayerInteractEvent.EntityInteract for RMB retrieval of scrolls.
     *  - LivingDeathEvent for handling courier raven death.
     */
    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onServerTick);
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onEntityInteract);
            NeoForge.EVENT_BUS.addListener(RavenCourierRuntime::onRavenDeath);
            LOG.info("[RavenCourierRuntime] Registered server tick + interaction + death listeners");
        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] Failed to register event listeners", t);
        }
    }

    // ---------------------------------------------------------------------
    // Server tick: periodic dispatch
    // ---------------------------------------------------------------------

    private static void onServerTick(@NotNull ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }

            serverTickCounter++;
            if ((serverTickCounter % DISPATCH_INTERVAL_TICKS) != 0) {
                // Throttle to once per DISPATCH_INTERVAL_TICKS.
                return;
            }

            dispatchPendingDeliveries(server);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onServerTick failed safely", t);
        }
    }

    /**
     * Core dispatch logic:
     *  - Reads all pending jobs from RavenCourierData.
     *  - Builds concurrency sets (senders/recipients already in-flight).
     *  - For jobs whose recipient is online and not currently busy as sender/recipient,
     *    spawns a courier raven carrying that job's scroll.
     */
    private static void dispatchPendingDeliveries(@NotNull MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOG.warn("[RavenCourierRuntime] dispatchPendingDeliveries: overworld is null; aborting dispatch.");
                return;
            }

            RavenCourierData data = RavenCourierData.get(overworld);
            List<RavenCourierData.DeliveryJob> allJobs = data.getAllJobsFlat();
            if (allJobs.isEmpty()) {
                return;
            }

            // Build sets of "busy" senders and recipients based on jobs already marked inFlight.
            Set<UUID> busySenders = new HashSet<>();
            Set<UUID> busyRecipients = new HashSet<>();

            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }
                if (!job.inFlight) {
                    continue;
                }
                if (job.senderUuid != null) {
                    busySenders.add(job.senderUuid);
                }
                if (job.recipientUuid != null) {
                    busyRecipients.add(job.recipientUuid);
                }
            }

            int dispatchedCount = 0;

            for (RavenCourierData.DeliveryJob job : allJobs) {
                if (job == null) {
                    continue;
                }

                // Already in-flight in this server session -> skip.
                if (job.inFlight) {
                    continue;
                }

                UUID senderId = job.senderUuid;
                UUID recipientId = job.recipientUuid;
                if (recipientId == null) {
                    continue;
                }

                // Apply concurrency constraints:
                //  - Never spawn a second delivery FROM the same sender concurrently.
                //  - Never spawn a second delivery TO the same recipient concurrently.
                if ((senderId != null && busySenders.contains(senderId))
                        || busyRecipients.contains(recipientId)) {
                    continue;
                }

                // Recipient must be online for the courier to spawn.
                ServerPlayer recipient = server.getPlayerList().getPlayer(recipientId);
                if (recipient == null) {
                    continue;
                }

                ServerLevel targetLevel = recipient.serverLevel();
                if (targetLevel == null || targetLevel.isClientSide()) {
                    continue;
                }

                RavenEntity raven = spawnCourierRavenForJob(targetLevel, recipient, job);
                if (raven == null) {
                    continue;
                }

                // Mark job as in-flight for the current server session.
                job.inFlight = true;
                data.setDirty();

                if (senderId != null) {
                    busySenders.add(senderId);
                }
                busyRecipients.add(recipientId);
                dispatchedCount++;

                if (dispatchedCount >= MAX_NEW_DISPATCHES_PER_PASS) {
                    break;
                }
            }

            if (dispatchedCount > 0 && LOG.isInfoEnabled()) {
                LOG.info("[RavenCourierRuntime] Dispatched {} courier raven(s) this tick.", dispatchedCount);
            }

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dispatchPendingDeliveries failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven spawn helpers
    // ---------------------------------------------------------------------

    @Nullable
    private static RavenEntity spawnCourierRavenForJob(@NotNull ServerLevel level,
                                                       @NotNull ServerPlayer recipient,
                                                       @NotNull RavenCourierData.DeliveryJob job) {
        try {
            RavenEntity raven = FFNeoForgeEntities.RAVEN.get().create(level);
            if (raven == null) {
                LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob: entity factory returned null for jobId={}", job.jobId);
                return null;
            }

            // Spawn position:  same logic style as TamedRavenScrollWatcher.findSafeSpawnAbovePlayer(...)
            Vec3 spawnPos = findSafeSpawnAbovePlayer(level, recipient);
            if (spawnPos != null) {
                raven.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, recipient.getYRot(), 0.0F);
            } else {
                // Fallback: slightly in front of the player's face.
                Vec3 playerPos = recipient.position();
                Vec3 look = recipient.getLookAngle();
                double lx = look.x;
                double lz = look.z;
                double len = Math.sqrt(lx * lx + lz * lz);
                if (len < 1.0E-4D) {
                    lx = 1.0D;
                    lz = 0.0D;
                    len = 1.0D;
                }
                lx /= len;
                lz /= len;

                double distance = 1.25D;
                double sx = playerPos.x + lx * distance;
                double sz = playerPos.z + lz * distance;
                double sy = recipient.getEyeY() + 0.1D;

                Vec3 fallback = new Vec3(sx, sy, sz);
                raven.moveTo(fallback.x, fallback.y, fallback.z, recipient.getYRot(), 0.0F);

                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: using fallback spawn={} for recipient='{}' jobId={}",
                        fallback, safePlayerName(recipient), job.jobId);
            }

            // Tame and bind to the RECIPIENT so the raven will follow them using standard AI.
            try {
                raven.setTame(true, true); // 1.21 style: setTame(boolean tame, boolean broadcastEvent)
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setTame(true, true) failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }
            try {
                raven.setOwnerUUID(job.recipientUuid);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setOwnerUUID failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            // Visual: this raven is clearly a scroll courier.
            try {
                raven.setRavenVariant(RavenVariant.SCROLL);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] spawnCourierRavenForJob: setRavenVariant(SCROLL) failed safely for jobId={}: {}",
                        job.jobId, t.toString());
            }

            // Name: use sender name if available, otherwise generic.
            String ravenName = job.senderName != null && !job.senderName.isEmpty()
                    ? job.senderName + "'s Raven"
                    : "Courier Raven";
            ensureRavenName(raven, ravenName);

            // Mark as a courier raven via persistent data + scoreboard tag.
            attachCourierJobToRaven(raven, job);

            // Actually add to the world.
            level.addFreshEntity(raven);

            // Spawn FX: portal + teleport + whistle (same style as scroll-summoned spawn).
            playCourierSpawnFx(level, recipient, raven);

            LOG.info(
                    "[RavenCourierRuntime] spawnCourierRavenForJob: spawned courier raven id={} for jobId={} sender='{}' -> recipient='{}' at {}",
                    raven.getId(),
                    job.jobId,
                    job.senderName,
                    job.recipientName,
                    raven.position()
            );

            return raven;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] spawnCourierRavenForJob failed safely for jobId={}", job.jobId, t);
            return null;
        }
    }

    /**
     * Attach courier job metadata to the raven (NBT + scoreboard tag).
     */
    private static void attachCourierJobToRaven(@NotNull RavenEntity raven,
                                                @NotNull RavenCourierData.DeliveryJob job) {
        try {
            CompoundTag root = raven.getPersistentData();
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);

            ffTag.putBoolean("CourierActive", true);
            ffTag.putLong("CourierJobId", job.jobId);

            if (job.senderUuid != null) {
                ffTag.putUUID("CourierSenderUUID", job.senderUuid);
            }
            if (job.recipientUuid != null) {
                ffTag.putUUID("CourierRecipientUUID", job.recipientUuid);
            }

            root.put(Constants.MOD_ID, ffTag);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] attachCourierJobToRaven: NBT write failed safely for raven id={}: {}",
                    raven.getId(), t.toString());
        }

        try {
            raven.addTag(TAG_COURIER_RAVEN);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] attachCourierJobToRaven: addTag({}) failed safely for raven id={}: {}",
                    TAG_COURIER_RAVEN, raven.getId(), t.toString());
        }
    }

    /**
     * Portal + teleport sound + whistle.
     */
    private static void playCourierSpawnFx(@NotNull ServerLevel level,
                                           @NotNull ServerPlayer recipient,
                                           @NotNull RavenEntity raven) {
        try {
            Vec3 ravenPos = raven.position();
            Vec3 playerPos = recipient.position();

            // Portal particles around the raven
            level.sendParticles(
                    ParticleTypes.PORTAL,
                    ravenPos.x,
                    ravenPos.y + 0.4D,
                    ravenPos.z,
                    40,
                    0.3D,
                    0.4D,
                    0.3D,
                    0.02D
            );

            // Enderman-style teleport sound at the raven
            level.playSound(
                    null,
                    ravenPos.x,
                    ravenPos.y,
                    ravenPos.z,
                    SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.NEUTRAL,
                    1.0F,
                    1.0F + (level.random.nextFloat() - 0.5F) * 0.2F
            );

            // Player whistle at the recipient's position (so it feels like they are "receiving" a raven).
            RavenSoundEngine.playAt(
                    level,
                    "featheredfriend:raven.whistle",
                    SoundSource.NEUTRAL,
                    playerPos,
                    0.35F,
                    1.0F
            );

        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] playCourierSpawnFx failed safely: {}", t.toString());
        }
    }

    @Nullable
    private static Vec3 findSafeSpawnAbovePlayer(@NotNull ServerLevel level, @NotNull ServerPlayer player) {
        try {
            Vec3 playerPos = player.position();
            Vec3 look = player.getLookAngle();

            double lx = look.x;
            double lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                lx = 1.0D;
                lz = 0.0D;
                len = 1.0D;
            }
            lx /= len;
            lz /= len;

            // Horizontal offset in front of the player.
            final double horizontalDistance = 1.25D;
            double baseX = playerPos.x + lx * horizontalDistance;
            double baseZ = playerPos.z + lz * horizontalDistance;

            // Preferred vertical offset (~15 blocks above).
            final int preferredOffsetY = 15;
            final int scanUp = 8;
            final int scanDown = 8;

            int baseY = Mth.floor(player.getY() + preferredOffsetY + 0.5D);

            int minY = level.getMinBuildHeight() + 2;
            int maxY = level.getMaxBuildHeight() - 2;

            baseY = Mth.clamp(baseY, minY, maxY);

            int cx = Mth.floor(baseX + 0.5D);
            int cz = Mth.floor(baseZ + 0.5D);

            int maxDelta = Math.max(scanUp, scanDown);

            for (int dy = 0; dy <= maxDelta; dy++) {
                int[] candidates = (dy == 0)
                        ? new int[]{baseY}
                        : new int[]{baseY + dy, baseY - dy};

                for (int y : candidates) {
                    if (y < minY || y > maxY) {
                        continue;
                    }

                    if (is3x3x2Air(level, cx, y, cz)) {
                        double sy = y + 0.1D;
                        Vec3 spawn = new Vec3(cx + 0.5D, sy, cz + 0.5D);

                        LOG.info("[RavenCourierRuntime] findSafeSpawnAbovePlayer: chosen spawn={} for player='{}' (baseY={}, dy={})",
                                spawn, safePlayerName(player), baseY, dy);

                        return spawn;
                    }
                }
            }

            LOG.warn("[RavenCourierRuntime] findSafeSpawnAbovePlayer: no 3x3x2 air column found near player='{}' (baseY={})",
                    safePlayerName(player), baseY);

            return null;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] findSafeSpawnAbovePlayer failed safely", t);
            return null;
        }
    }

    private static boolean is3x3x2Air(@NotNull ServerLevel level, int cx, int cy, int cz) {
        try {
            for (int dy = 0; dy <= 1; dy++) {
                int y = cy + dy;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                        if (!level.isEmptyBlock(pos)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] is3x3x2Air failed safely: {}", t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Player RMB retrieval (courier raven -> sealed scroll)
    // ---------------------------------------------------------------------

    private static void onEntityInteract(@NotNull PlayerInteractEvent.EntityInteract event) {
        try {
            Level level = event.getLevel();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            Entity target = event.getTarget();
            if (!(target instanceof RavenEntity raven)) {
                return;
            }

            Player player = event.getEntity();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            // Only care about ravens that are marked as courier ravens.
            if (!isCourierRaven(raven)) {
                return;
            }

            // Resolve jobId from the raven's NBT.
            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: courier raven id={} has no valid CourierJobId; ignoring.",
                        raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onEntityInteract: no job found for CourierJobId={} (raven id={})",
                        jobId, raven.getId());
                // Clean up stray raven by despawning it without touching jobs.
                despawnCourierRaven(serverLevel, serverPlayer, raven, "orphaned courier (no job)");
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Give the scroll to whoever clicked (could be recipient or a "thief").
            boolean delivered = giveSealedScrollToPlayerFromJob(serverLevel, serverPlayer, job);
            if (!delivered) {
                // If we somehow failed to hand out or drop the scroll, don't clear the job yet.
                event.setCancellationResult(InteractionResult.PASS);
                return;
            }

            // Remove the job; delivery is complete.
            data.removeJob(job.jobId, job.recipientUuid);

            // Despawn courier raven with FX.
            despawnCourierRaven(serverLevel, serverPlayer, raven, "delivery complete: scroll retrieved");

            // Consume the interaction.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onEntityInteract failed safely", t);
        }
    }

    private static boolean isCourierRaven(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                return true;
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: tag check failed for id={}: {}",
                    raven.getId(), t.toString());
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return false;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            return ffTag != null && ffTag.getBoolean("CourierActive");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] isCourierRaven: NBT check failed for id={}: {}",
                    raven.getId(), t.toString());
            return false;
        }
    }

    private static long getCourierJobIdFromRaven(@NotNull RavenEntity raven) {
        try {
            CompoundTag root = raven.getPersistentData();
            if (root == null) {
                return -1L;
            }
            CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
            if (ffTag == null || !ffTag.contains("CourierJobId", Tag.TAG_LONG)) {
                return -1L;
            }
            return ffTag.getLong("CourierJobId");
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] getCourierJobIdFromRaven failed safely for id={}: {}",
                    raven.getId(), t.toString());
            return -1L;
        }
    }

    /**
     * Constructs a new Sealed Scroll ItemStack from the job's SealedScroll NBT,
     * adds it to the player's inventory, and drops it if the inventory is full.
     */
    private static boolean giveSealedScrollToPlayerFromJob(@NotNull ServerLevel level,
                                                           @NotNull ServerPlayer player,
                                                           @NotNull RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: sealed scroll item not found in registry (id={})",
                        SEALED_SCROLL_ID);
                return false;
            }

            ItemStack stack = new ItemStack(sealedScrollItem);
            CompoundTag sealedCopy = job.sealedScrollNbt == null ? new CompoundTag() : job.sealedScrollNbt.copy();

            // Reconstruct the exact CustomData payload:
            // CustomData root: { SealedScroll: <sealedCopy> }
            CompoundTag customRoot = new CompoundTag();
            customRoot.put("SealedScroll", sealedCopy);
            CustomData customData = CustomData.of(customRoot);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, customData);

            boolean added = false;
            try {
                added = player.getInventory().add(stack);
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: add to inventory failed safely for player='{}': {}",
                        safePlayerName(player), t.toString());
            }

            if (!added || !stack.isEmpty()) {
                // Either inventory was full or add() left a remainder; drop it.
                try {
                    player.drop(stack, false);
                } catch (Throwable t) {
                    LOG.warn("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: drop failed safely for player='{}': {}",
                            safePlayerName(player), t.toString());
                }
            }

            // Notify the player.
            String senderName = job.senderName == null ? "" : job.senderName;
            String recipientName = job.recipientName == null ? "" : job.recipientName;

            Component msg = Component.literal(
                    "[FeatheredFriend] You retrieved a sealed scroll"
                            + (senderName.isEmpty() ? "" : " from " + senderName)
                            + (recipientName.isEmpty() ? "" : " addressed to " + recipientName)
                            + "."
            );
            player.sendSystemMessage(msg);

            LOG.info("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob: delivered sealed scroll for jobId={} to player='{}'",
                    job.jobId, safePlayerName(player));

            return true;

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] giveSealedScrollToPlayerFromJob failed safely for jobId={}", job.jobId, t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Courier raven death handling
    // ---------------------------------------------------------------------

    private static void onRavenDeath(@NotNull LivingDeathEvent event) {
        try {
            LivingEntity living = event.getEntity();
            if (!(living instanceof RavenEntity raven)) {
                return;
            }

            Level level = raven.level();
            if (!(level instanceof ServerLevel serverLevel) || level.isClientSide()) {
                return;
            }

            if (!isCourierRaven(raven)) {
                return;
            }

            long jobId = getCourierJobIdFromRaven(raven);
            if (jobId <= 0L) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: courier raven id={} has no valid CourierJobId; dropping nothing.",
                        raven.getId());
                return;
            }

            RavenCourierData data = RavenCourierData.get(serverLevel);
            RavenCourierData.DeliveryJob job = data.getJobById(jobId);
            if (job == null) {
                LOG.warn("[RavenCourierRuntime] onRavenDeath: no job found for CourierJobId={} (raven id={})",
                        jobId, raven.getId());
                // No job to clean up; just fall through and let it die.
                return;
            }

            // Drop the scroll at the raven's position.
            dropSealedScrollAtRaven(serverLevel, raven, job);

            // Notify the sender if online.
            notifySenderOfRavenDeath(serverLevel, raven, job);

            // Remove the job so the sender is free again.
            data.removeJob(job.jobId, job.recipientUuid);

            // Clean up courier flags (not strictly necessary, entity is dying).
            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] onRavenDeath failed safely", t);
        }
    }

    private static void dropSealedScrollAtRaven(@NotNull ServerLevel level,
                                                @NotNull RavenEntity raven,
                                                @NotNull RavenCourierData.DeliveryJob job) {
        try {
            Item sealedScrollItem = BuiltInRegistries.ITEM.get(SEALED_SCROLL_ID);
            if (sealedScrollItem == null) {
                LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven: sealed scroll item not found in registry (id={})",
                        SEALED_SCROLL_ID);
                return;
            }

            ItemStack stack = new ItemStack(sealedScrollItem);
            CompoundTag sealedCopy = job.sealedScrollNbt == null ? new CompoundTag() : job.sealedScrollNbt.copy();

            CompoundTag customRoot = new CompoundTag();
            customRoot.put("SealedScroll", sealedCopy);
            CustomData customData = CustomData.of(customRoot);
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, customData);

            raven.spawnAtLocation(stack, 0.2F);

            LOG.info("[RavenCourierRuntime] dropSealedScrollAtRaven: dropped sealed scroll for jobId={} at raven death pos={}",
                    job.jobId, raven.position());

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] dropSealedScrollAtRaven failed safely for jobId={}", job.jobId, t);
        }
    }

    private static void notifySenderOfRavenDeath(@NotNull ServerLevel anyLevel,
                                                 @NotNull RavenEntity raven,
                                                 @NotNull RavenCourierData.DeliveryJob job) {
        try {
            MinecraftServer server = anyLevel.getServer();
            if (server == null) {
                return;
            }

            if (job.senderUuid == null) {
                return;
            }

            ServerPlayer sender = server.getPlayerList().getPlayer(job.senderUuid);
            if (sender == null) {
                // Sender offline -> no message for now.
                return;
            }

            String ravenName = "<your raven>";
            try {
                if (raven.getCustomName() != null) {
                    String n = raven.getCustomName().getString();
                    if (n != null && !n.isEmpty()) {
                        ravenName = n;
                    }
                }
            } catch (Throwable ignored) {
            }

            String recipientPart = (job.recipientName == null || job.recipientName.isEmpty())
                    ? ""
                    : " while delivering a scroll to " + job.recipientName;

            Component msg = Component.literal(
                    "[FeatheredFriend] Your raven, " + ravenName + ", has perished"
                            + recipientPart
                            + ". The scroll was dropped where it fell."
            );
            sender.sendSystemMessage(msg);

            LOG.info("[RavenCourierRuntime] notifySenderOfRavenDeath: notified sender='{}' of raven death (jobId={})",
                    safePlayerName(sender), job.jobId);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] notifySenderOfRavenDeath failed safely for jobId={}", job.jobId, t);
        }
    }

    private static void clearCourierFlags(@NotNull RavenEntity raven) {
        try {
            if (raven.getTags().contains(TAG_COURIER_RAVEN)) {
                raven.removeTag(TAG_COURIER_RAVEN);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] clearCourierFlags: removeTag({}) failed safely for id={}: {}",
                    TAG_COURIER_RAVEN, raven.getId(), t.toString());
        }

        try {
            CompoundTag root = raven.getPersistentData();
            if (root != null) {
                CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                if (ffTag != null && !ffTag.isEmpty()) {
                    ffTag.remove("CourierActive");
                    ffTag.remove("CourierJobId");
                    ffTag.remove("CourierSenderUUID");
                    ffTag.remove("CourierRecipientUUID");
                    root.put(Constants.MOD_ID, ffTag);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] clearCourierFlags: NBT cleanup failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    /**
     * Despawn ONE courier raven via TamedRaven.beginDespawnWithFx,
     * falling back to discard() if anything goes wrong.
     *
     * This is the courier analogue of TamedRavenScrollWatcher.despawnOneScrollSummonedRaven.
     */
    private static void despawnCourierRaven(@NotNull ServerLevel level,
                                            @NotNull ServerPlayer contextPlayer,
                                            @NotNull RavenEntity raven,
                                            @NotNull String reason) {
        try {
            TamedRaven tamedModule = null;
            try {
                tamedModule = raven.getTamedRavenModule();
            } catch (Throwable t) {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: getTamedRavenModule failed safely: {}", t.toString());
            }

            String ravenName = "<unnamed>";
            try {
                if (raven.getCustomName() != null) {
                    ravenName = raven.getCustomName().getString();
                }
            } catch (Throwable ignored) {
            }

            // Trigger a one-shot Enderpop FX burst at the despawn position.
            try {
                Vec3 fxPos = raven.position().add(0.0D, 0.6D, 0.0D);
                long fxSeed =
                        raven.getUUID().getLeastSignificantBits()
                                ^ (long) raven.tickCount
                                ^ 0x9F42C3B1L; // fixed salt for courier-despawn
                Teleportation teleportFx = new Teleportation(raven);
                teleportFx.spawnEnderpopBurst(
                        level,
                        fxPos.x,
                        fxPos.y,
                        fxPos.z,
                        fxSeed,
                        "courier-despawn: " + reason,
                        raven
                );
            } catch (Throwable fxErr) {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: Teleportation FX failed safely for id={}: {}",
                        raven.getId(), fxErr.toString());
            }

            // Request fade-out FX via module if present.
            if (tamedModule != null) {
                try {
                    tamedModule.beginDespawnWithFx(level, contextPlayer, ravenName);
                    LOG.info("[RavenCourierRuntime] despawnCourierRaven: triggered despawn FX for id={} name='{}' player='{}' reason={}",
                            raven.getId(), ravenName, safePlayerName(contextPlayer), reason);
                } catch (Throwable t) {
                    LOG.error("[RavenCourierRuntime] despawnCourierRaven: beginDespawnWithFx failed; discarding raven directly. err={}",
                            t.toString());
                    raven.discard();
                }
            } else {
                LOG.warn("[RavenCourierRuntime] despawnCourierRaven: TamedRaven module null; discarding raven without FX. player='{}' id={} reason={}",
                        safePlayerName(contextPlayer), raven.getId(), reason);
                raven.discard();
            }

            // Clear courier flags so this raven is no longer treated as a courier.
            clearCourierFlags(raven);

        } catch (Throwable t) {
            LOG.error("[RavenCourierRuntime] despawnCourierRaven failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Simple helpers
    // ---------------------------------------------------------------------

    private static void ensureRavenName(@NotNull RavenEntity raven, @NotNull String ravenName) {
        try {
            Component cur = raven.getCustomName();
            String curStr = (cur == null) ? "" : cur.getString();
            if (!ravenName.equals(curStr)) {
                raven.setCustomName(Component.literal(ravenName));
            }
            raven.setCustomNameVisible(true);
        } catch (Throwable t) {
            LOG.warn("[RavenCourierRuntime] ensureRavenName failed safely for id={}: {}",
                    raven.getId(), t.toString());
        }
    }

    private static String safePlayerName(@NotNull Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (Throwable ignored) {
            try {
                return player.getName().getString();
            } catch (Throwable ignored2) {
                return "<unknown>";
            }
        }
    }
}
