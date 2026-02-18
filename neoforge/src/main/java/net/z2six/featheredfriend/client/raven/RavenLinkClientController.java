package net.z2six.featheredfriend.client.raven;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.platform.Services;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client Raven Link camera + input sender.
 */
public final class RavenLinkClientController {

    private static final Logger LOG = LogUtils.getLogger();
    private static final boolean ENABLE_RAVEN_LINK_DIAGNOSTICS = false;
    private static final long EYE_TRANSITION_DURATION_MS = 260L;
    private static final long BLACK_HOLD_AFTER_TELEPORT_MS = 1000L;
    private static final double LINK_FOV_MULTIPLIER = 1.18D;
    private static final double CLOSING_CAMERA_PUSH_DISTANCE = 1.28D;
    private static final double CLOSING_CAMERA_LIFT_DISTANCE = 0.16D;
    private static final ResourceLocation RAVEN_LINK_EDGE_BLUR_LOCATION =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "shaders/post/raven_link_edge_blur.json");
    private static final ResourceLocation RAVEN_LINK_OPENING_CAW_SOUND_ID =
            ResourceLocation.fromNamespaceAndPath("featheredfriend", "raven.caw_whistle");

    private enum VisionPhase {
        CLOSING,
        HOLD_BLACK,
        OPENING,
        ACTIVE
    }

    private static volatile boolean registered = false;
    private static volatile boolean active = false;
    private static volatile int linkedRavenEntityId = -1;
    private static volatile long localEndMillis = 0L;
    private static volatile VisionPhase visionPhase = VisionPhase.CLOSING;
    private static volatile long visionPhaseStartedAtMillis = 0L;
    private static volatile @org.jetbrains.annotations.Nullable RavenCameraProxy transitionCameraProxy = null;
    private static volatile double transitionAnchorX = 0.0D;
    private static volatile double transitionAnchorY = 0.0D;
    private static volatile double transitionAnchorZ = 0.0D;
    private static volatile float transitionAnchorYaw = 0.0F;
    private static volatile float transitionAnchorPitch = 0.0F;
    private static volatile int pendingStopRetries = 0;
    private static volatile long lastStopRetrySentAtMillis = 0L;
    private static volatile boolean escWasDown = false;
    private static volatile float lookYaw = 0.0F;
    private static volatile float lookPitch = 0.0F;
    private static volatile float anchorYaw = 0.0F;
    private static volatile float anchorPitch = 0.0F;
    private static volatile boolean hasServerRavenState = false;
    private static volatile double serverRavenX = 0.0D;
    private static volatile double serverRavenY = 0.0D;
    private static volatile double serverRavenZ = 0.0D;
    private static volatile float serverRavenYaw = 0.0F;
    private static volatile float serverRavenPitch = 0.0F;
    private static volatile int debugChunksSentThisTick = 0;
    private static volatile int debugChunksPending = 0;
    private static volatile int debugChunksLoaded = 0;
    private static volatile int debugStreamRadius = 0;
    private static volatile long debugLastStateAtMillis = 0L;
    private static volatile String debugCameraMode = "none";
    private static volatile @org.jetbrains.annotations.Nullable RavenCameraProxy cameraProxy = null;
    private static volatile @org.jetbrains.annotations.Nullable Field CLIENT_CHUNK_CACHE_STORAGE_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field CLIENT_CHUNK_STORAGE_CENTER_X_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field CLIENT_CHUNK_STORAGE_CENTER_Z_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field CLIENT_CHUNK_STORAGE_RADIUS_FIELD = null;
    private static volatile boolean CLIENT_CHUNK_STORAGE_REFLECTION_READY = false;
    private static volatile @org.jetbrains.annotations.Nullable Field LEVEL_RENDERER_VIEW_AREA_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD = null;
    private static volatile @org.jetbrains.annotations.Nullable Field LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD = null;
    private static volatile boolean LEVEL_RENDERER_REFLECTION_READY = false;
    private static volatile int forcedViewAreaSectionX = Integer.MIN_VALUE;
    private static volatile int forcedViewAreaSectionZ = Integer.MIN_VALUE;
    private static volatile int debugFrameLogsRemaining = 0;
    private static volatile String debugLastFrameSignature = "";
    private static volatile int hiddenLinkedRavenEntityId = -1;
    private static volatile boolean hiddenLinkedRavenOriginalInvisible = false;
    private static volatile boolean hiddenLinkedRavenApplied = false;
    private static volatile boolean savedHideGui = false;
    private static volatile boolean hideGuiCaptured = false;
    private static volatile @org.jetbrains.annotations.Nullable PostChain ravenLinkEdgeBlurEffect = null;
    private static volatile int ravenLinkEdgeBlurWidth = -1;
    private static volatile int ravenLinkEdgeBlurHeight = -1;
    private static final List<VisionPixel> VISION_PIXELS = new ArrayList<>();
    private static final Set<Integer> HIDDEN_OWNER_ENTITY_IDS = ConcurrentHashMap.newKeySet();

    private RavenLinkClientController() {
    }

    public static void registerGameBus() {
        try {
            if (registered) {
                return;
            }
            NeoForge.EVENT_BUS.register(RavenLinkClientController.class);
            registered = true;
        } catch (Throwable t) {
            LOG.error("[RavenLinkClientController] registerGameBus failed safely", t);
        }
    }

    public static void beginFromServer(int ravenEntityId,
                                       int durationTicks,
                                       double anchorX,
                                       double anchorY,
                                       double anchorZ,
                                       float anchorYawFromServer,
                                       float anchorPitchFromServer) {
        try {
            active = true;
            linkedRavenEntityId = ravenEntityId;
            long durMs = Math.max(0L, (long) durationTicks * 50L);
            localEndMillis = System.currentTimeMillis() + durMs;
            visionPhase = VisionPhase.CLOSING;
            visionPhaseStartedAtMillis = System.currentTimeMillis();
            transitionAnchorX = anchorX;
            transitionAnchorY = anchorY;
            transitionAnchorZ = anchorZ;
            transitionAnchorYaw = anchorYawFromServer;
            transitionAnchorPitch = anchorPitchFromServer;
            transitionCameraProxy = null;
            resetVisionPixels();
            escWasDown = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                anchorYaw = mc.player.getYRot();
                anchorPitch = mc.player.getXRot();
                lookYaw = anchorYaw;
                lookPitch = anchorPitch;
                RavenCameraProxy proxy = ensureTransitionCameraProxy(
                        mc,
                        anchorX,
                        anchorY,
                        anchorZ,
                        anchorYawFromServer,
                        anchorPitchFromServer
                );
                if (proxy != null) {
                    mc.setCameraEntity(proxy);
                }
                if (!hideGuiCaptured) {
                    savedHideGui = mc.options.hideGui;
                    hideGuiCaptured = true;
                }
                // Keep HUD pipeline active so Raven Link overlays can render;
                // actual vanilla layers are suppressed via RenderGuiLayerEvent.Pre.
                mc.options.hideGui = false;
            }
            debugFrameLogsRemaining = ENABLE_RAVEN_LINK_DIAGNOSTICS ? 600 : 0;
            debugLastFrameSignature = "";
        } catch (Throwable t) {
            LOG.error("[RavenLinkClientController] beginFromServer failed safely", t);
        }
    }

    public static void endFromServer() {
        clearLocalState(false);
    }

    public static boolean shouldHideLinkedRavenForFirstPerson(int entityId) {
        try {
            if (!active || linkedRavenEntityId < 0 || entityId != linkedRavenEntityId) {
                return false;
            }
            Minecraft mc = Minecraft.getInstance();
            return mc != null
                    && mc.player != null
                    && mc.level != null
                    && mc.options.getCameraType().isFirstPerson();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setLinkedOwnerHidden(int ownerEntityId, boolean hidden) {
        try {
            if (ownerEntityId < 0) {
                return;
            }
            if (hidden) {
                HIDDEN_OWNER_ENTITY_IDS.add(ownerEntityId);
            } else {
                HIDDEN_OWNER_ENTITY_IDS.remove(ownerEntityId);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void updateRavenStateFromServer(int ravenEntityId,
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
            if (!active || ravenEntityId != linkedRavenEntityId) {
                return;
            }
            hasServerRavenState = true;
            serverRavenX = x;
            serverRavenY = y;
            serverRavenZ = z;
            serverRavenYaw = yaw;
            serverRavenPitch = pitch;
            debugChunksSentThisTick = Math.max(0, chunksSentThisTick);
            debugChunksPending = Math.max(0, chunksPending);
            debugChunksLoaded = Math.max(0, chunksLoaded);
            debugStreamRadius = Math.max(0, streamRadius);
            debugLastStateAtMillis = System.currentTimeMillis();
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] updateRavenStateFromServer failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HIDDEN_OWNER_ENTITY_IDS.clear();
        clearLocalState(false);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            if (!active) {
                return;
            }
            if (event.getNewScreen() instanceof PauseScreen) {
                event.setCanceled(true);
                clearLocalState(true);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onScreenOpening failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        try {
            if (active) {
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderHand failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre event) {
        try {
            if (!active || linkedRavenEntityId < 0) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            if (!mc.options.getCameraType().isFirstPerson()) {
                return;
            }
            Entity entity = event.getEntity();
            if (entity != null && entity.getId() == linkedRavenEntityId) {
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderLivingPre failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        try {
            Entity entity = event.getEntity();
            if (entity != null && HIDDEN_OWNER_ENTITY_IDS.contains(entity.getId())) {
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderPlayerPre failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        try {
            if (!active) {
                return;
            }
            // Hide all vanilla HUD layers while linked; custom Raven Link overlays
            // are rendered in RenderGuiEvent.Post.
            event.setCanceled(true);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderGuiLayerPre failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        try {
            if (!active) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            renderRavenLinkVisionOverlay(event.getGuiGraphics(), mc, event.getPartialTick().getGameTimeDeltaTicks());
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderGuiPost failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onComputeViewportFov(ViewportEvent.ComputeFov event) {
        try {
            if (!active) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }
            Entity camEntity = event.getCamera() == null ? null : event.getCamera().getEntity();
            if (camEntity != mc.player && camEntity != transitionCameraProxy) {
                return;
            }

            double fov = event.getFOV();
            double closingMultiplier = getClosingFovMultiplier();
            if (closingMultiplier < 0.999D) {
                fov *= closingMultiplier;
            }

            double strength = getVisionEffectStrength();
            if (strength > 0.0D) {
                fov *= (1.0D + ((LINK_FOV_MULTIPLIER - 1.0D) * strength));
            }
            event.setFOV(Math.min(170.0D, fov));
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onComputeViewportFov failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            if (!active) {
                tickStopRetryDelivery();
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                clearLocalState(false);
                return;
            }

            if (System.currentTimeMillis() >= localEndMillis) {
                clearLocalState(true);
                return;
            }

            if (mc.screen != null) {
                clearLocalState(true);
                return;
            }

            // keep explicit ESC handling even when pause screen opening gets cancelled
            boolean escDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE);
            if (escDown && !escWasDown) {
                clearLocalState(true);
                escWasDown = escDown;
                return;
            }
            escWasDown = escDown;

            tickVisionTransition();

            Entity desiredCamera = resolveDesiredActiveCamera(mc);
            if (desiredCamera != null && mc.getCameraEntity() != desiredCamera) {
                mc.setCameraEntity(desiredCamera);
            }
            ensureLinkedRavenVisualMount(mc);
            suppressNonMovementInputs(mc);
            if (mc.options.hideGui) {
                mc.options.hideGui = false;
            }

            boolean allowMovementInput = visionPhase == VisionPhase.ACTIVE;
            boolean forward = allowMovementInput && mc.options.keyUp.isDown();
            boolean backward = allowMovementInput && mc.options.keyDown.isDown();
            boolean left = allowMovementInput && mc.options.keyLeft.isDown();
            boolean right = allowMovementInput && mc.options.keyRight.isDown();
            boolean ascend = allowMovementInput && mc.options.keyJump.isDown();
            boolean descend = allowMovementInput && mc.options.keyShift.isDown();

            // Keep server in sync with player intent + look during link.
            Services.PLATFORM.sendRavenLinkInputToServer(
                    forward,
                    backward,
                    left,
                    right,
                    ascend,
                    descend,
                    mc.player.getYRot(),
                    mc.player.getXRot()
            );

            // debug overlay intentionally disabled for normal gameplay
        } catch (Throwable t) {
            LOG.error("[RavenLinkClientController] onClientTick failed safely", t);
        }
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        try {
            if (!active) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }

            Entity desiredCamera = resolveDesiredActiveCamera(mc);
            if (desiredCamera != null && mc.getCameraEntity() != desiredCamera) {
                mc.setCameraEntity(desiredCamera);
            }
            ensureLinkedRavenVisualMount(mc);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderFramePre failed safely: {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        try {
            if (!ENABLE_RAVEN_LINK_DIAGNOSTICS || !active || debugFrameLogsRemaining <= 0) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null || mc.levelRenderer == null) {
                return;
            }

            ChunkPos ravenChunk = hasServerRavenState
                    ? new ChunkPos(Mth.floor(serverRavenX) >> 4, Mth.floor(serverRavenZ) >> 4)
                    : null;
            ChunkPos playerChunk = mc.player.chunkPosition();
            ChunkPos camChunk = mc.getCameraEntity() == null ? null : mc.getCameraEntity().chunkPosition();
            ChunkPos mainCamChunk = null;
            if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                var mainCamPos = mc.gameRenderer.getMainCamera().getPosition();
                mainCamChunk = new ChunkPos(Mth.floor(mainCamPos.x) >> 4, Mth.floor(mainCamPos.z) >> 4);
            }
            ChunkViewDebug cache = readClientChunkViewDebug(mc);
            LevelRendererSectionDebug lr = readLevelRendererSectionDebug(mc);
            int rendered = mc.levelRenderer.countRenderedSections();
            int total = (int) Math.max(0.0D, mc.levelRenderer.getTotalSections());
            int loaded = mc.level.getChunkSource().getLoadedChunksCount();
            String camEntity = "null";
            Entity c = mc.getCameraEntity();
            if (c != null) {
                camEntity = c.getType().toShortString() + "#" + c.getId();
            }

            String sig = "r=" + (ravenChunk == null ? "?" : (ravenChunk.x + "," + ravenChunk.z))
                    + "|p=" + (playerChunk.x + "," + playerChunk.z)
                    + "|c=" + (camChunk == null ? "?" : (camChunk.x + "," + camChunk.z))
                    + "|mc=" + (mainCamChunk == null ? "?" : (mainCamChunk.x + "," + mainCamChunk.z))
                    + "|cc=" + (cache == null ? "?" : (cache.centerX + "," + cache.centerZ))
                    + "|cr=" + (cache == null ? "?" : cache.radius)
                    + "|lr=" + (lr == null ? "?" : (lr.lastSectionX + "," + lr.lastSectionY + "," + lr.lastSectionZ))
                    + "|ren=" + rendered + "/" + total
                    + "|loaded=" + loaded
                    + "|camE=" + camEntity;

            if (!sig.equals(debugLastFrameSignature)) {
                debugLastFrameSignature = sig;
                LOG.info("[RavenLinkDiag] {}", sig);
                debugFrameLogsRemaining--;
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] onRenderFramePost failed safely: {}", t.toString());
        }
    }

    private static @org.jetbrains.annotations.Nullable Entity resolveDesiredActiveCamera(@org.jetbrains.annotations.NotNull Minecraft mc) {
        try {
            if (mc.player == null) {
                return null;
            }
            if (visionPhase == VisionPhase.CLOSING || visionPhase == VisionPhase.HOLD_BLACK) {
                Vec3 closingPos = getClosingTransitionCameraPosition();
                return ensureTransitionCameraProxy(
                        mc,
                        closingPos.x,
                        closingPos.y,
                        closingPos.z,
                        transitionAnchorYaw,
                        transitionAnchorPitch
                );
            }
            return mc.player;
        } catch (Throwable ignored) {
            return mc.player;
        }
    }

    private static @org.jetbrains.annotations.NotNull Vec3 getClosingTransitionCameraPosition() {
        double progress = 0.0D;
        try {
            long now = System.currentTimeMillis();
            if (visionPhase == VisionPhase.CLOSING) {
                progress = Mth.clamp(
                        (double) (now - visionPhaseStartedAtMillis) / (double) EYE_TRANSITION_DURATION_MS,
                        0.0D,
                        1.0D
                );
            } else if (visionPhase == VisionPhase.HOLD_BLACK) {
                progress = 1.0D;
            }
        } catch (Throwable ignored) {
        }

        double eased = 1.0D - Math.pow(1.0D - progress, 2.35D);
        Vec3 forward = Vec3.directionFromRotation(transitionAnchorPitch, transitionAnchorYaw);
        double push = CLOSING_CAMERA_PUSH_DISTANCE * eased;
        double lift = CLOSING_CAMERA_LIFT_DISTANCE * eased;
        return new Vec3(
                transitionAnchorX + (forward.x * push),
                transitionAnchorY + (forward.y * push) + lift,
                transitionAnchorZ + (forward.z * push)
        );
    }

    private static @org.jetbrains.annotations.Nullable RavenCameraProxy ensureTransitionCameraProxy(@org.jetbrains.annotations.NotNull Minecraft mc,
                                                                                                    double x,
                                                                                                    double y,
                                                                                                    double z,
                                                                                                    float yaw,
                                                                                                    float pitch) {
        try {
            if (mc.level == null) {
                return null;
            }
            RavenCameraProxy proxy = transitionCameraProxy;
            if (proxy == null || proxy.level() != mc.level || proxy.isRemoved()) {
                proxy = new RavenCameraProxy(mc.level);
                transitionCameraProxy = proxy;
            }
            proxy.applyRemoteState(x, y, z, yaw, pitch);
            return proxy;
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] ensureTransitionCameraProxy failed safely: {}", t.toString());
            transitionCameraProxy = null;
            return null;
        }
    }

    private static void tickStopRetryDelivery() {
        try {
            if (pendingStopRetries <= 0) {
                return;
            }
            long now = System.currentTimeMillis();
            if (lastStopRetrySentAtMillis > 0L && now - lastStopRetrySentAtMillis < 120L) {
                return;
            }
            Services.PLATFORM.sendStopRavenLinkToServer();
            pendingStopRetries--;
            lastStopRetrySentAtMillis = now;
        } catch (Throwable ignored) {
        }
    }

    private static void tickVisionTransition() {
        try {
            long now = System.currentTimeMillis();
            switch (visionPhase) {
                case CLOSING -> {
                    if (now - visionPhaseStartedAtMillis >= EYE_TRANSITION_DURATION_MS) {
                        visionPhase = VisionPhase.HOLD_BLACK;
                        visionPhaseStartedAtMillis = now;
                    }
                }
                case HOLD_BLACK -> {
                    if (now - visionPhaseStartedAtMillis >= BLACK_HOLD_AFTER_TELEPORT_MS) {
                        visionPhase = VisionPhase.OPENING;
                        visionPhaseStartedAtMillis = now;
                        playOpeningCawSound();
                    }
                }
                case OPENING -> {
                    if (now - visionPhaseStartedAtMillis >= EYE_TRANSITION_DURATION_MS) {
                        visionPhase = VisionPhase.ACTIVE;
                        visionPhaseStartedAtMillis = now;
                    }
                }
                case ACTIVE -> {
                    // steady-state
                }
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] tickVisionTransition failed safely: {}", t.toString());
        }
    }

    private static void playOpeningCawSound() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                return;
            }

            Entity target = resolveCameraTarget(mc);
            double x;
            double y;
            double z;
            if (target != null) {
                x = target.getX();
                y = target.getY() + target.getBbHeight() * 0.5D;
                z = target.getZ();
            } else if (mc.player != null) {
                x = mc.player.getX();
                y = mc.player.getEyeY();
                z = mc.player.getZ();
            } else {
                return;
            }

            SoundEvent caw = SoundEvent.createVariableRangeEvent(RAVEN_LINK_OPENING_CAW_SOUND_ID);
            float pitch = 0.98F + (mc.level.random.nextFloat() * 0.04F);
            mc.level.playLocalSound(x, y, z, caw, SoundSource.NEUTRAL, 1.0F, pitch, false);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] playOpeningCawSound failed safely: {}", t.toString());
        }
    }

    private static double getEyelidProgress() {
        try {
            long now = System.currentTimeMillis();
            return switch (visionPhase) {
                case CLOSING -> Mth.clamp((double) (now - visionPhaseStartedAtMillis) / (double) EYE_TRANSITION_DURATION_MS, 0.0D, 1.0D);
                case HOLD_BLACK -> 1.0D;
                case OPENING -> 1.0D - Mth.clamp((double) (now - visionPhaseStartedAtMillis) / (double) EYE_TRANSITION_DURATION_MS, 0.0D, 1.0D);
                case ACTIVE -> 0.0D;
            };
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static double getVisionEffectStrength() {
        try {
            return switch (visionPhase) {
                case CLOSING, HOLD_BLACK -> 0.0D;
                case OPENING -> Mth.clamp(1.0D - getEyelidProgress(), 0.0D, 1.0D);
                case ACTIVE -> 1.0D;
            };
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static double getClosingFovMultiplier() {
        try {
            if (visionPhase != VisionPhase.CLOSING) {
                return 1.0D;
            }
            double progress = getEyelidProgress();
            // Quickly tunnel in while eyelids close, then reset once full black starts.
            return Mth.clamp(1.0D - (0.34D * progress), 0.66D, 1.0D);
        } catch (Throwable ignored) {
            return 1.0D;
        }
    }

    private static void renderRavenLinkVisionOverlay(@org.jetbrains.annotations.NotNull net.minecraft.client.gui.GuiGraphics guiGraphics,
                                                     @org.jetbrains.annotations.NotNull Minecraft mc,
                                                     float partialTicks) {
        try {
            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            double strength = getVisionEffectStrength();
            if (strength > 0.0D) {
                applyEdgeGaussianBlur(mc, partialTicks, strength);
                // Stronger purple vision tint across whole frame.
                int baseTintA = (int) Math.round(72.0D * strength);
                int glowTintA = (int) Math.round(44.0D * strength);
                guiGraphics.fill(0, 0, width, height, argb(baseTintA, 168, 100, 218));
                guiGraphics.fill(0, 0, width, height, argb(glowTintA, 220, 146, 255));
                drawPurpleVignette(guiGraphics, width, height, strength);
                drawAnimatedPixelVeil(guiGraphics, width, height, strength);
            }

            double eyelid = getEyelidProgress();
            if (eyelid > 0.0D) {
                drawEyelids(guiGraphics, width, height, eyelid);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] renderRavenLinkVisionOverlay failed safely: {}", t.toString());
        }
    }

    private static void applyEdgeGaussianBlur(@org.jetbrains.annotations.NotNull Minecraft mc,
                                              float partialTicks,
                                              double strength) {
        try {
            PostChain blurChain = ensureRavenLinkEdgeBlurEffect(mc);
            if (blurChain == null) {
                return;
            }
            float radius = Mth.clamp((float) (2.4D + (strength * 4.8D)), 1.0F, 8.0F);
            float edgeMix = Mth.clamp((float) (0.34D + (strength * 0.46D)), 0.0F, 1.0F);
            float edgeStart = Mth.clamp((float) (0.58D - (strength * 0.06D)), 0.45F, 0.70F);
            blurChain.setUniform("Radius", radius);
            blurChain.setUniform("EdgeMix", edgeMix);
            blurChain.setUniform("EdgeStart", edgeStart);
            blurChain.setUniform("EdgeEnd", 0.98F);
            blurChain.setUniform("EdgePower", 1.55F);
            blurChain.process(partialTicks);
            // PostChain leaves the target unbound after processing; rebind so GUI tint draws on top.
            mc.getMainRenderTarget().bindWrite(false);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] applyEdgeGaussianBlur failed safely: {}", t.toString());
            releaseRavenLinkEdgeBlurEffect();
        }
    }

    private static @org.jetbrains.annotations.Nullable PostChain ensureRavenLinkEdgeBlurEffect(@org.jetbrains.annotations.NotNull Minecraft mc) {
        try {
            if (ravenLinkEdgeBlurEffect == null) {
                ravenLinkEdgeBlurEffect = new PostChain(
                        mc.getTextureManager(),
                        mc.getResourceManager(),
                        mc.getMainRenderTarget(),
                        RAVEN_LINK_EDGE_BLUR_LOCATION
                );
                ravenLinkEdgeBlurWidth = -1;
                ravenLinkEdgeBlurHeight = -1;
            }

            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            if (width > 0
                    && height > 0
                    && (width != ravenLinkEdgeBlurWidth || height != ravenLinkEdgeBlurHeight)) {
                ravenLinkEdgeBlurEffect.resize(width, height);
                ravenLinkEdgeBlurWidth = width;
                ravenLinkEdgeBlurHeight = height;
            }
            return ravenLinkEdgeBlurEffect;
        } catch (IOException | JsonSyntaxException e) {
            LOG.warn("[RavenLinkClientController] Failed to load Raven Link edge blur shader '{}': {}", RAVEN_LINK_EDGE_BLUR_LOCATION, e.toString());
            releaseRavenLinkEdgeBlurEffect();
            return null;
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] ensureRavenLinkEdgeBlurEffect failed safely: {}", t.toString());
            releaseRavenLinkEdgeBlurEffect();
            return null;
        }
    }

    private static void releaseRavenLinkEdgeBlurEffect() {
        try {
            if (ravenLinkEdgeBlurEffect != null) {
                ravenLinkEdgeBlurEffect.close();
            }
        } catch (Throwable ignored) {
        } finally {
            ravenLinkEdgeBlurEffect = null;
            ravenLinkEdgeBlurWidth = -1;
            ravenLinkEdgeBlurHeight = -1;
        }
    }

    private static void resetVisionPixels() {
        try {
            VISION_PIXELS.clear();
        } catch (Throwable ignored) {
        }
    }

    private static void drawPurpleVignette(@org.jetbrains.annotations.NotNull net.minecraft.client.gui.GuiGraphics guiGraphics,
                                           int width,
                                           int height,
                                           double strength) {
        try {
            int layers = 12;
            int maxInset = Math.max(12, (int) (Math.min(width, height) * 0.16F));

            for (int i = 0; i < layers; i++) {
                float f0 = i / (float) layers;
                float f1 = (i + 1) / (float) layers;
                int inset0 = Math.round(f0 * maxInset);
                int inset1 = Math.round(f1 * maxInset);
                if (inset1 <= inset0) {
                    continue;
                }

                float edgeStrength = 1.0F - f0;
                int darkAlpha = (int) Math.round((4.0F + (edgeStrength * edgeStrength * 34.0F)) * strength);
                int purpleAlpha = (int) Math.round((3.0F + (edgeStrength * edgeStrength * 24.0F)) * strength);
                int darkColor = argb(darkAlpha, 20, 8, 34);
                int purpleColor = argb(purpleAlpha, 132, 64, 176);

                guiGraphics.fill(inset0, inset0, width - inset0, inset1, darkColor);
                guiGraphics.fill(inset0, height - inset1, width - inset0, height - inset0, darkColor);
                guiGraphics.fill(inset0, inset0, inset1, height - inset0, darkColor);
                guiGraphics.fill(width - inset1, inset0, width - inset0, height - inset0, darkColor);

                guiGraphics.fill(inset1, inset1, width - inset1, inset1 + 1, purpleColor);
                guiGraphics.fill(inset1, height - inset1 - 1, width - inset1, height - inset1, purpleColor);
                guiGraphics.fill(inset1, inset1, inset1 + 1, height - inset1, purpleColor);
                guiGraphics.fill(width - inset1 - 1, inset1, width - inset1, height - inset1, purpleColor);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void drawAnimatedPixelVeil(@org.jetbrains.annotations.NotNull net.minecraft.client.gui.GuiGraphics guiGraphics,
                                              int width,
                                              int height,
                                              double strength) {
        try {
            float s = Mth.clamp((float) strength, 0.0F, 1.0F);
            if (s <= 0.0F) {
                return;
            }

            long now = System.currentTimeMillis();
            int targetPixels = Mth.clamp(Math.round(52.0F + (146.0F * s)), 34, 228);

            Iterator<VisionPixel> it = VISION_PIXELS.iterator();
            while (it.hasNext()) {
                VisionPixel pixel = it.next();
                if (now - pixel.bornAtMs >= pixel.lifeMs) {
                    it.remove();
                }
            }

            while (VISION_PIXELS.size() > targetPixels) {
                VISION_PIXELS.remove(VISION_PIXELS.size() - 1);
            }
            while (VISION_PIXELS.size() < targetPixels) {
                VISION_PIXELS.add(spawnVisionPixel(width, height, s, now));
            }
            ensureVisionPixelEdgeCoverage(width, height, s, now, targetPixels);

            for (VisionPixel pixel : VISION_PIXELS) {
                float lifeT = Mth.clamp((float) (now - pixel.bornAtMs) / (float) pixel.lifeMs, 0.0F, 1.0F);
                float fade = lifeT < 0.5F ? (lifeT * 2.0F) : ((1.0F - lifeT) * 2.0F);
                float pulse = 0.74F + (0.26F * (float) Math.sin((now + pixel.phaseOffsetMs) * 0.0044D));
                int alpha = Mth.clamp(Math.round(pixel.maxAlpha * fade * pulse), 0, 255);
                if (alpha <= 1) {
                    continue;
                }

                int x0 = Math.round(pixel.x);
                int y0 = Math.round(pixel.y);
                int x1 = x0 + pixel.size;
                int y1 = y0 + pixel.size;
                if (x1 <= 0 || y1 <= 0 || x0 >= width || y0 >= height) {
                    continue;
                }

                guiGraphics.fill(x0, y0, x1, y1, argb(alpha, pixel.r, pixel.g, pixel.b));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureVisionPixelEdgeCoverage(int width,
                                                      int height,
                                                      float strength,
                                                      long nowMs,
                                                      int targetPixels) {
        try {
            float edgeBandX = Math.max(10.0F, width * 0.15F);
            float edgeBandY = Math.max(10.0F, height * 0.15F);

            boolean hasTop = false;
            boolean hasBottom = false;
            boolean hasLeft = false;
            boolean hasRight = false;

            for (VisionPixel pixel : VISION_PIXELS) {
                float px = pixel.x + (pixel.size * 0.5F);
                float py = pixel.y + (pixel.size * 0.5F);
                if (py <= edgeBandY) {
                    hasTop = true;
                }
                if (py >= (height - edgeBandY)) {
                    hasBottom = true;
                }
                if (px <= edgeBandX) {
                    hasLeft = true;
                }
                if (px >= (width - edgeBandX)) {
                    hasRight = true;
                }
                if (hasTop && hasBottom && hasLeft && hasRight) {
                    return;
                }
            }

            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            if (!hasTop) {
                addVisionPixelForSide(0, width, height, strength, nowMs, targetPixels, rnd);
            }
            if (!hasBottom) {
                addVisionPixelForSide(1, width, height, strength, nowMs, targetPixels, rnd);
            }
            if (!hasLeft) {
                addVisionPixelForSide(2, width, height, strength, nowMs, targetPixels, rnd);
            }
            if (!hasRight) {
                addVisionPixelForSide(3, width, height, strength, nowMs, targetPixels, rnd);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addVisionPixelForSide(int side,
                                              int width,
                                              int height,
                                              float strength,
                                              long nowMs,
                                              int targetPixels,
                                              @org.jetbrains.annotations.NotNull ThreadLocalRandom rnd) {
        try {
            if (VISION_PIXELS.size() >= targetPixels && !VISION_PIXELS.isEmpty()) {
                VISION_PIXELS.remove(rnd.nextInt(VISION_PIXELS.size()));
            }
            VISION_PIXELS.add(spawnVisionPixelOnSide(width, height, strength, nowMs, side, rnd));
        } catch (Throwable ignored) {
        }
    }

    private static @org.jetbrains.annotations.NotNull VisionPixel spawnVisionPixel(int width,
                                                                                    int height,
                                                                                    float strength,
                                                                                    long nowMs) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextFloat() < 0.90F) {
            int side = rnd.nextInt(4);
            return spawnVisionPixelOnSide(width, height, strength, nowMs, side, rnd);
        }
        float x = rnd.nextFloat() * width;
        float y = rnd.nextFloat() * height;
        return createVisionPixelAt(x, y, width, height, strength, nowMs, rnd);
    }

    private static @org.jetbrains.annotations.NotNull VisionPixel spawnVisionPixelOnSide(int width,
                                                                                          int height,
                                                                                          float strength,
                                                                                          long nowMs,
                                                                                          int side,
                                                                                          @org.jetbrains.annotations.NotNull ThreadLocalRandom rnd) {
        float edgeDepthX = Math.max(10.0F, width * (0.07F + (rnd.nextFloat() * 0.23F)));
        float edgeDepthY = Math.max(10.0F, height * (0.07F + (rnd.nextFloat() * 0.23F)));
        float x;
        float y;
        switch (side) {
            case 0 -> {
                x = rnd.nextFloat() * width;
                y = rnd.nextFloat() * edgeDepthY;
            }
            case 1 -> {
                x = rnd.nextFloat() * width;
                y = height - (rnd.nextFloat() * edgeDepthY);
            }
            case 2 -> {
                x = rnd.nextFloat() * edgeDepthX;
                y = rnd.nextFloat() * height;
            }
            default -> {
                x = width - (rnd.nextFloat() * edgeDepthX);
                y = rnd.nextFloat() * height;
            }
        }
        return createVisionPixelAt(x, y, width, height, strength, nowMs, rnd);
    }

    private static @org.jetbrains.annotations.NotNull VisionPixel createVisionPixelAt(float x,
                                                                                       float y,
                                                                                       int width,
                                                                                       int height,
                                                                                       float strength,
                                                                                       long nowMs,
                                                                                       @org.jetbrains.annotations.NotNull ThreadLocalRandom rnd) {
        float edgeRatioX = Math.min(x, Math.max(0.0F, width - x)) / Math.max(1.0F, width);
        float edgeRatioY = Math.min(y, Math.max(0.0F, height - y)) / Math.max(1.0F, height);
        float edgeBias = Mth.clamp(1.0F - (Math.min(edgeRatioX, edgeRatioY) * 5.2F), 0.0F, 1.0F);

        int size = rnd.nextInt(10, 22);
        long lifeMs = rnd.nextLong(760L, 1820L);
        int maxAlpha = Mth.clamp(
                Math.round((18.0F + (78.0F * edgeBias)) * (0.45F + (0.85F * strength))),
                16,
                154
        );

        int r = Mth.clamp(126 + rnd.nextInt(66), 0, 255);
        int g = Mth.clamp(46 + rnd.nextInt(82), 0, 255);
        int b = Mth.clamp(168 + rnd.nextInt(82), 0, 255);
        long phaseOffsetMs = rnd.nextLong(0L, 2200L);
        return new VisionPixel(x, y, size, nowMs, lifeMs, maxAlpha, r, g, b, phaseOffsetMs);
    }

    private static void drawEyelids(@org.jetbrains.annotations.NotNull net.minecraft.client.gui.GuiGraphics guiGraphics,
                                    int width,
                                    int height,
                                    double progress) {
        try {
            int barHeight = Mth.clamp((int) Math.round((height * 0.5D) * progress), 0, height / 2);
            if (barHeight <= 0) {
                return;
            }

            guiGraphics.fill(0, 0, width, barHeight, 0xFF000000);
            guiGraphics.fill(0, height - barHeight, width, height, 0xFF000000);

            int feather = Math.max(2, height / 96);
            for (int i = 0; i < feather; i++) {
                float t = 1.0F - (i / (float) feather);
                int alpha = (int) Math.round(170.0F * t * progress);
                int color = argb(alpha, 0, 0, 0);
                int topY = barHeight + i;
                int bottomY = height - barHeight - i - 1;
                if (topY >= 0 && topY < height) {
                    guiGraphics.fill(0, topY, width, topY + 1, color);
                }
                if (bottomY >= 0 && bottomY < height) {
                    guiGraphics.fill(0, bottomY, width, bottomY + 1, color);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int argb(int a, int r, int g, int b) {
        int aa = Mth.clamp(a, 0, 255);
        int rr = Mth.clamp(r, 0, 255);
        int gg = Mth.clamp(g, 0, 255);
        int bb = Mth.clamp(b, 0, 255);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static @org.jetbrains.annotations.Nullable Entity resolveCameraTarget(Minecraft mc) {
        try {
            if (mc.level == null) {
                return null;
            }

            Entity raven = mc.level.getEntity(linkedRavenEntityId);
            if (raven != null && raven.isAlive() && !raven.isRemoved()) {
                debugCameraMode = "entity";
                return raven;
            }

            if (!hasServerRavenState) {
                debugCameraMode = "none";
                return null;
            }

            RavenCameraProxy proxy = ensureCameraProxy(mc.level);
            if (proxy == null) {
                debugCameraMode = "proxy_missing";
                return null;
            }
            proxy.applyRemoteState(
                    serverRavenX,
                    serverRavenY,
                    serverRavenZ,
                    serverRavenYaw,
                    serverRavenPitch
            );
            debugCameraMode = "proxy";
            return proxy;
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] resolveCameraTarget failed safely: {}", t.toString());
            debugCameraMode = "error";
            return null;
        }
    }

    private static void renderDebugOverlay(Minecraft mc) {
        try {
            long ageMs = debugLastStateAtMillis <= 0L ? -1L : (System.currentTimeMillis() - debugLastStateAtMillis);
            int loadedChunks = mc.level == null ? -1 : mc.level.getChunkSource().getLoadedChunksCount();
            ChunkPos camChunk = mc.getCameraEntity() == null ? null : mc.getCameraEntity().chunkPosition();
            ChunkPos playerChunk = mc.player == null ? null : mc.player.chunkPosition();
            ChunkPos mainCamChunk = null;
            if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                var mainCamPos = mc.gameRenderer.getMainCamera().getPosition();
                mainCamChunk = new ChunkPos(Mth.floor(mainCamPos.x) >> 4, Mth.floor(mainCamPos.z) >> 4);
            }
            ChunkViewDebug chunkViewDebug = readClientChunkViewDebug(mc);
            String text = "RLink cam=" + debugCameraMode
                    + " stateAge=" + ageMs + "ms"
                    + " sent=" + debugChunksSentThisTick
                    + " pending=" + debugChunksPending
                    + " loaded=" + debugChunksLoaded
                    + " radius=" + debugStreamRadius
                    + " clientLoaded=" + loadedChunks
                    + " camChunk=" + (camChunk == null ? "?" : (camChunk.x + "," + camChunk.z))
                    + " mainCamChunk=" + (mainCamChunk == null ? "?" : (mainCamChunk.x + "," + mainCamChunk.z))
                    + " playerChunk=" + (playerChunk == null ? "?" : (playerChunk.x + "," + playerChunk.z))
                    + " cacheCenter=" + (chunkViewDebug == null ? "?" : (chunkViewDebug.centerX + "," + chunkViewDebug.centerZ))
                    + " cacheRadius=" + (chunkViewDebug == null ? "?" : chunkViewDebug.radius)
                    + " xyz=" + String.format("%.1f %.1f %.1f", serverRavenX, serverRavenY, serverRavenZ);
            mc.gui.setOverlayMessage(Component.literal(text), false);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] renderDebugOverlay failed safely: {}", t.toString());
        }
    }

    private static void forceClientChunkViewToRaven(Minecraft mc) {
        try {
            if (!active || !hasServerRavenState || mc.level == null) {
                return;
            }

            int chunkX = Mth.floor(serverRavenX) >> 4;
            int chunkZ = Mth.floor(serverRavenZ) >> 4;
            int radius = Math.max(2, debugStreamRadius > 0 ? debugStreamRadius : 8);
            ClientChunkCache cache = mc.level.getChunkSource();
            cache.updateViewRadius(radius);
            cache.updateViewCenter(chunkX, chunkZ);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] forceClientChunkViewToRaven failed safely: {}", t.toString());
        }
    }

    private static @org.jetbrains.annotations.Nullable ChunkViewDebug readClientChunkViewDebug(Minecraft mc) {
        try {
            if (mc.level == null) {
                return null;
            }
            ensureClientChunkStorageReflection();
            if (!CLIENT_CHUNK_STORAGE_REFLECTION_READY
                    || CLIENT_CHUNK_CACHE_STORAGE_FIELD == null
                    || CLIENT_CHUNK_STORAGE_CENTER_X_FIELD == null
                    || CLIENT_CHUNK_STORAGE_CENTER_Z_FIELD == null
                    || CLIENT_CHUNK_STORAGE_RADIUS_FIELD == null) {
                return null;
            }

            Object storage = CLIENT_CHUNK_CACHE_STORAGE_FIELD.get(mc.level.getChunkSource());
            if (storage == null) {
                return null;
            }

            int centerX = CLIENT_CHUNK_STORAGE_CENTER_X_FIELD.getInt(storage);
            int centerZ = CLIENT_CHUNK_STORAGE_CENTER_Z_FIELD.getInt(storage);
            int radius = CLIENT_CHUNK_STORAGE_RADIUS_FIELD.getInt(storage);
            return new ChunkViewDebug(centerX, centerZ, radius);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] readClientChunkViewDebug failed safely: {}", t.toString());
            return null;
        }
    }

    private static void forceLevelRendererViewAreaToRaven(Minecraft mc) {
        try {
            if (!active || !hasServerRavenState || mc.level == null || mc.player == null || mc.levelRenderer == null) {
                return;
            }

            ensureLevelRendererReflection();
            if (!LEVEL_RENDERER_REFLECTION_READY
                    || LEVEL_RENDERER_VIEW_AREA_FIELD == null
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD == null
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD == null
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD == null) {
                return;
            }

            LevelRenderer levelRenderer = mc.levelRenderer;
            Object viewAreaObj = LEVEL_RENDERER_VIEW_AREA_FIELD.get(levelRenderer);
            if (!(viewAreaObj instanceof ViewArea viewArea)) {
                return;
            }

            // Keep vanilla's player-based camera section check "stable" so it does not
            // recenter viewArea back to the owner's frozen chunk every render frame.
            int playerSectionX = SectionPos.posToSectionCoord(mc.player.getX());
            int playerSectionY = SectionPos.posToSectionCoord(mc.player.getY());
            int playerSectionZ = SectionPos.posToSectionCoord(mc.player.getZ());
            LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD.setInt(levelRenderer, playerSectionX);
            LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD.setInt(levelRenderer, playerSectionY);
            LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD.setInt(levelRenderer, playerSectionZ);

            int ravenSectionX = SectionPos.posToSectionCoord(serverRavenX);
            int ravenSectionZ = SectionPos.posToSectionCoord(serverRavenZ);
            if (ravenSectionX != forcedViewAreaSectionX || ravenSectionZ != forcedViewAreaSectionZ) {
                viewArea.repositionCamera(serverRavenX, serverRavenZ);
                forcedViewAreaSectionX = ravenSectionX;
                forcedViewAreaSectionZ = ravenSectionZ;
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] forceLevelRendererViewAreaToRaven failed safely: {}", t.toString());
        }
    }

    private static void ensureLevelRendererReflection() {
        if (LEVEL_RENDERER_REFLECTION_READY) {
            return;
        }
        try {
            Field viewArea = LevelRenderer.class.getDeclaredField("viewArea");
            Field lastSectionX = LevelRenderer.class.getDeclaredField("lastCameraSectionX");
            Field lastSectionY = LevelRenderer.class.getDeclaredField("lastCameraSectionY");
            Field lastSectionZ = LevelRenderer.class.getDeclaredField("lastCameraSectionZ");

            viewArea.setAccessible(true);
            lastSectionX.setAccessible(true);
            lastSectionY.setAccessible(true);
            lastSectionZ.setAccessible(true);

            LEVEL_RENDERER_VIEW_AREA_FIELD = viewArea;
            LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD = lastSectionX;
            LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD = lastSectionY;
            LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD = lastSectionZ;
            LEVEL_RENDERER_REFLECTION_READY = true;
        } catch (Throwable t) {
            LEVEL_RENDERER_REFLECTION_READY = true;
            LOG.debug("[RavenLinkClientController] ensureLevelRendererReflection unavailable: {}", t.toString());
        }
    }

    private static @org.jetbrains.annotations.Nullable LevelRendererSectionDebug readLevelRendererSectionDebug(Minecraft mc) {
        try {
            if (mc.levelRenderer == null) {
                return null;
            }
            ensureLevelRendererReflection();
            if (!LEVEL_RENDERER_REFLECTION_READY
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD == null
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD == null
                    || LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD == null) {
                return null;
            }
            int x = LEVEL_RENDERER_LAST_CAMERA_SECTION_X_FIELD.getInt(mc.levelRenderer);
            int y = LEVEL_RENDERER_LAST_CAMERA_SECTION_Y_FIELD.getInt(mc.levelRenderer);
            int z = LEVEL_RENDERER_LAST_CAMERA_SECTION_Z_FIELD.getInt(mc.levelRenderer);
            return new LevelRendererSectionDebug(x, y, z);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] readLevelRendererSectionDebug failed safely: {}", t.toString());
            return null;
        }
    }

    private static void ensureClientChunkStorageReflection() {
        if (CLIENT_CHUNK_STORAGE_REFLECTION_READY) {
            return;
        }
        try {
            Field storageField = ClientChunkCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            Class<?> storageClass = Class.forName("net.minecraft.client.multiplayer.ClientChunkCache$Storage");
            Field centerX = storageClass.getDeclaredField("viewCenterX");
            Field centerZ = storageClass.getDeclaredField("viewCenterZ");
            Field radius = storageClass.getDeclaredField("chunkRadius");
            centerX.setAccessible(true);
            centerZ.setAccessible(true);
            radius.setAccessible(true);

            CLIENT_CHUNK_CACHE_STORAGE_FIELD = storageField;
            CLIENT_CHUNK_STORAGE_CENTER_X_FIELD = centerX;
            CLIENT_CHUNK_STORAGE_CENTER_Z_FIELD = centerZ;
            CLIENT_CHUNK_STORAGE_RADIUS_FIELD = radius;
            CLIENT_CHUNK_STORAGE_REFLECTION_READY = true;
        } catch (Throwable t) {
            CLIENT_CHUNK_STORAGE_REFLECTION_READY = true;
            LOG.debug("[RavenLinkClientController] ensureClientChunkStorageReflection unavailable: {}", t.toString());
        }
    }

    private static @org.jetbrains.annotations.Nullable RavenCameraProxy ensureCameraProxy(Level level) {
        try {
            RavenCameraProxy proxy = cameraProxy;
            if (proxy != null && proxy.level() == level && !proxy.isRemoved()) {
                return proxy;
            }
            proxy = new RavenCameraProxy(level);
            cameraProxy = proxy;
            return proxy;
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] ensureCameraProxy failed safely: {}", t.toString());
            cameraProxy = null;
            return null;
        }
    }

    private static void updateLinkLookFromPlayerDelta(Player player) {
        try {
            float currentYaw = player.getYRot();
            float currentPitch = player.getXRot();

            float deltaYaw = Mth.wrapDegrees(currentYaw - anchorYaw);
            float deltaPitch = currentPitch - anchorPitch;

            lookYaw = Mth.wrapDegrees(lookYaw + deltaYaw);
            lookPitch = Mth.clamp(lookPitch + deltaPitch, -89.9F, 89.9F);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] updateLinkLookFromPlayerDelta failed safely: {}", t.toString());
        }
    }

    private static void freezeLocalPlayerPose(Player player) {
        try {
            player.setYRot(anchorYaw);
            player.setYHeadRot(anchorYaw);
            player.setXRot(anchorPitch);
            player.yBodyRot = anchorYaw;
            player.yHeadRotO = anchorYaw;
            player.yBodyRotO = anchorYaw;
            player.yRotO = anchorYaw;
            player.xRotO = anchorPitch;
            player.setShiftKeyDown(false);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] freezeLocalPlayerPose failed safely: {}", t.toString());
        }
    }

    private static void clearLocalState(boolean sendStopRequest) {
        int previousLinkedRavenId = linkedRavenEntityId;
        try {
            if (sendStopRequest) {
                Services.PLATFORM.sendStopRavenLinkToServer();
                pendingStopRetries = Math.max(pendingStopRetries, 4);
                lastStopRetrySentAtMillis = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                try {
                    if (mc.level != null && previousLinkedRavenId >= 0) {
                        Entity e = mc.level.getEntity(previousLinkedRavenId);
                        if (e instanceof RavenEntity raven && raven.getVehicle() == mc.player) {
                            raven.stopRiding();
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (mc.getCameraEntity() != mc.player) {
                    mc.setCameraEntity(mc.player);
                }
            }
        } catch (Throwable ignored) {
        }

        releaseRavenLinkEdgeBlurEffect();
        resetVisionPixels();

        active = false;
        linkedRavenEntityId = -1;
        localEndMillis = 0L;
        visionPhase = VisionPhase.CLOSING;
        visionPhaseStartedAtMillis = 0L;
        transitionCameraProxy = null;
        transitionAnchorX = 0.0D;
        transitionAnchorY = 0.0D;
        transitionAnchorZ = 0.0D;
        transitionAnchorYaw = 0.0F;
        transitionAnchorPitch = 0.0F;
        if (!sendStopRequest) {
            pendingStopRetries = 0;
            lastStopRetrySentAtMillis = 0L;
        }
        escWasDown = false;
        lookYaw = 0.0F;
        lookPitch = 0.0F;
        anchorYaw = 0.0F;
        anchorPitch = 0.0F;
        hasServerRavenState = false;
        serverRavenX = 0.0D;
        serverRavenY = 0.0D;
        serverRavenZ = 0.0D;
        serverRavenYaw = 0.0F;
        serverRavenPitch = 0.0F;
        debugChunksSentThisTick = 0;
        debugChunksPending = 0;
        debugChunksLoaded = 0;
        debugStreamRadius = 0;
        debugLastStateAtMillis = 0L;
        debugCameraMode = "none";
        cameraProxy = null;
        forcedViewAreaSectionX = Integer.MIN_VALUE;
        forcedViewAreaSectionZ = Integer.MIN_VALUE;
        debugFrameLogsRemaining = 0;
        debugLastFrameSignature = "";
        if (hideGuiCaptured) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.options.hideGui = savedHideGui;
                }
            } catch (Throwable ignored) {
            }
        }
        savedHideGui = false;
        hideGuiCaptured = false;
        try {
            restoreHiddenLinkedRaven(Minecraft.getInstance());
        } catch (Throwable ignored) {
            hiddenLinkedRavenEntityId = -1;
            hiddenLinkedRavenOriginalInvisible = false;
            hiddenLinkedRavenApplied = false;
        }
    }

    private static void suppressNonMovementInputs(@org.jetbrains.annotations.NotNull Minecraft mc) {
        try {
            forceKeyUp(mc.options.keyAttack);
            forceKeyUp(mc.options.keyUse);
            forceKeyUp(mc.options.keyPickItem);
            forceKeyUp(mc.options.keyDrop);
            forceKeyUp(mc.options.keySwapOffhand);
            forceKeyUp(mc.options.keyInventory);
            forceKeyUp(mc.options.keyChat);
            forceKeyUp(mc.options.keyCommand);
            forceKeyUp(mc.options.keyLoadHotbarActivator);
            forceKeyUp(mc.options.keySaveHotbarActivator);
            for (KeyMapping hotbar : mc.options.keyHotbarSlots) {
                forceKeyUp(hotbar);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] suppressNonMovementInputs failed safely: {}", t.toString());
        }
    }

    private static void forceKeyUp(@org.jetbrains.annotations.Nullable KeyMapping key) {
        if (key == null) {
            return;
        }
        try {
            key.setDown(false);
            while (key.consumeClick()) {
                // drain queued clicks
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLinkedRavenVisualMount(@org.jetbrains.annotations.NotNull Minecraft mc) {
        try {
            if (!active || linkedRavenEntityId < 0 || mc.level == null || mc.player == null) {
                return;
            }

            Entity entity = mc.level.getEntity(linkedRavenEntityId);
            if (!(entity instanceof RavenEntity raven) || raven.isRemoved() || !raven.isAlive()) {
                return;
            }

            boolean mounted = raven.getVehicle() == mc.player && mc.player.getPassengers().contains(raven);
            if (!mounted) {
                try {
                    if (raven.isPassenger() && raven.getVehicle() != mc.player) {
                        raven.stopRiding();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    raven.startRiding(mc.player, true);
                } catch (Throwable ignored) {
                }
                mounted = raven.getVehicle() == mc.player && mc.player.getPassengers().contains(raven);
            }

            float yaw = mc.player.getYRot();
            float pitch = mc.player.getXRot();
            if (!mounted) {
                raven.absMoveTo(mc.player.getX(), mc.player.getY(), mc.player.getZ(), yaw, pitch);
                raven.setOldPosAndRot();
            }
            raven.setYRot(yaw);
            raven.setYHeadRot(yaw);
            raven.yBodyRot = yaw;
            raven.setXRot(pitch);
            raven.yRotO = yaw;
            raven.yHeadRotO = yaw;
            raven.yBodyRotO = yaw;
            raven.xRotO = pitch;
            raven.setDeltaMovement(Vec3.ZERO);
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] ensureLinkedRavenVisualMount failed safely: {}", t.toString());
        }
    }

    private static void updateLinkedRavenFirstPersonVisibility(@org.jetbrains.annotations.NotNull Minecraft mc) {
        try {
            if (mc.level == null) {
                restoreHiddenLinkedRaven(mc);
                return;
            }

            boolean shouldHide = active
                    && linkedRavenEntityId >= 0
                    && mc.options.getCameraType().isFirstPerson();

            Entity linkedRaven = null;
            if (shouldHide) {
                linkedRaven = mc.level.getEntity(linkedRavenEntityId);
                if (linkedRaven == null || linkedRaven.isRemoved() || !linkedRaven.isAlive()) {
                    shouldHide = false;
                }
            }

            if (hiddenLinkedRavenApplied) {
                if (!shouldHide || linkedRaven == null || linkedRaven.getId() != hiddenLinkedRavenEntityId) {
                    restoreHiddenLinkedRaven(mc);
                }
            }

            if (shouldHide && linkedRaven != null) {
                if (!hiddenLinkedRavenApplied) {
                    hiddenLinkedRavenApplied = true;
                    hiddenLinkedRavenEntityId = linkedRaven.getId();
                    hiddenLinkedRavenOriginalInvisible = linkedRaven.isInvisible();
                }
                linkedRaven.setInvisible(true);
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] updateLinkedRavenFirstPersonVisibility failed safely: {}", t.toString());
        }
    }

    private static void restoreHiddenLinkedRaven(@org.jetbrains.annotations.Nullable Minecraft mc) {
        try {
            if (!hiddenLinkedRavenApplied) {
                return;
            }
            if (mc != null && mc.level != null && hiddenLinkedRavenEntityId >= 0) {
                Entity e = mc.level.getEntity(hiddenLinkedRavenEntityId);
                if (e != null && !e.isRemoved()) {
                    e.setInvisible(hiddenLinkedRavenOriginalInvisible);
                }
            }
        } catch (Throwable t) {
            LOG.debug("[RavenLinkClientController] restoreHiddenLinkedRaven failed safely: {}", t.toString());
        } finally {
            hiddenLinkedRavenApplied = false;
            hiddenLinkedRavenEntityId = -1;
            hiddenLinkedRavenOriginalInvisible = false;
        }
    }

    private static final class RavenCameraProxy extends Entity {

        private RavenCameraProxy(Level level) {
            super(EntityType.MARKER, level);
            this.noPhysics = true;
            this.setNoGravity(true);
        }

        private void applyRemoteState(double x, double y, double z, float yaw, float pitch) {
            // Use absolute move/rotate so xo/yo/zo and rot-old values stay coherent.
            // This prevents per-frame camera interpolation jumps.
            this.absMoveTo(x, y, z, yaw, pitch);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
            // no synced data needed for local-only camera proxy
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {
            // local-only entity: no persistence
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {
            // local-only entity: no persistence
        }

        @Override
        public boolean isInvisible() {
            return true;
        }

        @Override
        public boolean shouldRender(double x, double y, double z) {
            return false;
        }
    }

    private record VisionPixel(float x,
                               float y,
                               int size,
                               long bornAtMs,
                               long lifeMs,
                               int maxAlpha,
                               int r,
                               int g,
                               int b,
                               long phaseOffsetMs) {
    }

    private record ChunkViewDebug(int centerX, int centerZ, int radius) {
    }

    private record LevelRendererSectionDebug(int lastSectionX, int lastSectionY, int lastSectionZ) {
    }
}
