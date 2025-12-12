// neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
package net.z2six.featheredfriend;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.z2six.featheredfriend.client.FFNeoForgeClient;
import net.z2six.featheredfriend.config.FFCalendarConfig;
import net.z2six.featheredfriend.network.FFNetwork;
import net.z2six.featheredfriend.registry.FFCreativeTabsNeoForge;
import net.z2six.featheredfriend.registry.FFNeoForgeItems;
import net.z2six.featheredfriend.registry.FFNeoForgeMenus;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/FeatheredFriend.java
 *
 * NeoForge entrypoint for FeatheredFriend.
 *
 * Responsibilities:
 *  - Invoke common init code.
 *  - Register NeoForge-specific registries (items, menus, creative tabs).
 *  - Register server-side calendar config.
 *  - Hook client-only registration (menu screens) via the mod event bus.
 *  - Register payload handlers via mod event bus listener (NeoForge 1.21.1 safe).
 */
@Mod(Constants.MOD_ID)
public class FeatheredFriend {

    private static final Logger LOG = LogUtils.getLogger();

    public FeatheredFriend(IEventBus modEventBus) {
        LOG.info("[FeatheredFriend] Initializing NeoForge side");

        // Common initialization (shared between platforms)
        try {
            CommonClass.init();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] CommonClass.init() failed", t);
        }

        // Server-side calendar config (NeoForge-specific)
        try {
            FFCalendarConfig.register();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFCalendarConfig.register() failed", t);
        }

        // NeoForge registries
        try {
            FFNeoForgeItems.register(modEventBus);
            FFCreativeTabsNeoForge.register(modEventBus);
            FFNeoForgeMenus.register(modEventBus);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to register NeoForge registries", t);
        }

        // Networking: payload handler registration (MOD bus event)
        try {
            modEventBus.addListener(FFNetwork::register);
            LOG.info("[FeatheredFriend] Hooked FFNetwork payload registration listener");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook FFNetwork payload registration listener", t);
        }

        // Legacy no-op call (kept for compatibility with your current structure)
        try {
            FFNetwork.registerSimpleMessages();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] FFNetwork.registerSimpleMessages() failed", t);
        }

        // Client-only: menu screens (called only on physical client)
        try {
            modEventBus.addListener(FFNeoForgeClient::onRegisterMenuScreens);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriend] Failed to hook client menu screen registration", t);
        }

        LOG.info("[FeatheredFriend] NeoForge initialization complete");
    }
}
