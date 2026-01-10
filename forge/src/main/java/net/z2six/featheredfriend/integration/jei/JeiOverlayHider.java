// neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/JeiOverlayHider.java
package net.z2six.featheredfriend.integration.jei;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.EnderPearlInventoryScreen;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.client.gui.ScrollViewScreen;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/JeiOverlayHider.java
 *
 * JeiOverlayHider
 *
 * Client-only helper that hides the JEI ingredient list overlay while our
 * custom FeatheredFriend GUIs are open:
 *  - ScrollSealingScreen
 *  - SealStampScreen
 *  - EnderPearlInventoryScreen (attachments inventory GUI)
 *  - ScrollViewScreen (sealed/opened scroll viewer)
 *
 * Design:
 *  - JEI runtime (IJeiRuntime) is stored as an Object to avoid tight coupling.
 *  - Each client tick, we check the current screen:
 *      * If it's one of our GUIs -> hide overlay.
 *      * Otherwise -> restore overlay (if we hid it).
 *  - We use reflection to:
 *      * Call runtime.getIngredientListOverlay()
 *      * On the overlay, find appropriate visibility getter/setter methods by name pattern:
 *          - setVisible / isVisible
 *          - setEnabled / isEnabled
 *          - setListDisplayed / isListDisplayed
 *          - any single-boolean-arg "set*" that looks like visibility control
 *  - If anything fails, we log and gracefully give up, never crashing the game.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class JeiOverlayHider {

    private static final Logger LOG = LogUtils.getLogger();

    // JEI runtime (IJeiRuntime), stored as Object to avoid direct dependency.
    private static volatile Object jeiRuntime = null;

    // Remember previous visible/enabled state so we can restore it.
    private static volatile Boolean previousOverlayState = null;
    private static volatile boolean overlayHiddenForOurScreen = false;

    // To avoid spamming logs, track that we've logged "runtime is null" at least once
    private static volatile boolean loggedMissingRuntime = false;

    private JeiOverlayHider() {
        // no-op
    }

    /**
     * Called from FeatheredFriendJeiPlugin.onRuntimeAvailable(IJeiRuntime).
     */
    public static void setRuntime(@NotNull Object runtime) {
        try {
            jeiRuntime = runtime;
            loggedMissingRuntime = false;
            LOG.debug("[JeiOverlayHider] JEI runtime received: {}", runtime.getClass().getName());
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] Failed to store JEI runtime", t);
        }
    }

    /**
     * Runs every client tick (POST). We:
     *  - Check the current screen.
     *  - If it's one of our custom GUIs, hide the JEI overlay.
     *  - Otherwise, restore the overlay if we previously hid it.
     */
    @SubscribeEvent
    public static void onClientTick(@NotNull ClientTickEvent.Post event) {
        try {
            if (jeiRuntime == null) {
                if (!loggedMissingRuntime) {
                    LOG.debug("[JeiOverlayHider] JEI runtime is null; overlay will not be modified yet");
                    loggedMissingRuntime = true;
                }
                return; // JEI missing or not initialized yet
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }

            // Our GUIs where we want JEI overlay hidden:
            //  - ScrollSealingScreen
            //  - SealStampScreen (etching GUI)
            //  - EnderPearlInventoryScreen (attachments inventory GUI)
            //  - ScrollViewScreen (sealed/opened scroll viewer)
            boolean isOurScreen =
                    (mc.screen instanceof ScrollSealingScreen) ||
                            (mc.screen instanceof SealStampScreen) ||
                            (mc.screen instanceof EnderPearlInventoryScreen) ||
                            (mc.screen instanceof ScrollViewScreen);

            if (isOurScreen) {
                hideOverlayIfNeeded();
            } else {
                restoreOverlayIfNeeded();
            }
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] onClientTick failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static void hideOverlayIfNeeded() {
        if (overlayHiddenForOurScreen) {
            return; // already hidden
        }

        try {
            Object overlay = getIngredientOverlay(jeiRuntime);
            if (overlay == null) {
                return;
            }

            VisibilityMethods methods = findVisibilityMethods(overlay);
            if (methods.setter == null) {
                LOG.debug("[JeiOverlayHider] No suitable visibility setter found on JEI overlay; cannot hide it safely");
                return;
            }

            // Remember previous state (if we have a getter), then disable
            if (methods.getter != null) {
                try {
                    Object old = methods.getter.invoke(overlay);
                    if (old instanceof Boolean b) {
                        previousOverlayState = b;
                        LOG.debug("[JeiOverlayHider] Recorded previous JEI overlay state: {}", b);
                    }
                } catch (Throwable t) {
                    LOG.error("[JeiOverlayHider] Failed to invoke visibility getter on overlay", t);
                }
            }

            methods.setter.invoke(overlay, Boolean.FALSE);
            overlayHiddenForOurScreen = true;
            LOG.debug("[JeiOverlayHider] JEI ingredient overlay hidden for FeatheredFriend GUI using setter '{}'",
                    methods.setter.getName());
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] Failed to hide JEI overlay", t);
        }
    }

    private static void restoreOverlayIfNeeded() {
        if (!overlayHiddenForOurScreen) {
            return;
        }

        try {
            Object overlay = getIngredientOverlay(jeiRuntime);
            if (overlay == null) {
                LOG.debug("[JeiOverlayHider] Overlay instance is null during restore; clearing state");
                overlayHiddenForOurScreen = false;
                previousOverlayState = null;
                return;
            }

            VisibilityMethods methods = findVisibilityMethods(overlay);
            if (methods.setter == null) {
                LOG.debug("[JeiOverlayHider] No suitable visibility setter found during restore; clearing state");
                overlayHiddenForOurScreen = false;
                previousOverlayState = null;
                return;
            }

            boolean newState = previousOverlayState != null ? previousOverlayState : true;
            try {
                methods.setter.invoke(overlay, newState);
                LOG.debug("[JeiOverlayHider] JEI ingredient overlay restored to {} using setter '{}'",
                        newState, methods.setter.getName());
            } catch (Throwable t) {
                LOG.error("[JeiOverlayHider] Failed to invoke visibility setter on overlay during restore", t);
            }
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] Failed to restore JEI overlay", t);
        } finally {
            overlayHiddenForOurScreen = false;
            previousOverlayState = null;
        }
    }

    /**
     * Reflectively calls runtime.getIngredientListOverlay().
     */
    private static Object getIngredientOverlay(@NotNull Object runtime) {
        try {
            Class<?> runtimeClass = runtime.getClass();
            Method getOverlay;
            try {
                getOverlay = runtimeClass.getMethod("getIngredientListOverlay");
            } catch (NoSuchMethodException ignored) {
                LOG.debug("[JeiOverlayHider] getIngredientListOverlay() not found on JEI runtime class {}", runtimeClass.getName());
                return null;
            }

            Object overlay = getOverlay.invoke(runtime);
            if (overlay == null) {
                LOG.debug("[JeiOverlayHider] JEI getIngredientListOverlay() returned null");
                return null;
            }

            LOG.debug("[JeiOverlayHider] Obtained JEI ingredient overlay instance: {}",
                    overlay.getClass().getName());
            return overlay;
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] Failed to obtain JEI ingredient overlay via reflection", t);
            return null;
        }
    }

    /**
     * Holds the visibility getter/setter pair for an overlay object.
     */
    private static final class VisibilityMethods {
        final Method getter;
        final Method setter;

        VisibilityMethods(Method getter, Method setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }

    /**
     * Try to find suitable visibility getter/setter methods on the overlay object.
     */
    private static VisibilityMethods findVisibilityMethods(@NotNull Object overlay) {
        Class<?> clazz = overlay.getClass();
        Method bestSetter = null;
        Method bestGetter = null;

        try {
            Method[] methods = clazz.getMethods();
            List<Method> setterCandidates = new ArrayList<>();
            List<Method> getterCandidates = new ArrayList<>();

            for (Method m : methods) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();

                // Setter candidate: set*(boolean)
                if (name.startsWith("set") &&
                        params.length == 1 &&
                        (params[0] == boolean.class || params[0] == Boolean.class)) {
                    setterCandidates.add(m);
                }

                // Getter candidate: is*/get* returning boolean
                if (params.length == 0 &&
                        (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) &&
                        (name.startsWith("is") || name.startsWith("get"))) {
                    getterCandidates.add(m);
                }
            }

            bestSetter = pickBestVisibilitySetter(setterCandidates);
            bestGetter = pickMatchingGetter(bestSetter, getterCandidates);

            if (bestSetter != null) {
                bestSetter.setAccessible(true);
            }
            if (bestGetter != null) {
                bestGetter.setAccessible(true);
            }

            if (bestSetter != null) {
                LOG.debug("[JeiOverlayHider] Using visibility setter '{}' and getter '{}' on JEI overlay class {}",
                        bestSetter.getName(),
                        bestGetter != null ? bestGetter.getName() : "<none>",
                        clazz.getName());
            } else {
                LOG.debug("[JeiOverlayHider] No suitable visibility setter discovered on JEI overlay class {}",
                        clazz.getName());
            }
        } catch (Throwable t) {
            LOG.error("[JeiOverlayHider] Error while discovering visibility methods on overlay class {}", clazz.getName(), t);
            bestSetter = null;
            bestGetter = null;
        }

        return new VisibilityMethods(bestGetter, bestSetter);
    }

    private static Method pickBestVisibilitySetter(List<Method> candidates) {
        Method best = null;
        int bestScore = -1;

        for (Method m : candidates) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            int score = 0;

            if (n.contains("visible")) score += 5;
            if (n.contains("display")) score += 4;
            if (n.contains("show"))    score += 4;
            if (n.contains("enable"))  score += 3;
            if (n.contains("list"))    score += 1;
            if (n.equals("setvisible")) score += 2;

            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }

        return best;
    }

    private static Method pickMatchingGetter(Method setter, List<Method> getterCandidates) {
        if (setter == null) {
            return null;
        }

        String setterName = setter.getName();
        String suffix;

        if (setterName.startsWith("set")) {
            suffix = setterName.substring(3);
        } else {
            suffix = setterName;
        }

        Method best = null;
        int bestScore = -1;

        for (Method m : getterCandidates) {
            String n = m.getName();
            int score = 0;

            if (n.equalsIgnoreCase("is" + suffix) || n.equalsIgnoreCase("get" + suffix)) {
                score += 5;
            }

            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.contains("visible")) score += 4;
            if (lower.contains("display")) score += 3;
            if (lower.contains("show"))    score += 3;
            if (lower.contains("enable"))  score += 2;

            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }

        return best;
    }
}
