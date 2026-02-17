package net.z2six.featheredfriend.client.raven;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.SectionPos;
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
import net.neoforged.neoforge.common.NeoForge;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import net.z2six.featheredfriend.platform.Services;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client Raven Link camera + input sender.
 */
public final class RavenLinkClientController {

    private static final Logger LOG = LogUtils.getLogger();
    private static final boolean ENABLE_RAVEN_LINK_DIAGNOSTICS = false;

    private static volatile boolean registered = false;
    private static volatile boolean active = false;
    private static volatile int linkedRavenEntityId = -1;
    private static volatile long localEndMillis = 0L;
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

    public static void beginFromServer(int ravenEntityId, int durationTicks) {
        try {
            active = true;
            linkedRavenEntityId = ravenEntityId;
            long durMs = Math.max(0L, (long) durationTicks * 50L);
            localEndMillis = System.currentTimeMillis() + durMs;
            escWasDown = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                anchorYaw = mc.player.getYRot();
                anchorPitch = mc.player.getXRot();
                lookYaw = anchorYaw;
                lookPitch = anchorPitch;
                if (!hideGuiCaptured) {
                    savedHideGui = mc.options.hideGui;
                    hideGuiCaptured = true;
                }
                mc.options.hideGui = true;
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
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            if (!active) {
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

            if (mc.getCameraEntity() != mc.player) {
                mc.setCameraEntity(mc.player);
            }
            ensureLinkedRavenVisualMount(mc);
            suppressNonMovementInputs(mc);
            if (!mc.options.hideGui) {
                mc.options.hideGui = true;
            }

            boolean forward = mc.options.keyUp.isDown();
            boolean backward = mc.options.keyDown.isDown();
            boolean left = mc.options.keyLeft.isDown();
            boolean right = mc.options.keyRight.isDown();
            boolean ascend = mc.options.keyJump.isDown();
            boolean descend = mc.options.keyShift.isDown();

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

            if (mc.getCameraEntity() != mc.player) {
                mc.setCameraEntity(mc.player);
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

        active = false;
        linkedRavenEntityId = -1;
        localEndMillis = 0L;
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
        hiddenLinkedRavenEntityId = -1;
        hiddenLinkedRavenOriginalInvisible = false;
        hiddenLinkedRavenApplied = false;
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

    private record ChunkViewDebug(int centerX, int centerZ, int radius) {
    }

    private record LevelRendererSectionDebug(int lastSectionX, int lastSectionY, int lastSectionZ) {
    }
}
