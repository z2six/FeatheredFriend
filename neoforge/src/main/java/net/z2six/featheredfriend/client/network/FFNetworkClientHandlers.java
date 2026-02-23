// neoforge/src/main/java/net/z2six/featheredfriend/client/network/FFNetworkClientHandlers.java
package net.z2six.featheredfriend.client.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.z2six.featheredfriend.client.raven.RavenLinkClientController;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeHudController;
import net.z2six.featheredfriend.client.knownplayers.KnownPlayersClientCache;
import net.z2six.featheredfriend.client.screen.RavenChestLabelScreen;
import net.z2six.featheredfriend.client.screen.RavenChestSelectScreen;
import net.z2six.featheredfriend.client.screen.RavenLogScreen;
import net.z2six.featheredfriend.client.screen.RavenNamingScreen;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public final class FFNetworkClientHandlers {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetworkClientHandlers() {
    }

    public static void handleKnownPlayersOnClient(@NotNull FFNetwork.KnownPlayersPayload payload,
                                                  @NotNull IPayloadContext context) {
        try {
            KnownPlayersClientCache cache = KnownPlayersClientCache.getInstance();
            cache.replaceAllFromServer(payload.players());
            LOG.debug("[FFNetworkClientHandlers] Updated client known-players cache: {} entries", payload.players().size());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleKnownPlayersOnClient failed", t);
        }
    }

    public static void handleOpenRavenNameScreenOnClient(@NotNull FFNetwork.OpenRavenNameScreenPayload payload,
                                                         @NotNull IPayloadContext context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player == null) return;
            if (mc.level == null) return;

            int id = payload.ravenEntityId();
            LOG.debug("[FFNetworkClientHandlers] Opening RavenNamingScreen for ravenEntityId={}", id);
            mc.setScreen(new RavenNamingScreen(id));
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleOpenRavenNameScreenOnClient failed", t);
        }
    }

    public static void handleOpenRavenLogScreenOnClient(@NotNull FFNetwork.OpenRavenLogScreenPayload payload,
                                                        @NotNull IPayloadContext context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            mc.setScreen(new RavenLogScreen(payload.entries()));
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleOpenRavenLogScreenOnClient failed", t);
        }
    }

    public static void handleOpenRavenChestLabelScreenOnClient(@NotNull FFNetwork.OpenRavenChestLabelScreenPayload payload,
                                                                @NotNull IPayloadContext context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            mc.setScreen(new RavenChestLabelScreen(
                    payload.dimensionId(),
                    payload.blockPos(),
                    payload.currentLabel()
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleOpenRavenChestLabelScreenOnClient failed", t);
        }
    }

    public static void handleOpenRavenChestSelectScreenOnClient(@NotNull FFNetwork.OpenRavenChestSelectScreenPayload payload,
                                                                 @NotNull IPayloadContext context) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            mc.setScreen(new RavenChestSelectScreen(
                    payload.ravenEntityId(),
                    payload.choices(),
                    RavenChestSelectAction.fromId(payload.actionId())
            ));
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleOpenRavenChestSelectScreenOnClient failed", t);
        }
    }

    public static void handleStartRavenLinkOnClient(@NotNull FFNetwork.StartRavenLinkPayload payload,
                                                     @NotNull IPayloadContext context) {
        try {
            RavenLinkClientController.beginFromServer(
                    payload.ravenEntityId(),
                    payload.durationTicks(),
                    payload.anchorX(),
                    payload.anchorY(),
                    payload.anchorZ(),
                    payload.anchorYaw(),
                    payload.anchorPitch()
            );
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleStartRavenLinkOnClient failed", t);
        }
    }

    public static void handleStopRavenLinkOnClient(@NotNull FFNetwork.StopRavenLinkPayload payload,
                                                     @NotNull IPayloadContext context) {
        try {
            RavenLinkClientController.endFromServer();
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleStopRavenLinkOnClient failed", t);
        }
    }

    public static void handleBeginRavenLinkEndOnClient(@NotNull FFNetwork.BeginRavenLinkEndPayload payload,
                                                       @NotNull IPayloadContext context) {
        try {
            RavenLinkClientController.beginEndSequenceFromServer();
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleBeginRavenLinkEndOnClient failed", t);
        }
    }

    public static void handleRavenLinkStateOnClient(@NotNull FFNetwork.RavenLinkStatePayload payload,
                                                      @NotNull IPayloadContext context) {
        try {
            RavenLinkClientController.updateRavenStateFromServer(
                    payload.ravenEntityId(),
                    payload.x(),
                    payload.y(),
                    payload.z(),
                    payload.yaw(),
                    payload.pitch(),
                    payload.chunksSentThisTick(),
                    payload.chunksPending(),
                    payload.chunksLoaded(),
                    payload.streamRadius()
            );
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleRavenLinkStateOnClient failed", t);
        }
    }

    public static void handleRavenLinkOwnerVisibilityOnClient(@NotNull FFNetwork.RavenLinkOwnerVisibilityPayload payload,
                                                               @NotNull IPayloadContext context) {
        try {
            RavenLinkClientController.setLinkedOwnerHidden(payload.ownerEntityId(), payload.hidden());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleRavenLinkOwnerVisibilityOnClient failed", t);
        }
    }

    public static void handleRavenBadgeStatusOnClient(@NotNull FFNetwork.RavenBadgeStatusPayload payload,
                                                       @NotNull IPayloadContext context) {
        try {
            RavenBadgeHudController.applyServerUpdate(payload.baseStateId(), payload.eventTypeId());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleRavenBadgeStatusOnClient failed", t);
        }
    }
}
