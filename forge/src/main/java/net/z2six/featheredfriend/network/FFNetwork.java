package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import org.slf4j.Logger;
import net.z2six.featheredfriend.network.SealStampCarveResultPacket;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Forge SimpleChannel networking for FeatheredFriend.
 *
 * NOTE:
 * - This remains responsible for your gameplay networking (known players, seal GUI, raven naming, whistle, etc.)
 * - Server settings sync is handled separately in FFPayloads.java.
 */
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(Constants.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static final String CLIENT_HANDLER_CLASS =
            "net.z2six.featheredfriend.client.network.FFNetworkClientHandlers";

    private FFNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(FFNetwork::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(FFNetwork::registerMessagesOnce);
    }

    private static void registerMessagesOnce() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }

        try {
            AtomicInteger id = new AtomicInteger(0);

            // ---------------------------
            // C2S
            // ---------------------------

            CHANNEL.messageBuilder(RequestKnownPlayersPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RequestKnownPlayersPacket::encode)
                    .decoder(RequestKnownPlayersPacket::decode)
                    .consumerMainThread(FFNetwork::handleRequestKnownPlayersOnServer)
                    .add();

            CHANNEL.messageBuilder(WhistleForRavenPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(WhistleForRavenPacket::encode)
                    .decoder(WhistleForRavenPacket::decode)
                    .consumerMainThread(FFNetwork::handleWhistleForRavenOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenNameChosenPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenNameChosenPacket::encode)
                    .decoder(RavenNameChosenPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenNameChosenOnServer)
                    .add();

            // ✅ NEW: C2S stamp carving result
            CHANNEL.messageBuilder(SealStampCarveResultPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SealStampCarveResultPacket::encode)
                    .decoder(SealStampCarveResultPacket::decode)
                    .consumerMainThread(FFNetwork::handleSealStampCarveResultOnServer)
                    .add();

            // ---------------------------
            // S2C
            // ---------------------------

            CHANNEL.messageBuilder(KnownPlayersPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(KnownPlayersPayload::encode)
                    .decoder(KnownPlayersPayload::decode)
                    .consumerMainThread(FFNetwork::handleKnownPlayersOnClientProxy)
                    .add();

            CHANNEL.messageBuilder(OpenRavenNameScreenPayload.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(OpenRavenNameScreenPayload::encode)
                    .decoder(OpenRavenNameScreenPayload::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenNameScreenOnClientProxy)
                    .add();

            CHANNEL.messageBuilder(WaxSealPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder(WaxSealPacket::encode)
                    .decoder(WaxSealPacket::decode)
                    .consumerMainThread(FFNetwork::handleWaxSealOnServer)
                    .add();

            CHANNEL.messageBuilder(BreakSealPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                    .encoder((msg, buf) -> BreakSealPacket.encode(buf, msg)) // <-- swap args here
                    .decoder(BreakSealPacket::decode)
                    .consumerMainThread(FFNetwork::handleBreakSealOnServer)
                    .add();

            LOG.debug("[FFNetwork] Registered main messages OK (protocol={})", PROTOCOL_VERSION);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register main messages", t);
        }
    }

    private static void handleSealStampCarveResultOnServer(
            SealStampCarveResultPacket msg,
            Supplier<NetworkEvent.Context> ctxSup
    ) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            ctx.enqueueWork(() -> {
                try {
                    SealStampCarveResultPacket.handle(msg, sp);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed handling SealStampCarveResultPacket", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    // Kept for compatibility with older callsites
    public static void registerSimpleMessages() {
        // no-op: Forge registration happens via register(modBus) -> common setup
        LOG.debug("[FFNetwork] registerSimpleMessages() called; using SimpleChannel registration");
    }

    // ---------------------------
    // Client dispatch (dedicated-safe)
    // ---------------------------

    private static void handleKnownPlayersOnClientProxy(KnownPlayersPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ctx.enqueueWork(() -> {
                try {
                    dispatchToClientHandler("handleKnownPlayersOnClient", payload, ctx);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] handleKnownPlayersOnClientProxy failed", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleOpenRavenNameScreenOnClientProxy(OpenRavenNameScreenPayload payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ctx.enqueueWork(() -> {
                try {
                    dispatchToClientHandler("handleOpenRavenNameScreenOnClient", payload, ctx);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] handleOpenRavenNameScreenOnClientProxy failed", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void dispatchToClientHandler(String methodName, Object payload, NetworkEvent.Context context) {
        try {
            Class<?> cls = Class.forName(CLIENT_HANDLER_CLASS);

            // Preferred Forge signature: (PayloadType, NetworkEvent.Context)
            try {
                Method m = cls.getDeclaredMethod(methodName, payload.getClass(), NetworkEvent.Context.class);
                m.setAccessible(true);
                m.invoke(null, payload, context);
                return;
            } catch (NoSuchMethodException ignored) {
            }

            // Fallback: (PayloadType) if you prefer no context.
            try {
                Method m = cls.getDeclaredMethod(methodName, payload.getClass());
                m.setAccessible(true);
                m.invoke(null, payload);
                return;
            } catch (NoSuchMethodException ignored) {
            }

            LOG.error("[FFNetwork] Client handler method not found: {}.{}({})",
                    CLIENT_HANDLER_CLASS, methodName, payload.getClass().getName());

        } catch (ClassNotFoundException e) {
            LOG.debug("[FFNetwork] Client handler class not present (expected on dedicated server): {}", CLIENT_HANDLER_CLASS);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to dispatch to client handler {}.{}(...)",
                    CLIENT_HANDLER_CLASS, methodName, t);
        }
    }

    // ---------------------------
    // Server handlers
    // ---------------------------

    private static void handleRequestKnownPlayersOnServer(RequestKnownPlayersPacket payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer serverPlayer = ctx.getSender();
            if (serverPlayer == null) return;

            ctx.enqueueWork(() -> {
                try {
                    var server = serverPlayer.server;
                    if (server == null) {
                        LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: server is null for player={}",
                                serverPlayer.getGameProfile().getName());
                        return;
                    }

                    FFKnownPlayersData data = FFKnownPlayersData.get(server);
                    List<FFKnownPlayersData.KnownPlayer> players = data.getSortedPlayers();

                    sendKnownPlayersTo(serverPlayer, players);

                    LOG.debug("[FFNetwork] Served request_known_players to '{}' (count={})",
                            serverPlayer.getGameProfile().getName(),
                            players.size());

                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed to handle RequestKnownPlayersPacket on server", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleWhistleForRavenOnServer(WhistleForRavenPacket payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer serverPlayer = ctx.getSender();
            if (serverPlayer == null) return;

            ctx.enqueueWork(() -> {
                try {
                    LOG.debug("[FFNetwork] handleWhistleForRavenOnServer: whistle request from '{}'",
                            serverPlayer.getGameProfile().getName());

                    net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleWhistleSummonRequest(serverPlayer);

                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed to handle WhistleForRavenPacket on server", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleRavenNameChosenOnServer(RavenNameChosenPacket payload, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer serverPlayer = ctx.getSender();
            if (serverPlayer == null) return;

            ctx.enqueueWork(() -> {
                try {
                    RavenNameChosenPacket.handle(payload, serverPlayer);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    // ---------------------------
    // Send helpers
    // ---------------------------

    public static void sendSealStampCarveResultToServer(SealStampCarveResultPacket msg) {
        try {
            CHANNEL.sendToServer(msg);
            LOG.debug("[FFNetwork] Sent SealStampCarveResultPacket to server (slot={})", msg.stampSlot());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendSealStampCarveResultToServer failed", t);
        }
    }

    public static void sendRavenNameChosenToServer(int ravenEntityId, String name) {
        try {
            String safeName = name != null ? name : "";
            RavenNameChosenPacket p = new RavenNameChosenPacket(ravenEntityId, safeName);
            CHANNEL.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RavenNameChosenPacket to server (ravenEntityId={}, nameLen={})",
                    ravenEntityId, safeName.length());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenNameChosenToServer failed", t);
        }
    }

    public static void sendWhistleForRaven() {
        try {
            CHANNEL.sendToServer(new WhistleForRavenPacket());
            LOG.debug("[FFNetwork] Sent WhistleForRavenPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWhistleForRaven failed safely", t);
        }
    }

    public static void sendRequestKnownPlayersToServer() {
        try {
            CHANNEL.sendToServer(new RequestKnownPlayersPacket());
            LOG.debug("[FFNetwork] Sent RequestKnownPlayersPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRequestKnownPlayersToServer failed safely", t);
        }
    }

    public static void sendOpenRavenNamingScreen(ServerPlayer player, int ravenEntityId) {
        try {
            OpenRavenNameScreenPayload payload = new OpenRavenNameScreenPayload(ravenEntityId);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
            LOG.debug("[FFNetwork] Sent OpenRavenNameScreenPayload to {} for ravenEntityId={}",
                    player.getGameProfile().getName(), ravenEntityId);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenNamingScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    public static void sendKnownPlayersTo(ServerPlayer player, Collection<FFKnownPlayersData.KnownPlayer> players) {
        try {
            List<KnownPlayerInfo> copy = new ArrayList<>();
            for (FFKnownPlayersData.KnownPlayer kp : players) {
                if (kp == null || kp.uuid() == null || kp.name() == null || kp.name().isBlank()) continue;
                copy.add(new KnownPlayerInfo(kp.uuid(), kp.name()));
            }
            KnownPlayersPayload payload = new KnownPlayersPayload(copy);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
            LOG.debug("[FFNetwork] Sent {} known players to {}", copy.size(), player.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send KnownPlayersPayload to {}", player.getGameProfile().getName(), t);
        }
    }

    // ---------------------------
    // Messages
    // ---------------------------

    public record RequestKnownPlayersPacket() {
        static void encode(RequestKnownPlayersPacket msg, FriendlyByteBuf buf) {
            // no fields
        }
        static RequestKnownPlayersPacket decode(FriendlyByteBuf buf) {
            return new RequestKnownPlayersPacket();
        }
    }

    public record WhistleForRavenPacket() {
        static void encode(WhistleForRavenPacket msg, FriendlyByteBuf buf) {
            // no fields
        }
        static WhistleForRavenPacket decode(FriendlyByteBuf buf) {
            return new WhistleForRavenPacket();
        }
    }

    public record KnownPlayerInfo(UUID uuid, String name) {}

    public record KnownPlayersPayload(List<KnownPlayerInfo> players) {
        static void encode(KnownPlayersPayload msg, FriendlyByteBuf buf) {
            List<KnownPlayerInfo> list = msg.players != null ? msg.players : List.of();
            buf.writeVarInt(list.size());
            for (KnownPlayerInfo info : list) {
                if (info == null || info.uuid() == null || info.name() == null) {
                    buf.writeUUID(new UUID(0L, 0L));
                    buf.writeUtf("", 1024);
                    continue;
                }
                buf.writeUUID(info.uuid());
                buf.writeUtf(info.name(), 1024);
            }
        }

        static KnownPlayersPayload decode(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            if (size < 0) size = 0;
            if (size > 10000) {
                LOG.warn("[FFNetwork] KnownPlayersPayload decode: suspicious size={} (clamping to 10000)", size);
                size = 10000;
            }

            List<KnownPlayerInfo> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                UUID uuid = buf.readUUID();
                String name = buf.readUtf(1024);

                if (uuid == null) continue;
                if (name == null || name.isBlank()) continue;
                if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) continue;

                list.add(new KnownPlayerInfo(uuid, name));
            }
            return new KnownPlayersPayload(list);
        }
    }

    public record OpenRavenNameScreenPayload(int ravenEntityId) {
        static void encode(OpenRavenNameScreenPayload msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.ravenEntityId);
        }
        static OpenRavenNameScreenPayload decode(FriendlyByteBuf buf) {
            return new OpenRavenNameScreenPayload(buf.readVarInt());
        }
    }

    // ---------------------------------------------------------------------
    // The following send helpers exist in your original file but depend on
    // additional packet classes you haven't provided yet.
    // Keep them here if those packet classes exist in your project; we'll
    // port those packet classes later so sending is safe.
    // ---------------------------------------------------------------------

    public static void sendWaxSealToServer(
            int stampSlot,
            String dateText,
            String recipientName,
            String recipientUUID,
            String recipientText,
            String messageText,
            String signatureText,
            long seed,
            int slices,
            int style,
            String senderName
    ) {
        // left as in your original design: depends on WaxSealPacket class
        WaxSealPacket p = new WaxSealPacket(
                stampSlot,
                dateText != null ? dateText : "",
                recipientName != null ? recipientName : "",
                recipientUUID != null ? recipientUUID : "",
                recipientText != null ? recipientText : "",
                messageText != null ? messageText : "",
                signatureText != null ? signatureText : "",
                seed,
                slices,
                style,
                senderName != null ? senderName : ""
        );
        CHANNEL.sendToServer(p);
    }

    public static void sendBreakSealToServer(int slotHint,
                                             long seed,
                                             String recipientUUID,
                                             String dateText,
                                             String senderName) {
        BreakSealPacket p = new BreakSealPacket(
                slotHint,
                seed,
                recipientUUID != null ? recipientUUID : "",
                dateText != null ? dateText : "",
                senderName != null ? senderName : ""
        );
        CHANNEL.sendToServer(p);
    }

    private static void handleWaxSealOnServer(WaxSealPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            ctx.enqueueWork(() -> {
                try {
                    WaxSealPacket.handle(msg, sp);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed handling WaxSealPacket", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

    private static void handleBreakSealOnServer(BreakSealPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        try {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            ctx.enqueueWork(() -> {
                try {
                    BreakSealPacket.handle(msg, sp);
                } catch (Throwable t) {
                    LOG.error("[FFNetwork] Failed handling BreakSealPacket", t);
                }
            });
        } finally {
            ctx.setPacketHandled(true);
        }
    }

}
