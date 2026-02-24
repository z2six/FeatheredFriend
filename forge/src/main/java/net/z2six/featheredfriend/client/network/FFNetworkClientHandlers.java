// neoforge/src/main/java/net/z2six/featheredfriend/client/network/FFNetworkClientHandlers.java
package net.z2six.featheredfriend.client.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.z2six.featheredfriend.client.raven.RavenLinkClientController;
import net.z2six.featheredfriend.client.ravenbadge.RavenBadgeHudController;
import net.z2six.featheredfriend.client.knownplayers.KnownPlayersClientCache;
import net.z2six.featheredfriend.client.screen.RavenChestLabelScreen;
import net.z2six.featheredfriend.client.screen.RavenChestSelectScreen;
import net.z2six.featheredfriend.client.screen.RavenLogScreen;
import net.z2six.featheredfriend.client.screen.RavenNamingScreen;
import net.z2six.featheredfriend.network.BeginRavenLinkEndPayload;
import net.z2six.featheredfriend.network.KnownPlayersPayload;
import net.z2six.featheredfriend.network.OpenRavenChestLabelScreenPayload;
import net.z2six.featheredfriend.network.OpenRavenChestSelectScreenPayload;
import net.z2six.featheredfriend.network.OpenRavenLogScreenPayload;
import net.z2six.featheredfriend.network.OpenRavenNameScreenPayload;
import net.z2six.featheredfriend.network.RavenBadgeStatusPayload;
import net.z2six.featheredfriend.network.RavenChestSelectAction;
import net.z2six.featheredfriend.network.RavenLinkOwnerVisibilityPayload;
import net.z2six.featheredfriend.network.RavenLinkStatePayload;
import net.z2six.featheredfriend.network.StartRavenLinkPayload;
import net.z2six.featheredfriend.network.StopRavenLinkPayload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public final class FFNetworkClientHandlers {

    private static final Logger LOG = LogUtils.getLogger();

    private FFNetworkClientHandlers() {
    }

    public static void handleKnownPlayersOnClient(@NotNull KnownPlayersPayload payload) {
        try {
            KnownPlayersClientCache cache = KnownPlayersClientCache.getInstance();
            cache.replaceAllFromServer(payload.players());
            LOG.debug("[FFNetworkClientHandlers] Updated client known-players cache: {} entries", payload.players().size());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleKnownPlayersOnClient failed", t);
        }
    }

    public static void handleOpenRavenNameScreenOnClient(@NotNull OpenRavenNameScreenPayload payload) {
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

    public static void handleOpenRavenLogScreenOnClient(@NotNull OpenRavenLogScreenPayload payload) {
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

    public static void handleOpenRavenChestLabelScreenOnClient(@NotNull OpenRavenChestLabelScreenPayload payload) {
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

    public static void handleOpenRavenChestSelectScreenOnClient(@NotNull OpenRavenChestSelectScreenPayload payload) {
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

    public static void handleStartRavenLinkOnClient(@NotNull StartRavenLinkPayload payload) {
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

    public static void handleStopRavenLinkOnClient(@NotNull StopRavenLinkPayload payload) {
        try {
            RavenLinkClientController.endFromServer();
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleStopRavenLinkOnClient failed", t);
        }
    }

    public static void handleBeginRavenLinkEndOnClient(@NotNull BeginRavenLinkEndPayload payload) {
        try {
            RavenLinkClientController.beginEndSequenceFromServer();
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleBeginRavenLinkEndOnClient failed", t);
        }
    }

    public static void handleRavenLinkStateOnClient(@NotNull RavenLinkStatePayload payload) {
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

    public static void handleRavenLinkOwnerVisibilityOnClient(@NotNull RavenLinkOwnerVisibilityPayload payload) {
        try {
            RavenLinkClientController.setLinkedOwnerHidden(payload.ownerEntityId(), payload.hidden());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleRavenLinkOwnerVisibilityOnClient failed", t);
        }
    }

    public static void handleRavenBadgeStatusOnClient(@NotNull RavenBadgeStatusPayload payload) {
        try {
            RavenBadgeHudController.applyServerUpdate(payload.baseStateId(), payload.eventTypeId());
        } catch (Throwable t) {
            LOG.error("[FFNetworkClientHandlers] handleRavenBadgeStatusOnClient failed", t);
        }
    }
}
