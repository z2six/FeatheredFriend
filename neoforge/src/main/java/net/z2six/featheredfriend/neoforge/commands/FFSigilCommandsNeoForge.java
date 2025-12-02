// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/neoforge/commands/FFSigilCommandsNeoForge.java
package net.z2six.featheredfriend.neoforge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.z2six.featheredfriend.network.FFNetwork;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**

 neoforge/src/main/java/net/z2six/featheredfriend/neoforge/commands/FFSigilCommandsNeoForge.java

 FFSigilCommandsNeoForge

 Registers:

 /ff sigil generate <slices> <shapeSetIndex> <seed>

 Where:

 slices : number of symmetry slices (2–8)

 shapeSetIndex : integer index of shape set (0 = first set)

 seed : arbitrary string, hashed for deterministic preview
 */
public final class FFSigilCommandsNeoForge {

    private static final Logger LOG = LogUtils.getLogger();

    private FFSigilCommandsNeoForge() {}

    public static void register(@NotNull RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("ff")
                        .then(Commands.literal("sigil")
                                .then(Commands.literal("generate")
                                        .then(Commands.argument("slices", IntegerArgumentType.integer(2, 8))
                                                .then(Commands.argument("shapeSetIndex", IntegerArgumentType.integer(0))
                                                        .then(Commands.argument("seed", StringArgumentType.greedyString())
                                                                .executes(ctx ->
                                                                        execute(
                                                                                ctx.getSource(),
                                                                                IntegerArgumentType.getInteger(ctx, "slices"),
                                                                                IntegerArgumentType.getInteger(ctx, "shapeSetIndex"),
                                                                                StringArgumentType.getString(ctx, "seed")
                                                                        )))))));

        dispatcher.register(root);

        LOG.debug("[FFSigilCommandsNeoForge] /ff sigil generate <slices> <shapeSetIndex> <seed> registered");
    }

    private static int execute(@NotNull CommandSourceStack src,
                               int slices,
                               int shapeSetIndex,
                               String seed) {
        try {
            ServerPlayer player = src.getPlayerOrException();

            if (seed == null || seed.isBlank()) {
                seed = player.getStringUUID();
            }

            FFNetwork.sendSigilPreview(player, slices, shapeSetIndex, seed);

            return 1;

        } catch (Throwable t) {
            LOG.error("[FFSigilCommandsNeoForge] Error executing sigil generate", t);
            try {
                src.sendFailure(Component.literal("Sigil generate failed."));
            } catch (Throwable ignored) {
                // swallow
            }
            return 0;
        }
    }


}