// neoforge/src/main/java/net/z2six/featheredfriend/chat/ChatDisabler.java
package net.z2six.featheredfriend.chat;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/chat/ChatDisabler.java
 *
 * NeoForge-only chat disabler.
 *
 * Behavior:
 *  - SERVER:
 *      * Cancels all player chat messages (ServerChatEvent).
 *      * Commands are unaffected (they are not routed through ServerChatEvent).
 *      * Optionally informs the sender that global chat is disabled.
 *
 *  - CLIENT:
 *      * When the local player types a message:
 *          - If it starts with "/", it is treated as a command and allowed through.
 *          - Otherwise, the send is cancelled (no chat reaches the server).
 *      * When any chat/system message arrives from the server:
 *          - Player chat is blocked (ClientChatReceivedEvent.Player).
 *          - System messages (ClientChatReceivedEvent.System) are allowed so that:
 *              * Vanilla command feedback (e.g. /data get) still shows.
 *              * Mod/system messages (e.g. FeatheredFriend notifications) still show.
 *
 * Result:
 *  - Players can still enter and execute commands via the chat input box.
 *  - No player-to-player chat ever goes through.
 *  - Chat HUD only shows system / command / mod messages.
 */
public final class ChatDisabler {

    private static final Logger LOG = LogUtils.getLogger();

    private ChatDisabler() {
        // no-op
    }

    /**
     * Call this once from your mod's initialization on the NeoForge side,
     * e.g. in your main mod class constructor:
     *
     *   ChatDisabler.register();
     */
    public static void register() {
        try {
            // Server-side: block player chat.
            NeoForge.EVENT_BUS.addListener(ChatDisabler::onServerChat);
            LOG.info("[ChatDisabler] Registered ServerChatEvent listener (global chat disabled on server).");

            // Client-side: block outgoing non-command chat and filter incoming chat.
            if (FMLEnvironment.dist == Dist.CLIENT) {
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientSendChat);
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientReceiveChat);
                LOG.info("[ChatDisabler] Registered ClientChatEvent + ClientChatReceivedEvent listeners (chat filtered on client).");
            }
        } catch (Throwable t) {
            LOG.error("[ChatDisabler] register() failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // SERVER: block all player chat (but not commands)
    // ---------------------------------------------------------------------

    /**
     * Fired whenever a player sends a chat message that would normally be broadcast.
     *
     * We:
     *  - Cancel the event so the message never reaches any players.
     *  - Optionally tell the sender that chat is disabled.
     */
    private static void onServerChat(@NotNull ServerChatEvent event) {
        try {
            ServerPlayer sender = event.getPlayer();
            String raw = event.getRawText(); // non-null in normal cases

            // Debug log for visibility.
            LOG.info("[ChatDisabler] Blocking server chat from '{}' (raw='{}')",
                    safePlayerName(sender), raw);

            // Cancel broadcast – no player receives this as chat.
            event.setCanceled(true);

            // Optional: tell the sender that global chat is disabled.
            try {
                if (sender != null) {
                    sender.sendSystemMessage(Component.literal(
                            "[FeatheredFriend] Global player chat is disabled on this server."
                    ));
                }
            } catch (Throwable msgErr) {
                LOG.warn("[ChatDisabler] Failed to send 'chat disabled' message to sender='{}': {}",
                        safePlayerName(sender), msgErr.toString());
            }

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onServerChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT: block outgoing non-command chat
    // ---------------------------------------------------------------------

    /**
     * Fired on the client when the local player is about to send a chat message
     * to the server (including commands).
     *
     * We:
     *  - Allow messages starting with '/' (commands).
     *  - Cancel everything else (normal chat).
     */
    private static void onClientSendChat(@NotNull ClientChatEvent event) {
        try {
            String message = event.getMessage();
            if (message == null) {
                return;
            }

            // Commands are allowed: they still go to the server.
            if (message.startsWith("/")) {
                LOG.debug("[ChatDisabler] Allowing client command: '{}'", message);
                return;
            }

            // Any non-command chat is blocked here and never reaches the server.
            LOG.info("[ChatDisabler] Blocking outgoing client chat message: '{}'", message);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onClientSendChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT: filter incoming chat/system messages
    // ---------------------------------------------------------------------

    /**
     * Fired on the client when a chat/system message is about to be displayed
     * in the chat HUD.
     *
     * We:
     *  - Block player chat (ClientChatReceivedEvent.Player).
     *  - Allow system messages (ClientChatReceivedEvent.System) so that:
     *      * Vanilla command feedback (e.g. /data get) still appears.
     *      * Mod/system messages (e.g. FeatheredFriend notifications) still appear.
     */
    private static void onClientReceiveChat(@NotNull ClientChatReceivedEvent event) {
        try {
            Component msg = event.getMessage();
            String msgStr = (msg == null) ? "<null>" : msg.getString();

            // Player chat: block completely (no player-to-player messages).
            if (event instanceof ClientChatReceivedEvent.Player) {
                LOG.debug("[ChatDisabler] Blocking incoming PLAYER chat message: '{}'", msgStr);
                event.setCanceled(true);
                return;
            }

            // System messages: allow, so commands + mod messages still show.
            if (event instanceof ClientChatReceivedEvent.System) {
                LOG.debug("[ChatDisabler] Allowing incoming SYSTEM message: '{}'", msgStr);
                // Do NOT cancel; let it be rendered in chat HUD.
                return;
            }

            // Fallback: unknown subtype – be conservative and block it.
            LOG.debug("[ChatDisabler] Blocking incoming UNKNOWN chat type ({}) message: '{}'",
                    event.getClass().getName(), msgStr);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onClientReceiveChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Simple helper
    // ---------------------------------------------------------------------

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
