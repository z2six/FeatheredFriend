// forge/src/main/java/net/z2six/featheredfriend/client/ChatDisablerClient.java
package net.z2six.featheredfriend.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.z2six.featheredfriend.network.FFPayloads;
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
import org.slf4j.Logger;

/**
 * CLIENT-ONLY chat disabler.
 *
 * Do not reference this class from common/server init paths except behind a strict client-only bootstrap.
 */
public final class ChatDisablerClient {

    private static final Logger LOG = LogUtils.getLogger();

    private ChatDisablerClient() {}

    public static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(ChatDisablerClient::onClientSendChat);
            MinecraftForge.EVENT_BUS.addListener(ChatDisablerClient::onClientReceiveChat);
            LOG.info("[ChatDisablerClient] Registered ClientChatEvent + ClientChatReceivedEvent listeners.");
        } catch (Throwable t) {
            LOG.error("[ChatDisablerClient] register() failed safely", t);
        }
    }

    private static void onClientSendChat(ClientChatEvent event) {
        try {
            if (!isChatDisabledClient()) return;

            String message = event.getMessage();
            if (message == null) return;

            if (message.startsWith("/")) {
                LOG.debug("[ChatDisablerClient] Allowing client command: '{}'", message);
                return;
            }

            LOG.info("[ChatDisablerClient] Blocking outgoing client chat message: '{}'", message);
            event.setCanceled(true);

            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[FeatheredFriend] Chat is disabled on this server."), true
                    );
                }
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            LOG.error("[ChatDisablerClient] onClientSendChat failed safely", t);
        }
    }

    private static void onClientReceiveChat(ClientChatReceivedEvent event) {
        try {
            if (!isChatDisabledClient()) return;

            Component msg = event.getMessage();
            String msgStr = (msg == null) ? "<null>" : msg.getString();

            if (event instanceof ClientChatReceivedEvent.Player) {
                LOG.debug("[ChatDisablerClient] Blocking incoming PLAYER chat message: '{}'", msgStr);
                event.setCanceled(true);
                return;
            }

            if (event instanceof ClientChatReceivedEvent.System) {
                LOG.debug("[ChatDisablerClient] Allowing incoming SYSTEM message: '{}'", msgStr);
                return;
            }

            LOG.debug("[ChatDisablerClient] Blocking incoming UNKNOWN chat type ({}) message: '{}'",
                    event.getClass().getName(), msgStr);
            event.setCanceled(true);

        } catch (Throwable t) {
            LOG.error("[ChatDisablerClient] onClientReceiveChat failed safely", t);
        }
    }

    private static boolean isChatDisabledClient() {
        try {
            // If we have synced server settings, ALWAYS use them.
            if (FFPayloads.ClientState.hasSynced()) {
                boolean v = FFPayloads.ClientState.isChatDisabled();
                LOG.debug("[ChatDisablerClient] isChatDisabledClient: using synced state -> {}", v);
                return v;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return true;

            // Integrated server: we can read the true world-owned setting.
            if (mc.hasSingleplayerServer()) {
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    ServerLevel overworld = server.overworld();
                    if (overworld != null) {
                        FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(overworld);
                        boolean v = data.isChatDisabled();
                        LOG.debug("[ChatDisablerClient] isChatDisabledClient: integrated server world value -> {}", v);
                        return v;
                    }
                }
            }

            // Dedicated server, but not yet synced: conservative behavior is "disabled" until we know.
            LOG.debug("[ChatDisablerClient] isChatDisabledClient: not synced yet -> default true");
            return true;

        } catch (Throwable t) {
            LOG.error("[ChatDisablerClient] isChatDisabledClient failed safely", t);
            return true;
        }
    }
}
