// neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/FeatheredFriendJeiPlugin.java
package net.z2six.featheredfriend.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.client.gui.ScrollSealingScreen;
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
 *  - Registers GUI handlers for ScrollSealingScreen so JEI knows about our layout.
 *
 * NOTE:
 *  We do NOT (and cannot cleanly) force JEI's ingredient overlay to hide.
 *  JEI does not expose a stable public API in 1.21.x to fully toggle that
 *  overlay from another mod. Players can still toggle JEI with its keybind.
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
        try {
            LOG.debug("FeatheredFriendJeiPlugin: Registering GUI handler for ScrollSealingScreen");

            registration.addGuiContainerHandler(
                    ScrollSealingScreen.class,
                    new IGuiContainerHandler<ScrollSealingScreen>() {

                        /**
                         * Tell JEI about extra GUI areas so it can position its overlay
                         * around our custom layout.
                         *
                         * Here we just say "our entire screen is GUI area", which tends
                         * to make JEI try very hard not to overlap it. This does NOT
                         * fully hide JEI, but it helps avoid overlay collisions.
                         */
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

                                // Must return a List, not a generic Collection.
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
            LOG.error("FeatheredFriendJeiPlugin: registerGuiHandlers failed", t);
        }
    }
}
