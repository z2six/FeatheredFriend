// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/command/FeatheredFriendCommands.java
package net.z2six.featheredfriend.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

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

            // Root: /featheredfriend clear_tamed_raven
            dispatcher.register(
                    Commands.literal("featheredfriend")
                            .requires(src -> src.hasPermission(2)) // OP-only
                            .then(Commands.literal("clear_tamed_raven")
                                    .executes(FeatheredFriendCommands::executeClearTamedRavenSelf)
                            )
            );

            // Short alias: /ff_clear_tamed_raven
            dispatcher.register(
                    Commands.literal("ff_clear_tamed_raven")
                            .requires(src -> src.hasPermission(2))
                            .executes(FeatheredFriendCommands::executeClearTamedRavenSelf)
            );

            LOG.info("[FeatheredFriendCommands] Commands registered: /featheredfriend clear_tamed_raven, /ff_clear_tamed_raven");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] onRegisterCommands failed", t);
        }
    }

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
}
