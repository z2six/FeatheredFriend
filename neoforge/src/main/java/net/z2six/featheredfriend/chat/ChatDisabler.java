// neoforge/src/main/java/net/z2six/featheredfriend/chat/ChatDisabler.java
package net.z2six.featheredfriend.chat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/chat/ChatDisabler.java
 *
 * NeoForge-only chat disabler.
 *
 * Behavior:
 *  - SERVER:
 *      * If chatDisabled setting is true:
 *          - Cancels all player chat messages (ServerChatEvent).
 *          - Commands are unaffected (they are not routed through ServerChatEvent).
 *          - Informs the sender that global chat is disabled.
 *      * If chatDisabled setting is false:
 *          - Does nothing; chat behaves normally.
 *
 *  - CLIENT:
 *      * When the local player types a message:
 *          - If chatDisabled setting is false -> do nothing.
 *          - If chatDisabled setting is true:
 *              - If it starts with "/", it is treated as a command and allowed through.
 *              - Otherwise, the send is cancelled (no chat reaches the server).
 *      * When any chat/system message arrives from the server:
 *          - If chatDisabled setting is false -> do nothing (show everything).
 *          - If chatDisabled setting is true:
 *              - Player chat is blocked (ClientChatReceivedEvent.Player).
 *              - System messages (ClientChatReceivedEvent.System) are allowed so that:
 *                  * Vanilla command feedback (e.g. /data get) still shows.
 *                  * Mod/system messages (e.g. FeatheredFriend notifications) still show.
 *
 * Result:
 *  - chatDisabled == true  -> player chat is effectively nuked, commands + system output remain.
 *  - chatDisabled == false -> this class is essentially inert.
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
            // Server-side: block player chat based on settings.
            NeoForge.EVENT_BUS.addListener(ChatDisabler::onServerChat);
            LOG.info("[ChatDisabler] Registered ServerChatEvent listener.");

            // Client-side: block outgoing non-command chat and filter incoming chat based on settings.
            if (FMLEnvironment.dist == Dist.CLIENT) {
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientSendChat);
                NeoForge.EVENT_BUS.addListener(ChatDisabler::onClientReceiveChat);
                LOG.info("[ChatDisabler] Registered ClientChatEvent + ClientChatReceivedEvent listeners.");
            }
        } catch (Throwable t) {
            LOG.error("[ChatDisabler] register() failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // SERVER: block player chat if setting enabled (but not commands)
    // ---------------------------------------------------------------------

    private static void onServerChat(@NotNull ServerChatEvent event) {
        try {
            ServerPlayer sender = event.getPlayer();
            if (sender == null) {
                return;
            }

            ServerLevel level = sender.serverLevel();
            FeatheredFriendSettingsData settings = FeatheredFriendSettingsData.get(level);
            if (!settings.isChatDisabled()) {
                // Chat not disabled -> do nothing.
                return;
            }

            String raw = event.getRawText(); // non-null in normal cases

            LOG.info("[ChatDisabler] Blocking server chat from '{}' (raw='{}')",
                    safePlayerName(sender), raw);

            // Cancel broadcast – no player receives this as chat.
            event.setCanceled(true);

            // Inform the sender that global chat is disabled.
            try {
                sender.sendSystemMessage(Component.literal(
                        "[FeatheredFriend] Global player chat is disabled on this server."
                ));
            } catch (Throwable msgErr) {
                LOG.warn("[ChatDisabler] Failed to send 'chat disabled' message to sender='{}': {}",
                        safePlayerName(sender), msgErr.toString());
            }

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] onServerChat failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // CLIENT: block outgoing non-command chat (when chatDisabled == true)
    // ---------------------------------------------------------------------

    private static void onClientSendChat(@NotNull ClientChatEvent event) {
        try {
            if (!isChatDisabledClient()) {
                // Chat is enabled -> do nothing.
                return;
            }

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
    // CLIENT: filter incoming chat/system messages (when chatDisabled == true)
    // ---------------------------------------------------------------------

    private static void onClientReceiveChat(@NotNull ClientChatReceivedEvent event) {
        try {
            if (!isChatDisabledClient()) {
                // Chat is enabled -> show everything.
                return;
            }

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
                // Not cancelled -> rendered in chat HUD.
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
    // CLIENT helper: resolve chatDisabled safely
    // ---------------------------------------------------------------------

    private static boolean isChatDisabledClient() {
        try {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                // On server side this helper should never be called, but just in case.
                return true;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return true; // conservative default: disable chat
            }

            // Integrated server case: we can read the real world-owned setting.
            if (mc.hasSingleplayerServer()) {
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    ServerLevel overworld = server.overworld();
                    if (overworld != null) {
                        FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(overworld);
                        return data.isChatDisabled();
                    }
                }
            }

            // Remote dedicated server: for now, default to "chat disabled" to keep
            // behavior consistent until a proper server->client sync is added.
            return true;

        } catch (Throwable t) {
            LOG.error("[ChatDisabler] isChatDisabledClient failed safely", t);
            return true;
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
