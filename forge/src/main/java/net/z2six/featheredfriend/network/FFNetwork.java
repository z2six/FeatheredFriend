package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeBaseState;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeEventType;
import net.z2six.featheredfriend.config.FFServerConfig;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import net.z2six.featheredfriend.world.MailboxRegistryData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static final ResourceLocation CHANNEL_ID = new ResourceLocation(Constants.MOD_ID, "main");

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static boolean REGISTERED = false;

    private FFNetwork() {
    }

    // --- register ---
    public static void registerSimpleMessages() {
        try {
            if (REGISTERED) return;
            REGISTERED = true;

            int id = 0;

            // -----------------
            // C2S (client -> server)
            // -----------------
            CHANNEL.messageBuilder(RequestKnownPlayersPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RequestKnownPlayersPacket::encode)
                    .decoder(RequestKnownPlayersPacket::decode)
                    .consumerMainThread(FFNetwork::handleRequestKnownPlayersOnServer)
                    .add();

            CHANNEL.messageBuilder(SealStampCarveResultPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SealStampCarveResultPacket::encode)
                    .decoder(SealStampCarveResultPacket::decode)
                    .consumerMainThread(FFNetwork::handleSealStampCarveResultOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenNameChosenPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenNameChosenPacket::encode)
                    .decoder(RavenNameChosenPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenNameChosenOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenNameCancelledPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenNameCancelledPacket::encode)
                    .decoder(RavenNameCancelledPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenNameCancelledOnServer)
                    .add();

            CHANNEL.messageBuilder(WaxSealPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(WaxSealPacket::encode)
                    .decoder(WaxSealPacket::decode)
                    .consumerMainThread(FFNetwork::handleWaxSealOnServer)
                    .add();

            CHANNEL.messageBuilder(BreakSealPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(BreakSealPacket::encode)
                    .decoder(BreakSealPacket::decode)
                    .consumerMainThread(FFNetwork::handleBreakSealOnServer)
                    .add();

            CHANNEL.messageBuilder(WhistleForRavenPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(WhistleForRavenPacket::encode)
                    .decoder(WhistleForRavenPacket::decode)
                    .consumerMainThread(FFNetwork::handleWhistleForRavenOnServer)
                    .add();

            CHANNEL.messageBuilder(OpenEnderpackRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(OpenEnderpackRequestPacket::encode)
                    .decoder(OpenEnderpackRequestPacket::decode)
                    .consumerMainThread(FFNetwork::handleOpenEnderpackRequestOnServer)
                    .add();

            CHANNEL.messageBuilder(OpenRavenLogRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(OpenRavenLogRequestPacket::encode)
                    .decoder(OpenRavenLogRequestPacket::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenLogRequestOnServer)
                    .add();

            CHANNEL.messageBuilder(ClearRavenLogRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(ClearRavenLogRequestPacket::encode)
                    .decoder(ClearRavenLogRequestPacket::decode)
                    .consumerMainThread(FFNetwork::handleClearRavenLogRequestOnServer)
                    .add();

            CHANNEL.messageBuilder(SetRavenChestLabelPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(SetRavenChestLabelPacket::encode)
                    .decoder(SetRavenChestLabelPacket::decode)
                    .consumerMainThread(FFNetwork::handleSetRavenChestLabelOnServer)
                    .add();

            CHANNEL.messageBuilder(ConfirmRavenChestDepositPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(ConfirmRavenChestDepositPacket::encode)
                    .decoder(ConfirmRavenChestDepositPacket::decode)
                    .consumerMainThread(FFNetwork::handleConfirmRavenChestDepositOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenLinkInputPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenLinkInputPacket::encode)
                    .decoder(RavenLinkInputPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenLinkInputOnServer)
                    .add();

            CHANNEL.messageBuilder(StopRavenLinkRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(StopRavenLinkRequestPacket::encode)
                    .decoder(StopRavenLinkRequestPacket::decode)
                    .consumerMainThread(FFNetwork::handleStopRavenLinkRequestOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenLinkBlackoutAckPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenLinkBlackoutAckPacket::encode)
                    .decoder(RavenLinkBlackoutAckPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenLinkBlackoutAckOnServer)
                    .add();

            CHANNEL.messageBuilder(RavenLinkEffigyPoseSnapshotPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                    .encoder(RavenLinkEffigyPoseSnapshotPacket::encode)
                    .decoder(RavenLinkEffigyPoseSnapshotPacket::decode)
                    .consumerMainThread(FFNetwork::handleRavenLinkEffigyPoseSnapshotOnServer)
                    .add();

            // -----------------
            // S2C (server -> client)
            // -----------------
            CHANNEL.messageBuilder(KnownPlayersPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(KnownPlayersPayload::encode)
                    .decoder(KnownPlayersPayload::decode)
                    .consumerMainThread(FFNetwork::handleKnownPlayersOnClient)
                    .add();

            CHANNEL.messageBuilder(OpenRavenNameScreenPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(OpenRavenNameScreenPayload::encode)
                    .decoder(OpenRavenNameScreenPayload::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenNameScreenOnClient)
                    .add();

            CHANNEL.messageBuilder(OpenRavenLogScreenPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(OpenRavenLogScreenPayload::encode)
                    .decoder(OpenRavenLogScreenPayload::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenLogScreenOnClient)
                    .add();

            CHANNEL.messageBuilder(OpenRavenChestLabelScreenPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(OpenRavenChestLabelScreenPayload::encode)
                    .decoder(OpenRavenChestLabelScreenPayload::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenChestLabelScreenOnClient)
                    .add();

            CHANNEL.messageBuilder(OpenRavenChestSelectScreenPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(OpenRavenChestSelectScreenPayload::encode)
                    .decoder(OpenRavenChestSelectScreenPayload::decode)
                    .consumerMainThread(FFNetwork::handleOpenRavenChestSelectScreenOnClient)
                    .add();

            CHANNEL.messageBuilder(StartRavenLinkPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(StartRavenLinkPayload::encode)
                    .decoder(StartRavenLinkPayload::decode)
                    .consumerMainThread(FFNetwork::handleStartRavenLinkOnClient)
                    .add();

            CHANNEL.messageBuilder(StopRavenLinkPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(StopRavenLinkPayload::encode)
                    .decoder(StopRavenLinkPayload::decode)
                    .consumerMainThread(FFNetwork::handleStopRavenLinkOnClient)
                    .add();

            CHANNEL.messageBuilder(BeginRavenLinkEndPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(BeginRavenLinkEndPayload::encode)
                    .decoder(BeginRavenLinkEndPayload::decode)
                    .consumerMainThread(FFNetwork::handleBeginRavenLinkEndOnClient)
                    .add();

            CHANNEL.messageBuilder(RavenLinkOwnerVisibilityPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(RavenLinkOwnerVisibilityPayload::encode)
                    .decoder(RavenLinkOwnerVisibilityPayload::decode)
                    .consumerMainThread(FFNetwork::handleRavenLinkOwnerVisibilityOnClient)
                    .add();

            CHANNEL.messageBuilder(RavenLinkStatePayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(RavenLinkStatePayload::encode)
                    .decoder(RavenLinkStatePayload::decode)
                    .consumerMainThread(FFNetwork::handleRavenLinkStateOnClient)
                    .add();

            CHANNEL.messageBuilder(RavenBadgeStatusPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(RavenBadgeStatusPayload::encode)
                    .decoder(RavenBadgeStatusPayload::decode)
                    .consumerMainThread(FFNetwork::handleRavenBadgeStatusOnClient)
                    .add();

            LOG.debug("[FFNetwork] Registered {} messages on SimpleChannel {}", id, CHANNEL_ID);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] registerSimpleMessages() failed", t);
        }
    }

    // --- handlers (S2C) ---

    private static void handleKnownPlayersOnClient(@NotNull KnownPlayersPayload payload,
                                                   @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleKnownPlayersOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleOpenRavenNameScreenOnClient(@NotNull OpenRavenNameScreenPayload payload,
                                                          @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleOpenRavenNameScreenOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleOpenRavenLogScreenOnClient(@NotNull OpenRavenLogScreenPayload payload,
                                                         @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleOpenRavenLogScreenOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleOpenRavenChestLabelScreenOnClient(@NotNull OpenRavenChestLabelScreenPayload payload,
                                                                @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleOpenRavenChestLabelScreenOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleOpenRavenChestSelectScreenOnClient(@NotNull OpenRavenChestSelectScreenPayload payload,
                                                                 @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleOpenRavenChestSelectScreenOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleStartRavenLinkOnClient(@NotNull StartRavenLinkPayload payload,
                                                     @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleStartRavenLinkOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleStopRavenLinkOnClient(@NotNull StopRavenLinkPayload payload,
                                                    @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleStopRavenLinkOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleBeginRavenLinkEndOnClient(@NotNull BeginRavenLinkEndPayload payload,
                                                        @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleBeginRavenLinkEndOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleRavenLinkOwnerVisibilityOnClient(@NotNull RavenLinkOwnerVisibilityPayload payload,
                                                               @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleRavenLinkOwnerVisibilityOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleRavenLinkStateOnClient(@NotNull RavenLinkStatePayload payload,
                                                     @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleRavenLinkStateOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    private static void handleRavenBadgeStatusOnClient(@NotNull RavenBadgeStatusPayload payload,
                                                       @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.z2six.featheredfriend.client.network.FFNetworkClientHandlers.handleRavenBadgeStatusOnClient(payload)
        ));
        ctx.setPacketHandled(true);
    }

    // --- handlers (C2S) ---

    private static void handleRequestKnownPlayersOnServer(@NotNull RequestKnownPlayersPacket payload,
                                                          @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: ctx.getSender() is null");
                    return;
                }
                MinecraftServer server = serverPlayer.getServer();
                if (server == null) {
                    LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: server is null for player={}",
                            serverPlayer.getGameProfile().getName());
                    return;
                }
                FFKnownPlayersData data = FFKnownPlayersData.get(server);
                sendKnownPlayersTo(serverPlayer, data.getSortedPlayers());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RequestKnownPlayersPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleWhistleForRavenOnServer(@NotNull WhistleForRavenPacket payload,
                                                      @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleWhistleForRavenOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleWhistleSummonRequest(serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WhistleForRavenPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleOpenEnderpackRequestOnServer(@NotNull OpenEnderpackRequestPacket payload,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleOpenEnderpackRequestOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.platform.Services.PLATFORM.openEnderpackScreen(serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle OpenEnderpackRequestPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleOpenRavenLogRequestOnServer(@NotNull OpenRavenLogRequestPacket payload,
                                                          @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleOpenRavenLogRequestOnServer: ctx.getSender() is null");
                    return;
                }
                sendOpenRavenLogScreen(serverPlayer, net.z2six.featheredfriend.world.RavenLogService.getEntriesForPlayer(serverPlayer));
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle OpenRavenLogRequestPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleClearRavenLogRequestOnServer(@NotNull ClearRavenLogRequestPacket payload,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleClearRavenLogRequestOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.RavenLogService.clearForPlayer(serverPlayer);
                sendOpenRavenLogScreen(serverPlayer, net.z2six.featheredfriend.world.RavenLogService.getEntriesForPlayer(serverPlayer));
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle ClearRavenLogRequestPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleSetRavenChestLabelOnServer(@NotNull SetRavenChestLabelPacket payload,
                                                         @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleSetRavenChestLabelOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleRavenChestLabelSubmission(
                        serverPlayer,
                        payload.dimensionId(),
                        payload.blockPos(),
                        payload.label()
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SetRavenChestLabelPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleConfirmRavenChestDepositOnServer(@NotNull ConfirmRavenChestDepositPacket payload,
                                                               @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleConfirmRavenChestDepositOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleConfirmRavenChestDeposit(
                        serverPlayer,
                        payload.ravenEntityId(),
                        payload.dimensionId(),
                        payload.blockPos(),
                        RavenChestSelectAction.fromId(payload.actionId())
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle ConfirmRavenChestDepositPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRavenLinkInputOnServer(@NotNull RavenLinkInputPacket payload,
                                                     @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRavenLinkInputOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.RavenLinkRuntime.handleLinkInput(
                        serverPlayer,
                        payload.forward(),
                        payload.backward(),
                        payload.left(),
                        payload.right(),
                        payload.ascend(),
                        payload.descend(),
                        payload.yaw(),
                        payload.pitch()
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenLinkInputPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleStopRavenLinkRequestOnServer(@NotNull StopRavenLinkRequestPacket payload,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleStopRavenLinkRequestOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.RavenLinkRuntime.stopLinkForOwner(serverPlayer, "client_stop");
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle StopRavenLinkRequestPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRavenLinkBlackoutAckOnServer(@NotNull RavenLinkBlackoutAckPacket payload,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRavenLinkBlackoutAckOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.RavenLinkRuntime.handleClientBlackoutAck(serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenLinkBlackoutAckPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRavenLinkEffigyPoseSnapshotOnServer(@NotNull RavenLinkEffigyPoseSnapshotPacket payload,
                                                                  @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRavenLinkEffigyPoseSnapshotOnServer: ctx.getSender() is null");
                    return;
                }
                net.z2six.featheredfriend.world.RavenLinkRuntime.handleEffigyPoseSnapshot(serverPlayer, payload);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenLinkEffigyPoseSnapshotPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleSealStampCarveResultOnServer(@NotNull SealStampCarveResultPacket payload,
                                                           @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleSealStampCarveResultOnServer: ctx.getSender() is null");
                    return;
                }
                SealStampCarveResultPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SealStampCarveResultPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleWaxSealOnServer(@NotNull WaxSealPacket payload,
                                              @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleWaxSealOnServer: ctx.getSender() is null");
                    return;
                }
                WaxSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WaxSealPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleBreakSealOnServer(@NotNull BreakSealPacket payload,
                                                @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleBreakSealOnServer: ctx.getSender() is null");
                    return;
                }
                BreakSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle BreakSealPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRavenNameChosenOnServer(@NotNull RavenNameChosenPacket payload,
                                                      @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRavenNameChosenOnServer: ctx.getSender() is null");
                    return;
                }
                RavenNameChosenPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRavenNameCancelledOnServer(@NotNull RavenNameCancelledPacket payload,
                                                         @NotNull Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            try {
                ServerPlayer serverPlayer = ctx.getSender();
                if (serverPlayer == null) {
                    LOG.error("[FFNetwork] handleRavenNameCancelledOnServer: ctx.getSender() is null");
                    return;
                }
                RavenNameCancelledPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameCancelledPacket on server", t);
            }
        });
        ctx.setPacketHandled(true);
    }

    // --- send helpers ---

    public static void sendRavenNameChosenToServer(int ravenEntityId, @NotNull String name) {
        try {
            CHANNEL.sendToServer(new RavenNameChosenPacket(ravenEntityId, name != null ? name : ""));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenNameChosenToServer failed", t);
        }
    }

    public static void sendRavenNameCancelledToServer(int ravenEntityId) {
        try {
            CHANNEL.sendToServer(new RavenNameCancelledPacket(ravenEntityId));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenNameCancelledToServer failed", t);
        }
    }

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
        try {
            CHANNEL.sendToServer(new WaxSealPacket(
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
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWaxSealToServer failed", t);
        }
    }

    public static void sendBreakSealToServer(int slotHint,
                                             long seed,
                                             @NotNull String recipientUUID,
                                             @NotNull String dateText,
                                             @NotNull String senderName) {
        try {
            CHANNEL.sendToServer(new BreakSealPacket(
                    slotHint,
                    seed,
                    recipientUUID != null ? recipientUUID : "",
                    dateText != null ? dateText : "",
                    senderName != null ? senderName : ""
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendBreakSealToServer failed", t);
        }
    }

    public static void sendOpenRavenNamingScreen(@NotNull ServerPlayer player, int ravenEntityId) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenRavenNameScreenPayload(ravenEntityId));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenNamingScreen failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendOpenRavenChestLabelScreen(@NotNull ServerPlayer player,
                                                     @NotNull String dimensionId,
                                                     long blockPos,
                                                     @NotNull String currentLabel) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenRavenChestLabelScreenPayload(dimensionId, blockPos, currentLabel));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenChestLabelScreen failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendOpenRavenLogScreen(@NotNull ServerPlayer player, @NotNull List<RavenLogEntryInfo> entries) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenRavenLogScreenPayload(entries == null ? List.of() : entries));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenLogScreen failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                                      int ravenEntityId,
                                                      @NotNull List<RavenChestChoiceInfo> choices) {
        sendOpenRavenChestSelectScreen(player, ravenEntityId, choices, RavenChestSelectAction.ENDERPACK_DEPOSIT);
    }

    public static void sendOpenRavenChestSelectScreen(@NotNull ServerPlayer player,
                                                      int ravenEntityId,
                                                      @NotNull List<RavenChestChoiceInfo> choices,
                                                      @NotNull RavenChestSelectAction action) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenRavenChestSelectScreenPayload(
                            ravenEntityId,
                            choices,
                            action == null ? RavenChestSelectAction.ENDERPACK_DEPOSIT.id() : action.id()
                    ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenChestSelectScreen failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendWhistleForRaven() {
        try {
            CHANNEL.sendToServer(new WhistleForRavenPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWhistleForRaven failed safely", t);
        }
    }

    public static void sendRequestKnownPlayersToServer() {
        try {
            CHANNEL.sendToServer(new RequestKnownPlayersPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRequestKnownPlayersToServer failed safely", t);
        }
    }

    public static void sendOpenEnderpackToServer() {
        try {
            CHANNEL.sendToServer(new OpenEnderpackRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenEnderpackToServer failed safely", t);
        }
    }

    public static void sendOpenRavenLogToServer() {
        try {
            CHANNEL.sendToServer(new OpenRavenLogRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenLogToServer failed safely", t);
        }
    }

    public static void sendClearRavenLogToServer() {
        try {
            CHANNEL.sendToServer(new ClearRavenLogRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendClearRavenLogToServer failed safely", t);
        }
    }

    public static void sendSetRavenChestLabelToServer(@NotNull String dimensionId, long blockPos, @NotNull String label) {
        try {
            CHANNEL.sendToServer(new SetRavenChestLabelPacket(dimensionId, blockPos, label));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendSetRavenChestLabelToServer failed safely", t);
        }
    }

    public static void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                            @NotNull String dimensionId,
                                                            long blockPos) {
        sendConfirmRavenChestDepositToServer(ravenEntityId, dimensionId, blockPos, RavenChestSelectAction.ENDERPACK_DEPOSIT);
    }

    public static void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                            @NotNull String dimensionId,
                                                            long blockPos,
                                                            @NotNull RavenChestSelectAction action) {
        try {
            CHANNEL.sendToServer(new ConfirmRavenChestDepositPacket(
                    ravenEntityId,
                    dimensionId,
                    blockPos,
                    action == null ? RavenChestSelectAction.ENDERPACK_DEPOSIT.id() : action.id()
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendConfirmRavenChestDepositToServer failed safely", t);
        }
    }

    public static void sendStartRavenLink(@NotNull ServerPlayer player,
                                          int ravenEntityId,
                                          int durationTicks,
                                          double anchorX,
                                          double anchorY,
                                          double anchorZ,
                                          float anchorYaw,
                                          float anchorPitch) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StartRavenLinkPayload(
                    ravenEntityId,
                    durationTicks,
                    anchorX,
                    anchorY,
                    anchorZ,
                    anchorYaw,
                    anchorPitch
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendStartRavenLink failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendStopRavenLink(@NotNull ServerPlayer player) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StopRavenLinkPayload());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendStopRavenLink failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendBeginRavenLinkEnd(@NotNull ServerPlayer player) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BeginRavenLinkEndPayload());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendBeginRavenLinkEnd failed for player={}", player.getGameProfile().getName(), t);
        }
    }

    public static void sendRavenLinkBlackoutAckToServer() {
        try {
            CHANNEL.sendToServer(new RavenLinkBlackoutAckPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenLinkBlackoutAckToServer failed safely", t);
        }
    }

    public static void sendRavenLinkEffigyPoseSnapshotToServer() {
        try {
            Class<?> cls = Class.forName("net.z2six.featheredfriend.client.raven.RavenLinkEffigyPoseCapture");
            boolean ok = (boolean) cls.getDeclaredMethod("hasSnapshot").invoke(null);
            if (!ok) {
                return;
            }

            float headXRot = ((Number) cls.getDeclaredMethod("headXRot").invoke(null)).floatValue();
            float headYRot = ((Number) cls.getDeclaredMethod("headYRot").invoke(null)).floatValue();
            float headZRot = ((Number) cls.getDeclaredMethod("headZRot").invoke(null)).floatValue();
            float bodyXRot = ((Number) cls.getDeclaredMethod("bodyXRot").invoke(null)).floatValue();
            float bodyYRot = ((Number) cls.getDeclaredMethod("bodyYRot").invoke(null)).floatValue();
            float bodyZRot = ((Number) cls.getDeclaredMethod("bodyZRot").invoke(null)).floatValue();
            float rightArmXRot = ((Number) cls.getDeclaredMethod("rightArmXRot").invoke(null)).floatValue();
            float rightArmYRot = ((Number) cls.getDeclaredMethod("rightArmYRot").invoke(null)).floatValue();
            float rightArmZRot = ((Number) cls.getDeclaredMethod("rightArmZRot").invoke(null)).floatValue();
            float leftArmXRot = ((Number) cls.getDeclaredMethod("leftArmXRot").invoke(null)).floatValue();
            float leftArmYRot = ((Number) cls.getDeclaredMethod("leftArmYRot").invoke(null)).floatValue();
            float leftArmZRot = ((Number) cls.getDeclaredMethod("leftArmZRot").invoke(null)).floatValue();
            float rightLegXRot = ((Number) cls.getDeclaredMethod("rightLegXRot").invoke(null)).floatValue();
            float rightLegYRot = ((Number) cls.getDeclaredMethod("rightLegYRot").invoke(null)).floatValue();
            float rightLegZRot = ((Number) cls.getDeclaredMethod("rightLegZRot").invoke(null)).floatValue();
            float leftLegXRot = ((Number) cls.getDeclaredMethod("leftLegXRot").invoke(null)).floatValue();
            float leftLegYRot = ((Number) cls.getDeclaredMethod("leftLegYRot").invoke(null)).floatValue();
            float leftLegZRot = ((Number) cls.getDeclaredMethod("leftLegZRot").invoke(null)).floatValue();

            CHANNEL.sendToServer(new RavenLinkEffigyPoseSnapshotPacket(
                    headXRot, headYRot, headZRot,
                    bodyXRot, bodyYRot, bodyZRot,
                    rightArmXRot, rightArmYRot, rightArmZRot,
                    leftArmXRot, leftArmYRot, leftArmZRot,
                    rightLegXRot, rightLegYRot, rightLegZRot,
                    leftLegXRot, leftLegYRot, leftLegZRot
            ));
        } catch (Throwable ignored) {
        }
    }

    public static void sendRavenLinkOwnerVisibilityToAll(@NotNull MinecraftServer server,
                                                         int ownerEntityId,
                                                         boolean hidden) {
        try {
            if (server == null) return;
            CHANNEL.send(PacketDistributor.ALL.noArg(), new RavenLinkOwnerVisibilityPayload(ownerEntityId, hidden));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenLinkOwnerVisibilityToAll failed", t);
        }
    }

    public static void sendRavenLinkOwnerVisibilityToPlayer(@NotNull ServerPlayer targetPlayer,
                                                            int ownerEntityId,
                                                            boolean hidden) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> targetPlayer),
                    new RavenLinkOwnerVisibilityPayload(ownerEntityId, hidden));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenLinkOwnerVisibilityToPlayer failed", t);
        }
    }

    public static void sendRavenLinkState(@NotNull ServerPlayer player,
                                          int ravenEntityId,
                                          double x,
                                          double y,
                                          double z,
                                          float yaw,
                                          float pitch,
                                          int chunksSentThisTick,
                                          int chunksPending,
                                          int chunksLoaded,
                                          int streamRadius) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RavenLinkStatePayload(
                    ravenEntityId,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    chunksSentThisTick,
                    chunksPending,
                    chunksLoaded,
                    streamRadius
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenLinkState failed", t);
        }
    }

    public static void sendRavenBadgeStatus(@NotNull ServerPlayer player,
                                            @NotNull RavenBadgeBaseState baseState,
                                            @NotNull RavenBadgeEventType eventType) {
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RavenBadgeStatusPayload(
                    baseState == null ? RavenBadgeBaseState.IDLE_NO_SCROLL.id() : baseState.id(),
                    eventType == null ? RavenBadgeEventType.NONE.id() : eventType.id()
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenBadgeStatus failed", t);
        }
    }

    public static void sendRavenLinkInputToServer(boolean forward,
                                                  boolean backward,
                                                  boolean left,
                                                  boolean right,
                                                  boolean ascend,
                                                  boolean descend,
                                                  float yaw,
                                                  float pitch) {
        try {
            CHANNEL.sendToServer(new RavenLinkInputPacket(
                    forward,
                    backward,
                    left,
                    right,
                    ascend,
                    descend,
                    yaw,
                    pitch
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenLinkInputToServer failed safely", t);
        }
    }

    public static void sendStopRavenLinkRequestToServer() {
        try {
            CHANNEL.sendToServer(new StopRavenLinkRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendStopRavenLinkRequestToServer failed safely", t);
        }
    }

    public static void sendKnownPlayersTo(@NotNull ServerPlayer player,
                                          @NotNull Collection<FFKnownPlayersData.KnownPlayer> players) {
        try {
            boolean mailboxEnabled = FFServerConfig.isMailboxEnabled();
            MailboxRegistryData mailboxRegistry = mailboxEnabled ? MailboxRegistryData.get(player.serverLevel()) : null;
            UUID observerUuid = player.getUUID();

            List<KnownPlayerInfo> copy = new ArrayList<>();
            for (FFKnownPlayersData.KnownPlayer kp : players) {
                if (kp == null || kp.uuid() == null || kp.name() == null || kp.name().isBlank()) continue;
                int mailboxCount = 0;
                try {
                    if (mailboxEnabled && mailboxRegistry != null) {
                        mailboxCount = mailboxRegistry.getKnownMailboxCount(observerUuid, kp.uuid());
                    }
                } catch (Throwable ignored) {
                }
                copy.add(new KnownPlayerInfo(kp.uuid(), kp.name(), Math.max(0, mailboxCount)));
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new KnownPlayersPayload(copy));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send KnownPlayersPayload to {}", player.getGameProfile().getName(), t);
        }
    }
}
