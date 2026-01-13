// MainFile: forge/src/main/java/net/z2six/featheredfriend/network/FFPayloads.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.world.FeatheredFriendSettingsData;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Settings sync networking for Forge 1.20.1 (SimpleChannel).
 *
 * What we sync (server-owned):
 * - chatDisabled (global)
 * - canEditChat (per-player permission check, computed server-side)
 *
 * What we sync (client-owned, per-player preference):
 * - autoSummonOnScroll (client preference that influences server tick logic)
 */
public final class FFPayloads {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Bump if you change message shapes. Must match client + server.
     *
     * v2:
     * - added ClientAutoSummonPrefPayload (C2S)
     */
    private static final String PROTOCOL_VERSION = "2";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new net.minecraft.resources.ResourceLocation(Constants.MOD_ID, "settings"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    /**
     * Per-player key stored in ServerPlayer persistent NBT (server side).
     * This is the server’s authoritative copy of the client preference, updated by C2S message.
     */
    public static final String PLAYER_NBT_KEY_AUTO_SUMMON_PREF = "ClientAutoSummonOnScroll";

    private FFPayloads() {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Registration hook
    // ---------------------------------------------------------------------

    public static void register(IEventBus modBus) {
        modBus.addListener(FFPayloads::onCommonSetup);
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(FFPayloads::registerMessagesOnce);
    }

    private static void registerMessagesOnce() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        try {
            AtomicInteger id = new AtomicInteger(0);

            CHANNEL.messageBuilder(RequestServerSettingsPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RequestServerSettingsPayload::encode)
                    .decoder(RequestServerSettingsPayload::decode)
                    .consumerMainThread(FFPayloads::handleRequestServerSettings)
                    .add();

            CHANNEL.messageBuilder(ServerSettingsPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(ServerSettingsPayload::encode)
                    .decoder(ServerSettingsPayload::decode)
                    .consumerMainThread(FFPayloads::handleServerSettingsSync)
                    .add();

            CHANNEL.messageBuilder(SetChatDisabledPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SetChatDisabledPayload::encode)
                    .decoder(SetChatDisabledPayload::decode)
                    .consumerMainThread(FFPayloads::handleSetChatDisabled)
                    .add();

            // NEW (C2S): client per-player auto-summon preference
            CHANNEL.messageBuilder(ClientAutoSummonPrefPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(ClientAutoSummonPrefPayload::encode)
                    .decoder(ClientAutoSummonPrefPayload::decode)
                    .consumerMainThread(FFPayloads::handleClientAutoSummonPref)
                    .add();

            LOG.info("[FFPayloads] Registered settings messages OK (protocol={})", PROTOCOL_VERSION);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] Failed to register settings messages", t);
        }
    }

    // ---------------------------------------------------------------------
    // Client-side cache (server-owned settings)
    // ---------------------------------------------------------------------

    public static final class ClientState {
        private static volatile boolean hasSynced = false;
        private static volatile boolean chatDisabled = true;
        private static volatile boolean canEditChat = false;

        private ClientState() {}

        public static boolean hasSynced() { return hasSynced; }
        public static boolean isChatDisabled() { return chatDisabled; }
        public static boolean canEditChat() { return canEditChat; }

        private static void applyFromServer(boolean newChatDisabled, boolean newCanEditChat) {
            chatDisabled = newChatDisabled;
            canEditChat = newCanEditChat;
            hasSynced = true;

            LOG.info("[FFPayloads.ClientState] Applied server settings: chatDisabled={} canEditChat={}",
                    newChatDisabled, newCanEditChat);
        }

        public static void clear() {
            hasSynced = false;
            chatDisabled = true;
            canEditChat = false;
            LOG.info("[FFPayloads.ClientState] Cleared client cache");
        }
    }

    // ---------------------------------------------------------------------
    // Message types
    // ---------------------------------------------------------------------

    public record RequestServerSettingsPayload() {
        static void encode(RequestServerSettingsPayload msg, FriendlyByteBuf buf) {
            // no fields
        }
        static RequestServerSettingsPayload decode(FriendlyByteBuf buf) {
            return new RequestServerSettingsPayload();
        }
    }

    public record ServerSettingsPayload(boolean chatDisabled, boolean canEditChat) {
        static void encode(ServerSettingsPayload msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.chatDisabled);
            buf.writeBoolean(msg.canEditChat);
        }
        static ServerSettingsPayload decode(FriendlyByteBuf buf) {
            boolean cd = buf.readBoolean();
            boolean ce = buf.readBoolean();
            return new ServerSettingsPayload(cd, ce);
        }
    }

    public record SetChatDisabledPayload(boolean chatDisabled) {
        static void encode(SetChatDisabledPayload msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.chatDisabled);
        }
        static SetChatDisabledPayload decode(FriendlyByteBuf buf) {
            return new SetChatDisabledPayload(buf.readBoolean());
        }
    }

