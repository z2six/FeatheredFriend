// neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/FeatheredFriendJeiPlugin.java
package net.z2six.featheredfriend.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.EnderPearlInventoryScreen;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
import net.z2six.featheredfriend.client.gui.SealStampScreen;
import net.z2six.featheredfriend.client.gui.ScrollViewScreen;
import net.z2six.featheredfriend.registry.FFItems;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/FeatheredFriendJeiPlugin.java
 *
 * JEI integration for FeatheredFriend.
 *
 * Currently:
 *  - Registers an ingredient info page for the sealed scroll item.
 *  - Registers GUI handlers for:
 *      * ScrollSealingScreen
 *      * SealStampScreen
 *      * EnderPearlInventoryScreen (attachments inventory)
 *      * ScrollViewScreen (sealed/opened scroll viewer)
 *    so JEI knows about our layout and tries not to overlap with it.
 *
 * NOTE:
 *  We do NOT (and cannot cleanly) force JEI's ingredient overlay to hide
 *  from here. That is done (best-effort) by JeiOverlayHider using reflection.
 *  Players can always toggle JEI with its keybind.
 */
@JeiPlugin
public class FeatheredFriendJeiPlugin implements IModPlugin {

    private static final Logger LOG = Constants.LOG;

    @Override
    public ResourceLocation getPluginUid() {
        // ResourceLocation(String, String) is private in modern MC,
        // so we use the parse helper instead.
        try {
            return ResourceLocation.parse(Constants.MOD_ID + ":jei_plugin");
        } catch (Throwable t) {
            LOG.error(
                    "FeatheredFriendJeiPlugin: Failed to create plugin UID, " +
                            "falling back to 'featheredfriend:jei_plugin_fallback'",
                    t
            );
            return ResourceLocation.parse("featheredfriend:jei_plugin_fallback");
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        LOG.debug("FeatheredFriendJeiPlugin: Registering JEI ingredient info for sealed scroll");

        try {
            ItemStack sealedScroll = new ItemStack(FFItems.SCROLL_SEALED.get());

            registration.addIngredientInfo(
                    sealedScroll,
                    VanillaTypes.ITEM_STACK,
                    Component.translatable("jei.featheredfriend.scroll_sealed.info")
            );
        } catch (Throwable t) {
            LOG.error(
                    "FeatheredFriendJeiPlugin: Failed to register JEI info for sealed scroll",
                    t
            );
        }
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // ---------------------------------------------------------------------
        // ScrollSealingScreen: tell JEI our entire window is GUI area.
        // ---------------------------------------------------------------------
        try {
            LOG.debug("FeatheredFriendJeiPlugin: Registering GUI handler for ScrollSealingScreen");

            registration.addGuiContainerHandler(
                    ScrollSealingScreen.class,
                    new IGuiContainerHandler<ScrollSealingScreen>() {

                        @Override
                        public List<Rect2i> getGuiExtraAreas(ScrollSealingScreen screen) {
                            try {
                                int x = 0;
                                int y = 0;
                                int width = screen.width;
                                int height = screen.height;

                                Rect2i fullScreen = new Rect2i(x, y, width, height);

                                LOG.debug(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas for ScrollSealingScreen -> {}x{} at {},{}",
                                        width, height, x, y
                                );

                                return Collections.singletonList(fullScreen);
                            } catch (Throwable t) {
                                LOG.error(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas failed for ScrollSealingScreen",
                                        t
                                );
                                return Collections.emptyList();
                            }
                        }
                    }
            );

        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: registerGuiHandlers failed for ScrollSealingScreen", t);
        }

        // ---------------------------------------------------------------------
        // SealStampScreen: same idea, tell JEI that our whole window is GUI area.
        // ---------------------------------------------------------------------
        try {
            LOG.debug("FeatheredFriendJeiPlugin: Registering GUI handler for SealStampScreen");

            registration.addGuiContainerHandler(
                    SealStampScreen.class,
                    new IGuiContainerHandler<SealStampScreen>() {
                        @Override
                        public List<Rect2i> getGuiExtraAreas(SealStampScreen screen) {
                            try {
                                int x = 0;
                                int y = 0;
                                int width = screen.width;
                                int height = screen.height;

                                Rect2i fullScreen = new Rect2i(x, y, width, height);

                                LOG.debug(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas for SealStampScreen -> {}x{} at {},{}",
                                        width, height, x, y
                                );

                                return Collections.singletonList(fullScreen);
                            } catch (Throwable t) {
                                LOG.error(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas failed for SealStampScreen",
                                        t
                                );
                                return Collections.emptyList();
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: registerGuiHandlers failed for SealStampScreen", t);
        }

        // ---------------------------------------------------------------------
        // EnderPearlInventoryScreen: attachments inventory GUI
        //  - Mark entire window as GUI area so JEI moves away from it.
        // ---------------------------------------------------------------------
        try {
            LOG.debug("FeatheredFriendJeiPlugin: Registering GUI handler for EnderPearlInventoryScreen");

            registration.addGuiContainerHandler(
                    EnderPearlInventoryScreen.class,
                    new IGuiContainerHandler<EnderPearlInventoryScreen>() {
                        @Override
                        public List<Rect2i> getGuiExtraAreas(EnderPearlInventoryScreen screen) {
                            try {
                                int x = 0;
                                int y = 0;
                                int width = screen.width;
                                int height = screen.height;

                                Rect2i fullScreen = new Rect2i(x, y, width, height);

                                LOG.debug(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas for EnderPearlInventoryScreen -> {}x{} at {},{}",
                                        width, height, x, y
                                );

                                return Collections.singletonList(fullScreen);
                            } catch (Throwable t) {
                                LOG.error(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas failed for EnderPearlInventoryScreen",
                                        t
                                );
                                return Collections.emptyList();
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: registerGuiHandlers failed for EnderPearlInventoryScreen", t);
        }

        // ---------------------------------------------------------------------
        // ScrollViewScreen: sealed/opened scroll viewer GUI
        //  - Mark entire window as GUI area so JEI moves away from it.
        // ---------------------------------------------------------------------
        try {
            LOG.debug("FeatheredFriendJeiPlugin: Registering GUI handler for ScrollViewScreen");

            registration.addGuiContainerHandler(
                    ScrollViewScreen.class,
                    new IGuiContainerHandler<ScrollViewScreen>() {
                        @Override
                        public List<Rect2i> getGuiExtraAreas(ScrollViewScreen screen) {
                            try {
                                int x = 0;
                                int y = 0;
                                int width = screen.width;
                                int height = screen.height;

                                Rect2i fullScreen = new Rect2i(x, y, width, height);

                                LOG.debug(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas for ScrollViewScreen -> {}x{} at {},{}",
                                        width, height, x, y
                                );

                                return Collections.singletonList(fullScreen);
                            } catch (Throwable t) {
                                LOG.error(
                                        "FeatheredFriendJeiPlugin: getGuiExtraAreas failed for ScrollViewScreen",
                                        t
                                );
                                return Collections.emptyList();
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: registerGuiHandlers failed for ScrollViewScreen", t);
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        try {
            JeiOverlayHider.setRuntime(jeiRuntime);
            LOG.debug("FeatheredFriendJeiPlugin: JEI runtime available and passed to JeiOverlayHider");
        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: Failed to pass JEI runtime to JeiOverlayHider", t);
        }
    }
}
