// neoforge/src/main/java/net/z2six/featheredfriend/integration/jei/FeatheredFriendJeiPlugin.java
package net.z2six.featheredfriend.integration.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.registry.FFItems;
import org.slf4j.Logger;

/**
 * JEI integration for FeatheredFriend.
 *
 * Adds an info page for the sealed scroll explaining how to obtain it.
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
            LOG.error("FeatheredFriendJeiPlugin: Failed to create plugin UID, falling back to 'featheredfriend:jei_plugin_fallback'", t);
            return ResourceLocation.parse("featheredfriend:jei_plugin_fallback");
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {

        LOG.debug("FeatheredFriendJeiPlugin: Registering JEI ingredient info");

        try {
            ItemStack sealedScroll = new ItemStack(FFItems.SCROLL_SEALED.get());

            registration.addIngredientInfo(
                    sealedScroll,
                    VanillaTypes.ITEM_STACK,
                    Component.translatable("jei.featheredfriend.scroll_sealed.info")
            );
        } catch (Throwable t) {
            LOG.error("FeatheredFriendJeiPlugin: Failed to register JEI info for sealed scroll", t);
        }
    }
}
