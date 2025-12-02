// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/commands/FFSigilCommandsNeoForge.java
package net.z2six.featheredfriend.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.z2six.featheredfriend.network.SigilPreviewPayload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public final class FFSigilCommandsNeoForge {

    private static final Logger LOG = LogUtils.getLogger();

    private FFSigilCommandsNeoForge() {}

    public static void register(@NotNull RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("ff")
                        .then(Commands.literal("sigil")
                                .then(Commands.literal("generate")
                                        .then(Commands.argument("seed", StringArgumentType.greedyString())
                                                .executes(ctx -> execute(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "seed")
                                                )))))
        );

        LOG.debug("[FFSigilCommandsNeoForge] Registered command");
    }

    private static int execute(@NotNull CommandSourceStack source, String seed) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            String normalized = (seed == null || seed.isEmpty())
                    ? player.getStringUUID()
                    : seed;

            // SEND PACKET → client opens GUI
            PacketDistributor.sendToPlayer(player, new SigilPreviewPayload(normalized));

            return 1;

        } catch (Throwable t) {
            LOG.error("[FFSigilCommandsNeoForge] Command failed", t);
            return 0;
        }
    }
}
