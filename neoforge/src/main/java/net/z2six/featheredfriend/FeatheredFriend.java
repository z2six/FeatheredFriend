// neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.registry.FFCreativeTabsNeoForge;
import net.z2six.featheredfriend.registry.FFNeoForgeItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
 *
 * Main NeoForge entrypoint.
 *
 * NOTE:
 * - Client-only stuff (calendar popup, screen registration) is handled by
 *   FFNeoForgeClient, which is invoked via `clientModInitializer` in mods.toml:
 *
 *     clientModInitializer="net.z2six.featheredfriend.client.FFNeoForgeClient::init"
 *
 * - This class only does common + NeoForge-side registry / config wiring.
 */
@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend(IEventBus modEventBus) {
        LOG.info("[FeatheredFriend] Initializing NeoForge side");

        try {
            // Common init (shared logic)
            CommonClass.init();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] CommonClass.init() failed", t);
        }

        try {
            // Register SERVER calendar config (NeoForge-side, server-authoritative)
            FFCalendarConfig.register();
            LOG.debug("[FeatheredFriend] FFCalendarConfig registered");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register FFCalendarConfig", t);
        }

        try {
            // NeoForge-specific registries
            FFNeoForgeItems.register(modEventBus);
            LOG.debug("[FeatheredFriend] FFNeoForgeItems registered");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register items", t);
        }

        try {
            FFCreativeTabsNeoForge.register(modEventBus);
            LOG.debug("[FeatheredFriend] FFCreativeTabsNeoForge registered");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register creative tab hooks", t);
        }

        try {
            FFNeoForgeMenus.register(modEventBus);
            LOG.debug("[FeatheredFriend] FFNeoForgeMenus registered");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register menus", t);
        }

        LOG.info("[FeatheredFriend] NeoForge initialization complete");
    }
}