    /**
     * Client -> Server: per-player preference controlling whether holding a sealed scroll auto-summons the raven.
     * Server stores it on the player persistent NBT.
     */
    public record ClientAutoSummonPrefPayload(boolean autoSummonOnScroll) {
        static void encode(ClientAutoSummonPrefPayload msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.autoSummonOnScroll);
        }
        static ClientAutoSummonPrefPayload decode(FriendlyByteBuf buf) {
            return new ClientAutoSummonPrefPayload(buf.readBoolean());
        }
    }

    // ---------------------------------------------------------------------
    // Client send helpers
    // ---------------------------------------------------------------------

    public static void sendRequestServerSettingsToServer() {
        try {
            CHANNEL.sendToServer(new RequestServerSettingsPayload());
            LOG.debug("[FFPayloads] Sent RequestServerSettingsPayload to server");
        } catch (Throwable t) {
            LOG.error("[FFPayloads] sendRequestServerSettingsToServer failed", t);
        }
    }

    public static void sendSetChatDisabledToServer(boolean chatDisabled) {
        try {
            CHANNEL.sendToServer(new SetChatDisabledPayload(chatDisabled));
            LOG.debug("[FFPayloads] Sent SetChatDisabledPayload to server (chatDisabled={})", chatDisabled);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] sendSetChatDisabledToServer failed", t);
        }
    }

    public static void sendClientAutoSummonPrefToServer(boolean autoSummonOnScroll) {
        try {
            CHANNEL.sendToServer(new ClientAutoSummonPrefPayload(autoSummonOnScroll));
            LOG.debug("[FFPayloads] Sent ClientAutoSummonPrefPayload to server (autoSummonOnScroll={})", autoSummonOnScroll);
        } catch (Throwable t) {
            LOG.error("[FFPayloads] sendClientAutoSummonPrefToServer failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Server-side send helpers (server-owned settings)
    // ---------------------------------------------------------------------

    public static void sendSettingsToPlayer(ServerLevel level, ServerPlayer player) {
        try {
            if (level == null || player == null) {
                LOG.warn("[FFPayloads] sendSettingsToPlayer: null args; skipping");
                return;
            }

            FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(level);

            boolean chatDisabledValue = data.isChatDisabled();
            boolean canEditChatValue;
            try {
                canEditChatValue = player.hasPermissions(4);
            } catch (Throwable ignored) {
                canEditChatValue = false;
            }

            ServerSettingsPayload msg = new ServerSettingsPayload(chatDisabledValue, canEditChatValue);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);

            LOG.info("[FFPayloads] Sent settings to {}: chatDisabled={} canEditChat={}",
                    player.getGameProfile().getName(), chatDisabledValue, canEditChatValue);

        } catch (Throwable t) {
            LOG.error("[FFPayloads] sendSettingsToPlayer failed safely", t);
        }
    }

    public static void broadcastSettings(ServerLevel level) {
        try {
            if (level == null || level.getServer() == null) {
                LOG.warn("[FFPayloads] broadcastSettings: null level/server; skipping");
                return;
            }

            List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
            for (ServerPlayer sp : players) {
                try {
                    sendSettingsToPlayer(level, sp);
                } catch (Throwable t) {
                    LOG.warn("[FFPayloads] broadcastSettings: failed sending to {}: {}",
                            sp.getGameProfile().getName(), t.toString());
                }
            }
        } catch (Throwable t) {
            LOG.error("[FFPayloads] broadcastSettings failed safely", t);
        }
    }

    // ---------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------

    private static void handleRequestServerSettings(RequestServerSettingsPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) {
                return;
            }

            ctx.enqueueWork(() -> {
                try {
                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] RequestServerSettings: serverLevel null; ignoring");
                        return;
                    }
                    sendSettingsToPlayer(level, sp);
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleRequestServerSettings work failed safely", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleServerSettingsSync(ServerSettingsPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientState.applyFromServer(payload.chatDisabled(), payload.canEditChat());
                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleServerSettingsSync work failed safely", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleSetChatDisabled(SetChatDisabledPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) {
                return;
            }

            ctx.enqueueWork(() -> {
                try {
                    ServerLevel level = sp.serverLevel();
                    if (level == null) {
                        LOG.warn("[FFPayloads] SetChatDisabled: serverLevel null; ignoring");
                        return;
                    }

                    boolean allowed;
                    try {
                        allowed = sp.hasPermissions(4);
                    } catch (Throwable ignored) {
                        allowed = false;
                    }

                    if (!allowed) {
                        LOG.warn("[FFPayloads] {} tried to SetChatDisabled without permission; denied",
                                sp.getGameProfile().getName());
                        sendSettingsToPlayer(level, sp);
                        return;
                    }

                    FeatheredFriendSettingsData data = FeatheredFriendSettingsData.get(level);
                    data.setChatDisabled(payload.chatDisabled());

                    LOG.info("[FFPayloads] {} set chatDisabled -> {}",
                            sp.getGameProfile().getName(), payload.chatDisabled());

                    broadcastSettings(level);

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleSetChatDisabled work failed safely", t);
                }
            });

        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleClientAutoSummonPref(ClientAutoSummonPrefPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) {
                return;
            }

            ctx.enqueueWork(() -> {
                try {
                    CompoundTag root = sp.getPersistentData();
                    if (root == null) {
                        LOG.warn("[FFPayloads] handleClientAutoSummonPref: player persistentData null; skipping (player={})",
                                sp.getGameProfile().getName());
                        return;
                    }

                    CompoundTag ffTag = root.getCompound(Constants.MOD_ID);
                    ffTag.putBoolean(PLAYER_NBT_KEY_AUTO_SUMMON_PREF, payload.autoSummonOnScroll());
                    root.put(Constants.MOD_ID, ffTag);

                    LOG.info("[FFPayloads] Stored client autoSummon preference for {} -> {}",
                            sp.getGameProfile().getName(), payload.autoSummonOnScroll());

                } catch (Throwable t) {
                    LOG.error("[FFPayloads] handleClientAutoSummonPref work failed safely (player={})",
                            sp.getGameProfile().getName(), t);
                }
            });

        } finally {
            ctx.setPacketHandled(true);
        }
    }
}
