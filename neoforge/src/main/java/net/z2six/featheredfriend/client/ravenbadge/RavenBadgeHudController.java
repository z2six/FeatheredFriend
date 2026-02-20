package net.z2six.featheredfriend.client.ravenbadge;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFClientConfig;
import net.z2six.featheredfriend.client.ravenbadge.RavenStatusGuiVisualMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

/**
 * Client HUD overlay for raven status badge + raven animation.
 */
public final class RavenBadgeHudController {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int FRAME_WIDTH = 128;
    private static final int FRAME_HEIGHT = 42;
    private static final float RENDER_SCALE = 0.75F;
    private static final float TEXT_SCALE = 0.55F;
    // Fine-tune badge text centering from code without touching draw math elsewhere.
    private static final float TEXT_X_OFFSET_PIXELS = 2.0F;
    private static final int MAX_QUEUED_ONE_SHOTS = 8;

    private static volatile boolean registered = false;
    private static volatile RavenBadgeBaseState currentBaseState = RavenBadgeBaseState.HIDDEN;
    private static final ArrayDeque<RavenBadgeEventType> queuedOneShots = new ArrayDeque<>();
    private static @Nullable ActiveOneShot activeOneShot = null;

    private static final Map<RavenBadgeBaseState, RenderPair> BASE_RENDERERS =
            new EnumMap<>(RavenBadgeBaseState.class);
    private static final Map<RavenBadgeEventType, RenderPair> EVENT_RENDERERS =
            new EnumMap<>(RavenBadgeEventType.class);

