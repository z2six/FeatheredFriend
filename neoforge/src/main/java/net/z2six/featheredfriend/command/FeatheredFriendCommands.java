// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/command/FeatheredFriendCommands.java
package net.z2six.featheredfriend.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.world.RavenCourierData;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class FeatheredFriendCommands {

    private static final Logger LOG = LogUtils.getLogger();

    private FeatheredFriendCommands() {
        // no-op
    }

    /**
     * Called from FeatheredFriend main class:
     *
     *   FeatheredFriendCommands.register();
     */
    public static void register() {
        try {
            // Use an explicitly-typed lambda so the generic parameter T is inferred
            // as RegisterCommandsEvent and not plain Event.
            NeoForge.EVENT_BUS.addListener(
                    (RegisterCommandsEvent event) -> FeatheredFriendCommands.onRegisterCommands(event)
            );
            LOG.info("[FeatheredFriendCommands] Registered command listener on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] Failed to register command listener", t);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

            // Root: /featheredfriend ...
            dispatcher.register(
                    Commands.literal("featheredfriend")
                            .requires(src -> src.hasPermission(2)) // OP-only
                            // -----------------------------------------------------------------
                            // /featheredfriend clear_tamed_raven
                            // -----------------------------------------------------------------
                            .then(Commands.literal("clear_tamed_raven")
                                    .executes(FeatheredFriendCommands::executeClearTamedRavenSelf)
                            )
                            // -----------------------------------------------------------------
                            // /featheredfriend courier ...
                            // -----------------------------------------------------------------
                            .then(Commands.literal("courier")
                                    // /featheredfriend courier list
                                    // /featheredfriend courier list <player>
                                    .then(Commands.literal("list")
                                            .executes(FeatheredFriendCommands::executeCourierListAll)
                                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                    .executes(FeatheredFriendCommands::executeCourierListPlayer)
                                            )
                                    )
                                    // /featheredfriend courier clear
                                    // /featheredfriend courier clear <player>
                                    .then(Commands.literal("clear")
                                            .executes(FeatheredFriendCommands::executeCourierClearAll)
                                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                    .executes(FeatheredFriendCommands::executeCourierClearPlayer)
                                            )
                                    )
                            )
            );

            // Short alias: /ff_clear_tamed_raven
            dispatcher.register(
                    Commands.literal("ff_clear_tamed_raven")
                            .requires(src -> src.hasPermission(2))
                            .executes(FeatheredFriendCommands::executeClearTamedRavenSelf)
            );

            LOG.info("[FeatheredFriendCommands] Commands registered: " +
                    "/featheredfriend clear_tamed_raven, " +
                    "/ff_clear_tamed_raven, " +
                    "/featheredfriend courier list [player], " +
                    "/featheredfriend courier clear [player]");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] onRegisterCommands failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // clear_tamed_raven
    // ---------------------------------------------------------------------

    /**
     * Command handler:
     *   - /featheredfriend clear_tamed_raven
     *   - /ff_clear_tamed_raven
     *
     * Clears the EXECUTING PLAYER's stored TamedRaven data from NeoForge persistent
     * player data (NeoForgeData.featheredfriend.TamedRaven.*).
     */
    private static int executeClearTamedRavenSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException ex) {
            // Not a player (e.g., console)
            LOG.warn("[FeatheredFriendCommands] clear_tamed_raven: source is not a player (name={})",
                    source.getTextName());
            source.sendFailure(Component.literal("[FeatheredFriend] This command must be run by a player."));
            throw ex;
        }

        MinecraftServer server = player.server;
        if (server == null) {
            LOG.warn("[FeatheredFriendCommands] clear_tamed_raven: server is null for player={}", player.getGameProfile().getName());
            source.sendFailure(Component.literal("[FeatheredFriend] Internal error: server is null."));
            return 0;
        }

        boolean cleared = clearPlayerTamedRavenData(player);
        final boolean clearedFinal = cleared;

        source.sendSuccess(
                () -> Component.literal(
                        "[FeatheredFriend] "
                                + (clearedFinal
                                ? "Cleared tamed raven data for " + player.getGameProfile().getName()
                                : "Found no tamed raven data to clear for " + player.getGameProfile().getName())
                ),
                true
        );

        return cleared ? 1 : 0;
    }

    /**
     * Directly manipulates the player's persistent NeoForge data:
     *
     * NeoForgeData: {
     *   featheredfriend: {
     *     TamedRaven: {
     *       OwnerUUID: ...
     *       OwnerDimension: ...
     *       RavenName: "..."
     *       HasTamedRaven: 1b
     *       ...
     *     }
     *   }
     * }
     *
     * We set HasTamedRaven=false and aggressively strip the other fields.
     *
     * @return true if we actually found HasTamedRaven == true and cleared it.
     */
    private static boolean clearPlayerTamedRavenData(ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: no persistent data for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            // This maps to the "featheredfriend" compound inside NeoForgeData in the NBT dump.
            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: no '{}' tag for player={}",
                        Constants.MOD_ID, player.getGameProfile().getName());
                return false;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains("TamedRaven", Tag.TAG_COMPOUND)) {
                LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: no TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            CompoundTag tamed = modTag.getCompound("TamedRaven");
            if (tamed == null || tamed.isEmpty()) {
                LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: empty TamedRaven compound for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            boolean had = tamed.getBoolean("HasTamedRaven");

            LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: BEFORE clear player={} tag={}",
                    player.getGameProfile().getName(), tamed);

            // Hard-reset the fields we know about.
            tamed.putBoolean("HasTamedRaven", false);
            tamed.remove("RavenName");
            tamed.remove("OwnerUUID");
            tamed.remove("OwnerDimension");

            // If the compound is now empty, remove it entirely.
            if (tamed.isEmpty()) {
                modTag.remove("TamedRaven");
            } else {
                modTag.put("TamedRaven", tamed);
            }

            // If the mod compound is now empty, remove it entirely.
            if (modTag.isEmpty()) {
                root.remove(Constants.MOD_ID);
            } else {
                root.put(Constants.MOD_ID, modTag);
            }

            LOG.info("[FeatheredFriendCommands] clearPlayerTamedRavenData: AFTER clear player={} had={} nowTag={}",
                    player.getGameProfile().getName(), had, tamed);

            return had;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] clearPlayerTamedRavenData failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Courier commands
    // ---------------------------------------------------------------------

    /**
     * /featheredfriend courier list
     *
     * Lists all pending courier jobs in the world.
     */
    private static int executeCourierListAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            List<RavenCourierData.DeliveryJob> jobs = data.getAllJobsFlat();
            if (jobs.isEmpty()) {
                source.sendSuccess(
                        () -> Component.literal("[FeatheredFriend] No pending raven courier jobs found."),
                        false
                );
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal("[FeatheredFriend] Pending raven courier jobs: " + jobs.size()),
                    false
            );

            for (RavenCourierData.DeliveryJob job : jobs) {
                if (job == null) {
                    continue;
                }
                final String line = String.format(
                        " - id=%d sender='%s' [%s] -> recipient='%s' [%s] inFlight=%s sealedScroll=%s",
                        job.jobId,
                        job.senderName,
                        job.senderUuid,
                        job.recipientName,
                        job.recipientUuid,
                        job.inFlight,
                        job.sealedScrollNbt // full SealedScroll compound
                );
                source.sendSuccess(() -> Component.literal(line), false);
            }

            return jobs.size();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierListAll failed", t);
            source.sendFailure(Component.literal("[FeatheredFriend] Error while listing courier jobs; see log."));
            return 0;
        }
    }

    /**
     * /featheredfriend courier list <player>
     *
     * Lists all courier jobs where the given player is either sender OR recipient.
     */
    private static int executeCourierListPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        try {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) {
                source.sendFailure(Component.literal("[FeatheredFriend] No matching player found."));
                return 0;
            }

            GameProfile profile = profiles.iterator().next();
            UUID targetUuid = profile.getId();
            String targetName = profile.getName();

            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            List<RavenCourierData.DeliveryJob> jobs = data.getJobsForPlayer(targetUuid);
            if (jobs.isEmpty()) {
                source.sendSuccess(
                        () -> Component.literal("[FeatheredFriend] No courier jobs found for player '" + targetName + "'."),
                        false
                );
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal("[FeatheredFriend] Courier jobs for '" + targetName + "': " + jobs.size()),
                    false
            );

            for (RavenCourierData.DeliveryJob job : jobs) {
                if (job == null) {
                    continue;
                }
                final String line = String.format(
                        " - id=%d sender='%s' [%s] -> recipient='%s' [%s] inFlight=%s",
                        job.jobId,
                        job.senderName,
                        job.senderUuid,
                        job.recipientName,
                        job.recipientUuid,
                        job.inFlight,
                        job.sealedScrollNbt // full SealedScroll compound
                );
                source.sendSuccess(() -> Component.literal(line), false);
            }

            return jobs.size();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierListPlayer failed", t);
            source.sendFailure(Component.literal("[FeatheredFriend] Error while listing courier jobs for player; see log."));
            return 0;
        }
    }

    /**
     * /featheredfriend courier clear
     *
     * Removes ALL courier jobs from the world.
     */
    private static int executeCourierClearAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            int removed = data.clearAllJobs();
            source.sendSuccess(
                    () -> Component.literal("[FeatheredFriend] Cleared " + removed + " raven courier job(s) from the world."),
                    true
            );
            return removed > 0 ? 1 : 0;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierClearAll failed", t);
            source.sendFailure(Component.literal("[FeatheredFriend] Error while clearing courier jobs; see log."));
            return 0;
        }
    }

    /**
     * /featheredfriend courier clear <player>
     *
     * Removes all courier jobs where the given player is either sender OR recipient.
     */
    private static int executeCourierClearPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        try {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) {
                source.sendFailure(Component.literal("[FeatheredFriend] No matching player found."));
                return 0;
            }

            GameProfile profile = profiles.iterator().next();
            UUID targetUuid = profile.getId();
            String targetName = profile.getName();

            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            int removed = data.clearJobsForPlayer(targetUuid);

            source.sendSuccess(
                    () -> Component.literal(
                            "[FeatheredFriend] Cleared "
                                    + removed
                                    + " raven courier job(s) for player '"
                                    + targetName
                                    + "'."
                    ),
                    true
            );

            return removed > 0 ? 1 : 0;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierClearPlayer failed", t);
            source.sendFailure(Component.literal("[FeatheredFriend] Error while clearing courier jobs for player; see log."));
            return 0;
        }
    }
}
