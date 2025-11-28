// neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.z2six.featheredfriend.registry.FFCreativeTabsNeoForge;
import net.z2six.featheredfriend.registry.FFNeoForgeItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend(IEventBus eventBus) {

        LOG.info("[FeatheredFriend] Initializing NeoForge side");

        // Common init
        CommonClass.init();

        // NeoForge-specific registries
        FFNeoForgeItems.register(eventBus);
        FFCreativeTabsNeoForge.register(eventBus);
        FFNeoForgeMenus.register(eventBus);

        LOG.info("[FeatheredFriend] NeoForge initialization complete");
    }
}