    static {
        ResourceLocation badgeDefault = tex("textures/gui/raven_badge/badge/badge_raven_default.png");
        ResourceLocation badgeDead = tex("textures/gui/raven_badge/badge/badge_raven_dead.png");
        ResourceLocation badgeHit = tex("textures/gui/raven_badge/badge/raven_hit_sheet.png");
        ResourceLocation badgeHostile = tex("textures/gui/raven_badge/badge/raven_hostile_nearby_sheet.png");

        ResourceLocation ravenDead = tex("textures/gui/raven_badge/raven/raven_dead.png");
        ResourceLocation ravenIdle = tex("textures/gui/raven_badge/raven/raven_idle_sheet.png");
        ResourceLocation ravenNoScroll = tex("textures/gui/raven_badge/raven/raven_no_scroll_sheet.png");
        ResourceLocation ravenQueue = tex("textures/gui/raven_badge/raven/raven_queue_sheet.png");
        ResourceLocation ravenDelivery = tex("textures/gui/raven_badge/raven/raven_delivery_sheet.png");
        ResourceLocation ravenDeliverySuccess = tex("textures/gui/raven_badge/raven/raven_delivery_success_sheet.png");
        ResourceLocation ravenHit = tex("textures/gui/raven_badge/raven/raven_hit_sheet.png");
        ResourceLocation ravenHitLoseScroll = tex("textures/gui/raven_badge/raven/raven_hit_lose_scroll_sheet.png");
        ResourceLocation ravenHostileWithScroll = tex("textures/gui/raven_badge/raven/raven_hostile_nearby_sheet.png");
        ResourceLocation ravenHostileNoScroll = tex("textures/gui/raven_badge/raven/raven_hostile_nearby_no_scroll_sheet.png");
        ResourceLocation ravenPlayerNotFound = tex("textures/gui/raven_badge/raven/raven_player_not_found_sheet.png");

        BASE_RENDERERS.put(
                RavenBadgeBaseState.IDLE_NO_SCROLL,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenIdle, 2, 22, 44, FRAME_WIDTH, FRAME_HEIGHT, 2, true)
                )
        );
        BASE_RENDERERS.put(
                RavenBadgeBaseState.MOVING_NO_SCROLL,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenNoScroll, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, true)
                )
        );
        BASE_RENDERERS.put(
                RavenBadgeBaseState.QUEUED_WITH_SCROLL,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenQueue, 2, 11, 22, FRAME_WIDTH, FRAME_HEIGHT, 2, true)
                )
        );
        BASE_RENDERERS.put(
                RavenBadgeBaseState.DELIVERING_WITH_SCROLL,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenDelivery, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, true)
                )
        );
        BASE_RENDERERS.put(
                RavenBadgeBaseState.DEAD,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDead, FRAME_WIDTH, FRAME_HEIGHT),
                        SpriteSheetAnimator.single(ravenDead, FRAME_WIDTH, FRAME_HEIGHT)
                )
        );

        EVENT_RENDERERS.put(
                RavenBadgeEventType.DELIVERY_SUCCESS,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenDeliverySuccess, 2, 9, 18, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.HIT_KEEP_SCROLL,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.HIT,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.DODGED,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.HIT_LOSE_SCROLL,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHit, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHitLoseScroll, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.HOSTILE_WITH_SCROLL,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHostile, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHostileWithScroll, 2, 9, 18, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.HOSTILE_NO_SCROLL,
                new RenderPair(
                        new SpriteSheetAnimator(badgeHostile, 2, 7, 14, FRAME_WIDTH, FRAME_HEIGHT, 2, false),
                        new SpriteSheetAnimator(ravenHostileNoScroll, 2, 9, 18, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
        EVENT_RENDERERS.put(
                RavenBadgeEventType.PLAYER_NOT_FOUND,
                new RenderPair(
                        SpriteSheetAnimator.single(badgeDefault, FRAME_WIDTH, FRAME_HEIGHT),
                        new SpriteSheetAnimator(ravenPlayerNotFound, 2, 14, 28, FRAME_WIDTH, FRAME_HEIGHT, 2, false)
                )
        );
    }

    private RavenBadgeHudController() {
    }

    public static void registerGameBus() {
        try {
            if (registered) {
                return;
            }
            NeoForge.EVENT_BUS.register(RavenBadgeHudController.class);
            registered = true;
            LOG.debug("[RavenBadgeHudController] Registered on NeoForge EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[RavenBadgeHudController] registerGameBus failed safely", t);
        }
    }

    public static void applyServerUpdate(int baseStateId, int eventTypeId) {
        try {
            currentBaseState = RavenBadgeBaseState.fromId(baseStateId);
            RavenBadgeEventType eventType = RavenBadgeEventType.fromId(eventTypeId);
            if (currentBaseState == RavenBadgeBaseState.HIDDEN) {
                queuedOneShots.clear();
                activeOneShot = null;
                return;
            }
            if (eventType != RavenBadgeEventType.NONE) {
                queueOneShot(eventType);
            }
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeHudController] applyServerUpdate failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearState();
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }

            long nowTick = mc.level.getGameTime();
            advanceOneShotQueue(nowTick);

            RenderPair renderPair = resolveRenderPair();
            if (renderPair == null) {
                return;
            }

            long elapsedTicks = resolveElapsedTicks(nowTick);
            int cfgX = FFClientConfig.getRavenStatusGuiX();
            int cfgY = FFClientConfig.getRavenStatusGuiY();
            RavenStatusGuiAnchor anchor = FFClientConfig.getRavenStatusGuiAnchor();
            RavenStatusGuiVisualMode visualMode = FFClientConfig.getRavenStatusGuiVisualMode();
            int drawFrameWidth = Math.max(1, Math.round(FRAME_WIDTH * RENDER_SCALE));
            int drawFrameHeight = Math.max(1, Math.round(FRAME_HEIGHT * RENDER_SCALE));
            int totalWidth = drawFrameWidth;
            int totalHeight = drawFrameHeight;
            int guiWidth = mc.getWindow().getGuiScaledWidth();
            int guiHeight = mc.getWindow().getGuiScaledHeight();
            int maxOffsetX = Math.max(0, guiWidth - totalWidth);
            int maxOffsetY = Math.max(0, guiHeight - totalHeight);
            int x = resolveDrawX(cfgX, maxOffsetX, anchor);
            int y = resolveDrawY(cfgY, maxOffsetY, anchor);

            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().translate(x, y, 0.0F);
            event.getGuiGraphics().pose().scale(RENDER_SCALE, RENDER_SCALE, 1.0F);

            if (visualMode == RavenStatusGuiVisualMode.BADGE_AND_TEXT) {
                renderPair.badge().render(
                        event.getGuiGraphics(),
                        0,
                        0,
                        FRAME_WIDTH,
                        FRAME_HEIGHT,
                        elapsedTicks
                );
            }
            renderPair.raven().render(
                    event.getGuiGraphics(),
                    0,
                    0,
                    FRAME_WIDTH,
                    FRAME_HEIGHT,
                    elapsedTicks
            );

            event.getGuiGraphics().pose().popPose();

            if (visualMode == RavenStatusGuiVisualMode.BADGE_AND_TEXT) {
                Component overlayText = resolveOverlayText();
                if (overlayText != null) {
                    renderOverlayText(event.getGuiGraphics(), mc, x, y, drawFrameWidth, drawFrameHeight, overlayText);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeHudController] onRenderGuiPost failed safely: {}", t.toString());
        }
    }

    private static long resolveElapsedTicks(long nowTick) {
        ActiveOneShot oneShot = activeOneShot;
        if (oneShot == null) {
            return nowTick;
        }
        return Math.max(0L, nowTick - oneShot.startedAtTick());
    }

    private static void queueOneShot(@NotNull RavenBadgeEventType eventType) {
        ActiveOneShot oneShot = activeOneShot;
        if (oneShot == null) {
            activeOneShot = new ActiveOneShot(eventType, currentClientTick());
            return;
        }
        if (queuedOneShots.size() >= MAX_QUEUED_ONE_SHOTS) {
            queuedOneShots.pollFirst();
        }
        queuedOneShots.addLast(eventType);
    }

    private static void advanceOneShotQueue(long nowTick) {
        ActiveOneShot oneShot = activeOneShot;
        if (oneShot == null) {
            if (!queuedOneShots.isEmpty()) {
                activeOneShot = new ActiveOneShot(queuedOneShots.pollFirst(), nowTick);
            }
            return;
        }

        RenderPair eventPair = EVENT_RENDERERS.get(oneShot.eventType());
        if (eventPair == null) {
            activeOneShot = null;
            return;
        }

        long elapsed = Math.max(0L, nowTick - oneShot.startedAtTick());
        // Event completes when every non-looping track has finished.
        // Looping/static tracks are treated as always complete for one-shot lifecycle.
        boolean badgeFinished = eventPair.badge().isLooping() || eventPair.badge().isFinished(elapsed);
        boolean ravenFinished = eventPair.raven().isLooping() || eventPair.raven().isFinished(elapsed);
        if (badgeFinished && ravenFinished) {
            if (!queuedOneShots.isEmpty()) {
                activeOneShot = new ActiveOneShot(queuedOneShots.pollFirst(), nowTick);
            } else {
                activeOneShot = null;
            }
        }
    }

    private static @Nullable RenderPair resolveRenderPair() {
        ActiveOneShot oneShot = activeOneShot;
        if (oneShot != null) {
            RenderPair pair = EVENT_RENDERERS.get(oneShot.eventType());
            if (pair != null) {
                return pair;
            }
        }
        return BASE_RENDERERS.get(currentBaseState);
    }

    private static @Nullable Component resolveOverlayText() {
        ActiveOneShot oneShot = activeOneShot;
        if (oneShot != null) {
            Component eventText = textForEvent(oneShot.eventType());
            if (eventText != null) {
                return eventText;
            }
        }
        return textForBaseState(currentBaseState);
    }

    private static @Nullable Component textForBaseState(@NotNull RavenBadgeBaseState baseState) {
        return switch (baseState) {
            case HIDDEN -> null;
            case IDLE_NO_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.base.idle_no_scroll");
            case MOVING_NO_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.base.moving_no_scroll");
            case QUEUED_WITH_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.base.queued_with_scroll");
            case DELIVERING_WITH_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.base.delivering_with_scroll");
            case DEAD -> Component.translatable("hud.featheredfriend.raven_badge.base.dead");
        };
    }

    private static @Nullable Component textForEvent(@NotNull RavenBadgeEventType eventType) {
        return switch (eventType) {
            case NONE -> null;
            case DELIVERY_SUCCESS -> Component.translatable("hud.featheredfriend.raven_badge.event.delivery_success");
            case HIT -> Component.translatable("hud.featheredfriend.raven_badge.event.hit");
            case DODGED -> Component.translatable("hud.featheredfriend.raven_badge.event.dodged");
            case HIT_KEEP_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.event.hit_keep_scroll");
            case HIT_LOSE_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.event.hit_lose_scroll");
            case HOSTILE_WITH_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.event.hostile_with_scroll");
            case HOSTILE_NO_SCROLL -> Component.translatable("hud.featheredfriend.raven_badge.event.hostile_no_scroll");
            case PLAYER_NOT_FOUND -> Component.translatable("hud.featheredfriend.raven_badge.event.player_not_found");
        };
    }

    private static void renderOverlayText(@NotNull net.minecraft.client.gui.GuiGraphics guiGraphics,
                                          @NotNull Minecraft mc,
                                          int badgeX,
                                          int badgeY,
                                          int badgeWidth,
                                          int badgeHeight,
                                          @NotNull Component text) {
        try {
            float scale = Mth.clamp(TEXT_SCALE, 0.1F, 1.0F);
            float scaledTextWidth = mc.font.width(text) * scale;
            float scaledTextHeight = mc.font.lineHeight * scale;

            float textX = badgeX + ((badgeWidth - scaledTextWidth) * 0.5F) + TEXT_X_OFFSET_PIXELS;
            float textY = badgeY + ((badgeHeight - scaledTextHeight) * 0.5F);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(textX, textY, 200.0F);
            guiGraphics.pose().scale(scale, scale, 1.0F);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xFFFFFF, true);
            guiGraphics.pose().popPose();
        } catch (Throwable t) {
            LOG.warn("[RavenBadgeHudController] renderOverlayText failed safely: {}", t.toString());
        }
    }

    private static int resolveDrawX(int storedX, int maxOffsetX, @NotNull RavenStatusGuiAnchor anchor) {
        return switch (anchor) {
            case TOP_LEFT, BOTTOM_LEFT -> Mth.clamp(storedX, 0, maxOffsetX);
            case TOP_RIGHT, BOTTOM_RIGHT -> maxOffsetX - Mth.clamp(storedX, 0, maxOffsetX);
            case TOP_CENTER, CENTER, BOTTOM_CENTER -> decodeCenteredAxis(storedX, maxOffsetX);
        };
    }

    private static int resolveDrawY(int storedY, int maxOffsetY, @NotNull RavenStatusGuiAnchor anchor) {
        return switch (anchor) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> Mth.clamp(storedY, 0, maxOffsetY);
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> maxOffsetY - Mth.clamp(storedY, 0, maxOffsetY);
            case CENTER -> decodeCenteredAxis(storedY, maxOffsetY);
        };
    }

    private static int decodeCenteredAxis(int storedValue, int maxOffset) {
        int maxStored = Math.max(0, maxOffset * 2);
        int clampedStored = Mth.clamp(storedValue, 0, maxStored);
        int centerAbsolute = maxOffset / 2;
        int deltaFromCenter = clampedStored - maxOffset;
        return Mth.clamp(centerAbsolute + deltaFromCenter, 0, maxOffset);
    }

    private static void clearState() {
        currentBaseState = RavenBadgeBaseState.HIDDEN;
        queuedOneShots.clear();
        activeOneShot = null;
    }

    private static long currentClientTick() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                return mc.level.getGameTime();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static @NotNull ResourceLocation tex(@NotNull String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
    }

    private record RenderPair(@NotNull SpriteSheetAnimator badge, @NotNull SpriteSheetAnimator raven) {
    }

    private record ActiveOneShot(@NotNull RavenBadgeEventType eventType, long startedAtTick) {
    }
}
