// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/chat/ChatDisabler.java
package net.z2six.featheredfriend.chat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.network.FFPayloads;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * NeoForge-only chat disabler.
 *
 * Behavior:
 *  - SERVER:
 *      * If chatDisabled == true:
 *          - Cancels all player chat messages (ServerChatEvent).
 *          - Commands unaffected.
 *          - Informs sender.
 *
 *  - CLIENT:
 *      * If chatDisabled == true:
 *          - Blocks outgoing non-command chat locally.
 *          - Blocks incoming PLAYER chat messages.
 *          - Allows SYSTEM messages (command feedback, mod/system messages).
 */
public final class ChatDisabler {

    private static final Logger LOG = LogUtils.getLogger();

    private ChatDisabler() {
        // no-op
    }
    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(ChatDisabler::onServerChat);
            LOG.debug("[ChatDisabler] Registered ServerChatEvent listener.");

            if (FMLEnvironment.dist == Dist.CLIENT) {
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientSendChat);
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientReceiveChat);
                LOG.debug("[ChatDisabler] Registered ClientChatEvent + ClientChatReceivedEvent listeners.");
            }
        } catch (Throwable t) {
            LOG.error("[ChatDisabler] register() failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // SERVER
    // ---------------------------------------------------------------------

    private static void onServerChat(@NotNull ServerChatEvent event) {
        try {
            ServerPlayer sender = event.getPlayer();
            if (sender == null) return;

            if (!FFServerConfig.isChatDisabled()) {
                return;
            }

            String raw = event.getRawText();

            LOG.debug("[ChatDisabler] Blocking server chat from '{}' (raw='{}')",
                    safePlayerName(sender), raw);

            event.setCanceled(true);

            try {
                sender.sendSystemMessage(Component.translatable("message.featheredfriend.chat_disabled.server"));
            } catch (Throwable msgErr) {
                LOG.warn("[ChatDisabler] Failed to message sender='{}': {}",
                        safePlayerName(sender), msgErr.toString());
            }

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onServerChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT: outgoing
    // ---------------------------------------------------------------------

    private static void onClientSendChat(@NotNull ClientChatEvent event) {
        try {
            if (!isChatDisabledClient()) return;

            String message = event.getMessage();
            if (message == null) return;

            if (message.startsWith("/")) {
                LOG.debug("[ChatDisabler] Allowing client command: '{}'", message);
                return;
            }

            LOG.debug("[ChatDisabler] Blocking outgoing client chat message: '{}'", message);
            event.setCanceled(true);

            // Optional UX hint (kept very light; remove if you hate it)
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.translatable("message.featheredfriend.chat_disabled.client"), true
                    );
                }
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onClientSendChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT: incoming
    // ---------------------------------------------------------------------

    private static void onClientReceiveChat(@NotNull ClientChatReceivedEvent event) {
        try {
            if (!isChatDisabledClient()) return;

            Component msg = event.getMessage();
            String msgStr = (msg == null) ? "<null>" : msg.getString();

            if (event instanceof ClientChatReceivedEvent.Player) {
                LOG.debug("[ChatDisabler] Blocking incoming PLAYER chat message: '{}'", msgStr);
                event.setCanceled(true);
                return;
            }

            if (event instanceof ClientChatReceivedEvent.System) {
                LOG.debug("[ChatDisabler] Allowing incoming SYSTEM message: '{}'", msgStr);
                return;
            }

            LOG.debug("[ChatDisabler] Blocking incoming UNKNOWN chat type ({}) message: '{}'",
                    event.getClass().getName(), msgStr);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onClientReceiveChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT helper
    // ---------------------------------------------------------------------

    private static boolean isChatDisabledClient() {
        try {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                // Should never be called on server.
                return false;
            }

            // If we have synced server settings, ALWAYS use them.
            if (FFPayloads.ClientState.hasSynced()) {
                boolean v = FFPayloads.ClientState.isChatDisabled();
                LOG.debug("[ChatDisabler] isChatDisabledClient: using synced state -> {}", v);
                return v;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return false;

            // Integrated server: we can read the true world-owned setting.
            if (mc.hasSingleplayerServer()) {
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    boolean v = FFServerConfig.isChatDisabled();
                    LOG.debug("[ChatDisabler] isChatDisabledClient: integrated server config value -> {}", v);
                    return v;
                }
            }

            // Dedicated server, but not yet synced: default to enabled.
            LOG.debug("[ChatDisabler] isChatDisabledClient: not synced yet -> default false");
            return false;

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] isChatDisabledClient failed safely", t);
            return false;
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
