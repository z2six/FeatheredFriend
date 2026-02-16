// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/FFNetwork.java
package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.data.FFKnownPlayersData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Payload-based networking for FeatheredFriend.
 *
 * NOTE:
 * - This file remains responsible for your legacy/gameplay payloads (known players, seal GUI, raven naming, whistle, etc.)
 * - Server settings sync is handled separately in FFPayloads.java to avoid ID collisions and keep concerns isolated.
 */
public final class FFNetwork {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String CLIENT_HANDLER_CLASS =
            "net.z2six.featheredfriend.client.network.FFNetworkClientHandlers";

    private FFNetwork() {
    }

    static {
        try {
            LOG.debug("[FFNetwork] Class loaded");
        } catch (Throwable ignored) {
        }
    }

    public static void registerSimpleMessages() {
        try {
            LOG.debug("[FFNetwork] registerSimpleMessages() called; payload-based networking is used (no-op)");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] registerSimpleMessages() failed (no-op stub)", t);
        }
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        try {
            LOG.debug("[FFNetwork] RegisterPayloadHandlersEvent received -> registering payloads");
            var registrar = event.registrar("1");

            registrar.playToClient(
                    KnownPlayersPayload.TYPE,
                    KnownPlayersPayload.STREAM_CODEC,
                    FFNetwork::handleKnownPlayersOnClientProxy
            );

            registrar.playToClient(
                    OpenRavenNameScreenPayload.TYPE,
                    OpenRavenNameScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenNameScreenOnClientProxy
            );

            registrar.playToClient(
                    OpenRavenLogScreenPayload.TYPE,
                    OpenRavenLogScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenLogScreenOnClientProxy
            );

            registrar.playToClient(
                    OpenRavenChestLabelScreenPayload.TYPE,
                    OpenRavenChestLabelScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenChestLabelScreenOnClientProxy
            );

            registrar.playToClient(
                    OpenRavenChestSelectScreenPayload.TYPE,
                    OpenRavenChestSelectScreenPayload.STREAM_CODEC,
                    FFNetwork::handleOpenRavenChestSelectScreenOnClientProxy
            );

            registrar.playToServer(
                    RequestKnownPlayersPacket.TYPE,
                    RequestKnownPlayersPacket.STREAM_CODEC,
                    FFNetwork::handleRequestKnownPlayersOnServer
            );

            registrar.playToServer(
                    SealStampCarveResultPacket.TYPE,
                    SealStampCarveResultPacket.STREAM_CODEC,
                    FFNetwork::handleSealStampCarveResultOnServer
            );

            registrar.playToServer(
                    WaxSealPacket.TYPE,
                    WaxSealPacket.STREAM_CODEC,
                    FFNetwork::handleWaxSealOnServer
            );

            registrar.playToServer(
                    BreakSealPacket.TYPE,
                    BreakSealPacket.STREAM_CODEC,
                    FFNetwork::handleBreakSealOnServer
            );

            registrar.playToServer(
                    RavenNameChosenPacket.TYPE,
                    RavenNameChosenPacket.STREAM_CODEC,
                    FFNetwork::handleRavenNameChosenOnServer
            );

            registrar.playToServer(
                    RavenNameCancelledPacket.TYPE,
                    RavenNameCancelledPacket.STREAM_CODEC,
                    FFNetwork::handleRavenNameCancelledOnServer
            );

            registrar.playToServer(
                    WhistleForRavenPacket.TYPE,
                    WhistleForRavenPacket.STREAM_CODEC,
                    FFNetwork::handleWhistleForRavenOnServer
            );

            registrar.playToServer(
                    OpenEnderpackRequestPacket.TYPE,
                    OpenEnderpackRequestPacket.STREAM_CODEC,
                    FFNetwork::handleOpenEnderpackOnServer
            );

            registrar.playToServer(
                    OpenRavenLogRequestPacket.TYPE,
                    OpenRavenLogRequestPacket.STREAM_CODEC,
                    FFNetwork::handleOpenRavenLogOnServer
            );

            registrar.playToServer(
                    ClearRavenLogRequestPacket.TYPE,
                    ClearRavenLogRequestPacket.STREAM_CODEC,
                    FFNetwork::handleClearRavenLogOnServer
            );

            registrar.playToServer(
                    SetRavenChestLabelPacket.TYPE,
                    SetRavenChestLabelPacket.STREAM_CODEC,
                    FFNetwork::handleSetRavenChestLabelOnServer
            );

            registrar.playToServer(
                    ConfirmRavenChestDepositPacket.TYPE,
                    ConfirmRavenChestDepositPacket.STREAM_CODEC,
                    FFNetwork::handleConfirmRavenChestDepositOnServer
            );

            LOG.debug("[FFNetwork] Registered payload channels: known_players, request_known_players, open_raven_name_screen, open_raven_log_screen, " +
                    "seal_stamp_carve_result, wax_seal, break_seal, " +
                    "raven_name_chosen, raven_name_cancelled, whistle_for_raven, open_enderpack_request, open_raven_log_request, clear_raven_log_request, " +
                    "open_raven_chest_label_screen, open_raven_chest_select_screen, set_raven_chest_label, confirm_raven_chest_deposit");

        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to register payload handlers", t);
        }
    }

    // ---------------------------
    // Client dispatch (dedicated-safe)
    // ---------------------------

    private static void handleKnownPlayersOnClientProxy(@NotNull KnownPlayersPayload payload,
                                                        @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleKnownPlayersOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleKnownPlayersOnClientProxy failed", t);
            }
        });
    }

    private static void handleOpenRavenNameScreenOnClientProxy(@NotNull OpenRavenNameScreenPayload payload,
                                                               @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleOpenRavenNameScreenOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleOpenRavenNameScreenOnClientProxy failed", t);
            }
        });
    }

    private static void handleOpenRavenLogScreenOnClientProxy(@NotNull OpenRavenLogScreenPayload payload,
                                                               @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleOpenRavenLogScreenOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleOpenRavenLogScreenOnClientProxy failed", t);
            }
        });
    }

    private static void dispatchToClientHandler(@NotNull String methodName,
                                                @NotNull Object payload,
                                                @NotNull IPayloadContext context) {
        try {
            Class<?> cls = Class.forName(CLIENT_HANDLER_CLASS);
            Method m = cls.getDeclaredMethod(methodName, payload.getClass(), IPayloadContext.class);
            m.setAccessible(true);
            m.invoke(null, payload, context);
        } catch (ClassNotFoundException e) {
            LOG.debug("[FFNetwork] Client handler class not present (expected on dedicated server): {}", CLIENT_HANDLER_CLASS);
        } catch (NoSuchMethodException e) {
            LOG.error("[FFNetwork] Client handler method not found: {}.{}({}, {})",
                    CLIENT_HANDLER_CLASS, methodName, payload.getClass().getName(), IPayloadContext.class.getName(), e);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to dispatch to client handler {}.{}(...)",
                    CLIENT_HANDLER_CLASS, methodName, t);
        }
    }

    // ---------------------------
    // Server handlers
    // ---------------------------

    private static void handleRequestKnownPlayersOnServer(@NotNull RequestKnownPlayersPacket payload,
                                                          @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRequestKnownPlayersOnServer: context.player() is not a ServerPlayer");
                    return;
                }

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
    }

    private static void handleWhistleForRavenOnServer(@NotNull WhistleForRavenPacket payload,
                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleWhistleForRavenOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                LOG.debug("[FFNetwork] handleWhistleForRavenOnServer: whistle request from '{}'",
                        serverPlayer.getGameProfile().getName());

                net.z2six.featheredfriend.world.TamedRavenScrollWatcher.handleWhistleSummonRequest(serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WhistleForRavenPacket on server", t);
            }
        });
    }

    private static void handleOpenEnderpackOnServer(@NotNull OpenEnderpackRequestPacket payload,
                                                    @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleOpenEnderpackOnServer: context.player() is not a ServerPlayer");
                    return;
                }
                net.z2six.featheredfriend.platform.Services.PLATFORM.openEnderpackScreen(serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle OpenEnderpackRequestPacket on server", t);
            }
        });
    }

    private static void handleOpenRavenLogOnServer(@NotNull OpenRavenLogRequestPacket payload,
                                                   @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleOpenRavenLogOnServer: context.player() is not a ServerPlayer");
                    return;
                }
                sendOpenRavenLogScreen(
                        serverPlayer,
                        net.z2six.featheredfriend.world.RavenLogService.getEntriesForPlayer(serverPlayer)
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle OpenRavenLogRequestPacket on server", t);
            }
        });
    }

    private static void handleClearRavenLogOnServer(@NotNull ClearRavenLogRequestPacket payload,
                                                     @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleClearRavenLogOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                net.z2six.featheredfriend.world.RavenLogService.clearForPlayer(serverPlayer);
                sendOpenRavenLogScreen(
                        serverPlayer,
                        net.z2six.featheredfriend.world.RavenLogService.getEntriesForPlayer(serverPlayer)
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle ClearRavenLogRequestPacket on server", t);
            }
        });
    }

    private static void handleOpenRavenChestLabelScreenOnClientProxy(@NotNull OpenRavenChestLabelScreenPayload payload,
                                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleOpenRavenChestLabelScreenOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleOpenRavenChestLabelScreenOnClientProxy failed", t);
            }
        });
    }

    private static void handleOpenRavenChestSelectScreenOnClientProxy(@NotNull OpenRavenChestSelectScreenPayload payload,
                                                                       @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                dispatchToClientHandler("handleOpenRavenChestSelectScreenOnClient", payload, context);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] handleOpenRavenChestSelectScreenOnClientProxy failed", t);
            }
        });
    }

    private static void handleSetRavenChestLabelOnServer(@NotNull SetRavenChestLabelPacket payload,
                                                          @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleSetRavenChestLabelOnServer: context.player() is not a ServerPlayer");
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
    }

    private static void handleConfirmRavenChestDepositOnServer(@NotNull ConfirmRavenChestDepositPacket payload,
                                                                @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleConfirmRavenChestDepositOnServer: context.player() is not a ServerPlayer");
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
    }

    private static void handleSealStampCarveResultOnServer(@NotNull SealStampCarveResultPacket payload,
                                                           @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleSealStampCarveResultOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                SealStampCarveResultPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle SealStampCarveResultPacket on server", t);
            }
        });
    }

    private static void handleWaxSealOnServer(@NotNull WaxSealPacket payload,
                                              @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleWaxSealOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                WaxSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle WaxSealPacket on server", t);
            }
        });
    }

    private static void handleBreakSealOnServer(@NotNull BreakSealPacket payload,
                                                @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleBreakSealOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                BreakSealPacket.handle(payload, serverPlayer);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle BreakSealPacket on server", t);
            }
        });
    }

    private static void handleRavenNameChosenOnServer(@NotNull RavenNameChosenPacket payload,
                                                      @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRavenNameChosenOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                RavenNameChosenPacket.handle(payload, serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameChosenPacket on server", t);
            }
        });
    }

    private static void handleRavenNameCancelledOnServer(@NotNull RavenNameCancelledPacket payload,
                                                         @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                    LOG.error("[FFNetwork] handleRavenNameCancelledOnServer: context.player() is not a ServerPlayer");
                    return;
                }

                RavenNameCancelledPacket.handle(payload, serverPlayer);

            } catch (Throwable t) {
                LOG.error("[FFNetwork] Failed to handle RavenNameCancelledPacket on server", t);
            }
        });
    }

    // ---------------------------
    // Send helpers
    // ---------------------------

    public static void sendRavenNameChosenToServer(int ravenEntityId,
                                                   @NotNull String name) {
        try {
            String safeName = name != null ? name : "";
            RavenNameChosenPacket p = new RavenNameChosenPacket(ravenEntityId, safeName);
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RavenNameChosenPacket to server (ravenEntityId={}, nameLen={})",
                    ravenEntityId, safeName.length());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRavenNameChosenToServer failed", t);
        }
    }

    public static void sendRavenNameCancelledToServer(int ravenEntityId) {
        try {
            RavenNameCancelledPacket p = new RavenNameCancelledPacket(ravenEntityId);
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RavenNameCancelledPacket to server (ravenEntityId={})",
                    ravenEntityId);
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

            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent WaxSealPacket to server");
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
            BreakSealPacket p = new BreakSealPacket(
                    slotHint,
                    seed,
                    recipientUUID != null ? recipientUUID : "",
                    dateText != null ? dateText : "",
                    senderName != null ? senderName : ""
            );
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent BreakSealPacket to server (slotHint={} seed={})", slotHint, seed);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendBreakSealToServer failed", t);
        }
    }

    public static void sendOpenRavenNamingScreen(@NotNull ServerPlayer player,
                                                 int ravenEntityId) {
        try {
            OpenRavenNameScreenPayload payload = new OpenRavenNameScreenPayload(ravenEntityId);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent OpenRavenNameScreenPayload to {} for ravenEntityId={}",
                    player.getGameProfile().getName(), ravenEntityId);
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenNamingScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    public static void sendOpenRavenChestLabelScreen(@NotNull ServerPlayer player,
                                                      @NotNull String dimensionId,
                                                      long blockPos,
                                                      @NotNull String currentLabel) {
        try {
            PacketDistributor.sendToPlayer(player, new OpenRavenChestLabelScreenPayload(
                    dimensionId,
                    blockPos,
                    currentLabel
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenChestLabelScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    public static void sendOpenRavenLogScreen(@NotNull ServerPlayer player,
                                              @NotNull List<RavenLogEntryInfo> entries) {
        try {
            PacketDistributor.sendToPlayer(player, new OpenRavenLogScreenPayload(entries == null ? List.of() : entries));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenLogScreen failed for player={}",
                    player.getGameProfile().getName(), t);
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
            PacketDistributor.sendToPlayer(
                    player,
                    new OpenRavenChestSelectScreenPayload(
                            ravenEntityId,
                            choices,
                            action == null ? RavenChestSelectAction.ENDERPACK_DEPOSIT.id() : action.id()
                    )
            );
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenChestSelectScreen failed for player={}",
                    player.getGameProfile().getName(), t);
        }
    }

    public static void sendWhistleForRaven() {
        try {
            WhistleForRavenPacket p = new WhistleForRavenPacket();
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent WhistleForRavenPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendWhistleForRaven failed safely", t);
        }
    }

    public static void sendRequestKnownPlayersToServer() {
        try {
            RequestKnownPlayersPacket p = new RequestKnownPlayersPacket();
            PacketDistributor.sendToServer(p);
            LOG.debug("[FFNetwork] Sent RequestKnownPlayersPacket to server");
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendRequestKnownPlayersToServer failed safely", t);
        }
    }

    public static void sendOpenEnderpackToServer() {
        try {
            PacketDistributor.sendToServer(new OpenEnderpackRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenEnderpackToServer failed safely", t);
        }
    }

    public static void sendOpenRavenLogToServer() {
        try {
            PacketDistributor.sendToServer(new OpenRavenLogRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendOpenRavenLogToServer failed safely", t);
        }
    }

    public static void sendClearRavenLogToServer() {
        try {
            PacketDistributor.sendToServer(new ClearRavenLogRequestPacket());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendClearRavenLogToServer failed safely", t);
        }
    }

    public static void sendSetRavenChestLabelToServer(@NotNull String dimensionId,
                                                       long blockPos,
                                                       @NotNull String label) {
        try {
            PacketDistributor.sendToServer(new SetRavenChestLabelPacket(dimensionId, blockPos, label));
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendSetRavenChestLabelToServer failed safely", t);
        }
    }

    public static void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                             @NotNull String dimensionId,
                                                             long blockPos) {
        sendConfirmRavenChestDepositToServer(
                ravenEntityId,
                dimensionId,
                blockPos,
                RavenChestSelectAction.ENDERPACK_DEPOSIT
        );
    }

    public static void sendConfirmRavenChestDepositToServer(int ravenEntityId,
                                                             @NotNull String dimensionId,
                                                             long blockPos,
                                                             @NotNull RavenChestSelectAction action) {
        try {
            PacketDistributor.sendToServer(
                    new ConfirmRavenChestDepositPacket(
                            ravenEntityId,
                            dimensionId,
                            blockPos,
                            action == null ? RavenChestSelectAction.ENDERPACK_DEPOSIT.id() : action.id()
                    )
            );
        } catch (Throwable t) {
            LOG.error("[FFNetwork] sendConfirmRavenChestDepositToServer failed safely", t);
        }
    }

    public static void sendKnownPlayersTo(@NotNull ServerPlayer player,
                                          @NotNull Collection<FFKnownPlayersData.KnownPlayer> players) {
        try {
            List<KnownPlayerInfo> copy = new ArrayList<>();
            for (FFKnownPlayersData.KnownPlayer kp : players) {
                if (kp == null || kp.uuid() == null || kp.name() == null || kp.name().isBlank()) continue;
                copy.add(new KnownPlayerInfo(kp.uuid(), kp.name()));
            }
            KnownPlayersPayload payload = new KnownPlayersPayload(copy);
            PacketDistributor.sendToPlayer(player, payload);
            LOG.debug("[FFNetwork] Sent {} known players to {}", copy.size(), player.getGameProfile().getName());
        } catch (Throwable t) {
            LOG.error("[FFNetwork] Failed to send KnownPlayersPayload to {}", player.getGameProfile().getName(), t);
        }
    }

    // ---------------------------
    // Payloads
    // ---------------------------

    public record RequestKnownPlayersPacket() implements CustomPacketPayload {
        public static final Type<RequestKnownPlayersPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestKnownPlayersPacket> STREAM_CODEC =
                StreamCodec.of(RequestKnownPlayersPacket::encode, RequestKnownPlayersPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf, @NotNull RequestKnownPlayersPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] RequestKnownPlayersPacket encode failed", t);
            }
        }

        private static @NotNull RequestKnownPlayersPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new RequestKnownPlayersPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] RequestKnownPlayersPacket decode failed", t);
                return new RequestKnownPlayersPacket();
            }
        }

        @Override
        public @NotNull Type<RequestKnownPlayersPacket> type() {
            return TYPE;
        }
    }

    public record WhistleForRavenPacket() implements CustomPacketPayload {

        public static final Type<WhistleForRavenPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "whistle_for_raven"));

        public static final StreamCodec<RegistryFriendlyByteBuf, WhistleForRavenPacket> STREAM_CODEC =
                StreamCodec.of(WhistleForRavenPacket::encode, WhistleForRavenPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull WhistleForRavenPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] WhistleForRavenPacket encode failed", t);
            }
        }

        private static @NotNull WhistleForRavenPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new WhistleForRavenPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] WhistleForRavenPacket decode failed", t);
                return new WhistleForRavenPacket();
            }
        }

        @Override
        public @NotNull Type<WhistleForRavenPacket> type() {
            return TYPE;
        }
    }

    public record OpenEnderpackRequestPacket() implements CustomPacketPayload {

        public static final Type<OpenEnderpackRequestPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_enderpack_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenEnderpackRequestPacket> STREAM_CODEC =
                StreamCodec.of(OpenEnderpackRequestPacket::encode, OpenEnderpackRequestPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenEnderpackRequestPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenEnderpackRequestPacket encode failed", t);
            }
        }

        private static @NotNull OpenEnderpackRequestPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new OpenEnderpackRequestPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenEnderpackRequestPacket decode failed", t);
                return new OpenEnderpackRequestPacket();
            }
        }

        @Override
        public @NotNull Type<OpenEnderpackRequestPacket> type() {
            return TYPE;
        }
    }

    public record OpenRavenLogRequestPacket() implements CustomPacketPayload {

        public static final Type<OpenRavenLogRequestPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_log_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenLogRequestPacket> STREAM_CODEC =
                StreamCodec.of(OpenRavenLogRequestPacket::encode, OpenRavenLogRequestPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenLogRequestPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenLogRequestPacket encode failed", t);
            }
        }

        private static @NotNull OpenRavenLogRequestPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new OpenRavenLogRequestPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenLogRequestPacket decode failed", t);
                return new OpenRavenLogRequestPacket();
            }
        }

        @Override
        public @NotNull Type<OpenRavenLogRequestPacket> type() {
            return TYPE;
        }
    }

    public record ClearRavenLogRequestPacket() implements CustomPacketPayload {

        public static final Type<ClearRavenLogRequestPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "clear_raven_log_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ClearRavenLogRequestPacket> STREAM_CODEC =
                StreamCodec.of(ClearRavenLogRequestPacket::encode, ClearRavenLogRequestPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull ClearRavenLogRequestPacket payload) {
            try {
                // no fields
            } catch (Throwable t) {
                LOG.error("[FFNetwork] ClearRavenLogRequestPacket encode failed", t);
            }
        }

        private static @NotNull ClearRavenLogRequestPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new ClearRavenLogRequestPacket();
            } catch (Throwable t) {
                LOG.error("[FFNetwork] ClearRavenLogRequestPacket decode failed", t);
                return new ClearRavenLogRequestPacket();
            }
        }

        @Override
        public @NotNull Type<ClearRavenLogRequestPacket> type() {
            return TYPE;
        }
    }

    public record KnownPlayersPayload(List<KnownPlayerInfo> players) implements CustomPacketPayload {

        public static final Type<KnownPlayersPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "known_players"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnownPlayersPayload> STREAM_CODEC =
                StreamCodec.of(KnownPlayersPayload::encode, KnownPlayersPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull KnownPlayersPayload payload) {
            try {
                List<KnownPlayerInfo> list = payload.players();
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
            } catch (Throwable t) {
                LOG.error("[FFNetwork] KnownPlayersPayload encode failed", t);
            }
        }

        private static @NotNull KnownPlayersPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
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
            } catch (Throwable t) {
                LOG.error("[FFNetwork] KnownPlayersPayload decode failed", t);
                return new KnownPlayersPayload(List.of());
            }
        }

        @Override
        public @NotNull Type<KnownPlayersPayload> type() {
            return TYPE;
        }
    }

    public record OpenRavenNameScreenPayload(int ravenEntityId) implements CustomPacketPayload {

        public static final Type<OpenRavenNameScreenPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_name_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenNameScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenRavenNameScreenPayload::encode, OpenRavenNameScreenPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenNameScreenPayload payload) {
            try {
                buf.writeVarInt(payload.ravenEntityId());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenNameScreenPayload encode failed", t);
            }
        }

        private static @NotNull OpenRavenNameScreenPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                return new OpenRavenNameScreenPayload(id);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenNameScreenPayload decode failed", t);
                return new OpenRavenNameScreenPayload(-1);
            }
        }

        @Override
        public @NotNull Type<OpenRavenNameScreenPayload> type() {
            return TYPE;
        }
    }

    public record OpenRavenLogScreenPayload(@NotNull List<RavenLogEntryInfo> entries) implements CustomPacketPayload {

        public static final Type<OpenRavenLogScreenPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_log_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenLogScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenRavenLogScreenPayload::encode, OpenRavenLogScreenPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenLogScreenPayload payload) {
            try {
                List<RavenLogEntryInfo> list = payload.entries() == null ? List.of() : payload.entries();
                buf.writeVarInt(list.size());
                for (RavenLogEntryInfo entry : list) {
                    if (entry == null) {
                        buf.writeVarLong(0L);
                        buf.writeVarLong(0L);
                        buf.writeVarLong(0L);
                        buf.writeUtf("system", 32);
                        buf.writeUtf("", 1024);
                        continue;
                    }
                    buf.writeVarLong(entry.entryId());
                    buf.writeVarLong(entry.gameTime());
                    buf.writeVarLong(entry.createdAtMillis());
                    buf.writeUtf(entry.categoryId() == null ? "system" : entry.categoryId(), 32);
                    buf.writeUtf(entry.message() == null ? "" : entry.message(), 1024);
                }
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenLogScreenPayload encode failed", t);
            }
        }

        private static @NotNull OpenRavenLogScreenPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int size = Math.max(0, Math.min(4096, buf.readVarInt()));
                List<RavenLogEntryInfo> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    long entryId = buf.readVarLong();
                    long gameTime = buf.readVarLong();
                    long createdAtMillis = buf.readVarLong();
                    String categoryId = buf.readUtf(32);
                    String message = buf.readUtf(1024);
                    list.add(new RavenLogEntryInfo(entryId, gameTime, createdAtMillis, categoryId, message));
                }
                return new OpenRavenLogScreenPayload(list);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenLogScreenPayload decode failed", t);
                return new OpenRavenLogScreenPayload(List.of());
            }
        }

        @Override
        public @NotNull Type<OpenRavenLogScreenPayload> type() {
            return TYPE;
        }
    }

    public record OpenRavenChestLabelScreenPayload(@NotNull String dimensionId,
                                                   long blockPos,
                                                   @NotNull String currentLabel) implements CustomPacketPayload {

        public static final Type<OpenRavenChestLabelScreenPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_chest_label_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenChestLabelScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenRavenChestLabelScreenPayload::encode, OpenRavenChestLabelScreenPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenChestLabelScreenPayload payload) {
            try {
                buf.writeUtf(payload.dimensionId(), 128);
                buf.writeLong(payload.blockPos());
                buf.writeUtf(payload.currentLabel(), 128);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenChestLabelScreenPayload encode failed", t);
            }
        }

        private static @NotNull OpenRavenChestLabelScreenPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new OpenRavenChestLabelScreenPayload(
                        buf.readUtf(128),
                        buf.readLong(),
                        buf.readUtf(128)
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenChestLabelScreenPayload decode failed", t);
                return new OpenRavenChestLabelScreenPayload("minecraft:overworld", BlockPos.ZERO.asLong(), "");
            }
        }

        @Override
        public @NotNull Type<OpenRavenChestLabelScreenPayload> type() {
            return TYPE;
        }
    }

    public record OpenRavenChestSelectScreenPayload(int ravenEntityId,
                                                    @NotNull List<RavenChestChoiceInfo> choices,
                                                    int actionId) implements CustomPacketPayload {

        public static final Type<OpenRavenChestSelectScreenPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_raven_chest_select_screen"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRavenChestSelectScreenPayload> STREAM_CODEC =
                StreamCodec.of(OpenRavenChestSelectScreenPayload::encode, OpenRavenChestSelectScreenPayload::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull OpenRavenChestSelectScreenPayload payload) {
            try {
                buf.writeVarInt(payload.ravenEntityId());
                buf.writeVarInt(payload.actionId());
                List<RavenChestChoiceInfo> list = payload.choices() == null ? List.of() : payload.choices();
                buf.writeVarInt(list.size());
                for (RavenChestChoiceInfo c : list) {
                    if (c == null) {
                        buf.writeUtf("minecraft:overworld", 128);
                        buf.writeLong(BlockPos.ZERO.asLong());
                        buf.writeUtf("", 128);
                        buf.writeBoolean(false);
                        continue;
                    }
                    buf.writeUtf(c.dimensionId(), 128);
                    buf.writeLong(c.blockPos());
                    buf.writeUtf(c.label(), 128);
                    buf.writeBoolean(c.available());
                }
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenChestSelectScreenPayload encode failed", t);
            }
        }

        private static @NotNull OpenRavenChestSelectScreenPayload decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                int ravenId = buf.readVarInt();
                int actionId = buf.readVarInt();
                int size = Math.max(0, Math.min(1024, buf.readVarInt()));
                List<RavenChestChoiceInfo> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    String dim = buf.readUtf(128);
                    long pos = buf.readLong();
                    String label = buf.readUtf(128);
                    boolean available = buf.readBoolean();
                    list.add(new RavenChestChoiceInfo(dim, pos, label, available));
                }
                return new OpenRavenChestSelectScreenPayload(ravenId, list, actionId);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] OpenRavenChestSelectScreenPayload decode failed", t);
                return new OpenRavenChestSelectScreenPayload(
                        -1,
                        List.of(),
                        RavenChestSelectAction.ENDERPACK_DEPOSIT.id()
                );
            }
        }

        @Override
        public @NotNull Type<OpenRavenChestSelectScreenPayload> type() {
            return TYPE;
        }
    }

    public record SetRavenChestLabelPacket(@NotNull String dimensionId,
                                           long blockPos,
                                           @NotNull String label) implements CustomPacketPayload {

        public static final Type<SetRavenChestLabelPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "set_raven_chest_label"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SetRavenChestLabelPacket> STREAM_CODEC =
                StreamCodec.of(SetRavenChestLabelPacket::encode, SetRavenChestLabelPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull SetRavenChestLabelPacket payload) {
            try {
                buf.writeUtf(payload.dimensionId(), 128);
                buf.writeLong(payload.blockPos());
                buf.writeUtf(payload.label(), 128);
            } catch (Throwable t) {
                LOG.error("[FFNetwork] SetRavenChestLabelPacket encode failed", t);
            }
        }

        private static @NotNull SetRavenChestLabelPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new SetRavenChestLabelPacket(
                        buf.readUtf(128),
                        buf.readLong(),
                        buf.readUtf(128)
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] SetRavenChestLabelPacket decode failed", t);
                return new SetRavenChestLabelPacket("minecraft:overworld", BlockPos.ZERO.asLong(), "");
            }
        }

        @Override
        public @NotNull Type<SetRavenChestLabelPacket> type() {
            return TYPE;
        }
    }

    public record ConfirmRavenChestDepositPacket(int ravenEntityId,
                                                 @NotNull String dimensionId,
                                                 long blockPos,
                                                 int actionId) implements CustomPacketPayload {

        public static final Type<ConfirmRavenChestDepositPacket> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "confirm_raven_chest_deposit"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmRavenChestDepositPacket> STREAM_CODEC =
                StreamCodec.of(ConfirmRavenChestDepositPacket::encode, ConfirmRavenChestDepositPacket::decode);

        private static void encode(@NotNull RegistryFriendlyByteBuf buf,
                                   @NotNull ConfirmRavenChestDepositPacket payload) {
            try {
                buf.writeVarInt(payload.ravenEntityId());
                buf.writeUtf(payload.dimensionId(), 128);
                buf.writeLong(payload.blockPos());
                buf.writeVarInt(payload.actionId());
            } catch (Throwable t) {
                LOG.error("[FFNetwork] ConfirmRavenChestDepositPacket encode failed", t);
            }
        }

        private static @NotNull ConfirmRavenChestDepositPacket decode(@NotNull RegistryFriendlyByteBuf buf) {
            try {
                return new ConfirmRavenChestDepositPacket(
                        buf.readVarInt(),
                        buf.readUtf(128),
                        buf.readLong(),
                        buf.readVarInt()
                );
            } catch (Throwable t) {
                LOG.error("[FFNetwork] ConfirmRavenChestDepositPacket decode failed", t);
                return new ConfirmRavenChestDepositPacket(
                        -1,
                        "minecraft:overworld",
                        BlockPos.ZERO.asLong(),
                        RavenChestSelectAction.ENDERPACK_DEPOSIT.id()
                );
            }
        }

        @Override
        public @NotNull Type<ConfirmRavenChestDepositPacket> type() {
            return TYPE;
        }
    }
}
