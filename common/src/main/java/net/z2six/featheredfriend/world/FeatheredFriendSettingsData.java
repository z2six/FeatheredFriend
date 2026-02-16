// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.util.datafix.DataFixTypes;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
 *
 * World-owned settings for FeatheredFriend.
 *
 * Currently stores:
 *  - chatDisabled: whether global player chat is disabled (server-owned).
 *
 * Design:
 *  - Lives on the SERVER, attached to the OVERWORLD's data storage.
 *  - Access via FeatheredFriendSettingsData.get(ServerLevel).
 *  - Public setters mark the data as dirty and never throw.
 */
public class FeatheredFriendSettingsData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_settings";

    private static final String KEY_CHAT_DISABLED = "ChatDisabled";

    // If NBT is missing, we now default from server config.
    public static final boolean DEFAULT_CHAT_DISABLED = false;

    private boolean chatDisabled = DEFAULT_CHAT_DISABLED;

    public FeatheredFriendSettingsData() {
        // no-op
    }

    public static FeatheredFriendSettingsData create() {
        return new FeatheredFriendSettingsData();
    }

    public static FeatheredFriendSettingsData load(CompoundTag tag, HolderLookup.Provider lookupProvider) {
        FeatheredFriendSettingsData data = new FeatheredFriendSettingsData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] save failed safely: {}", t.toString());
        }
        return tag;
    }

    private void readFromNbt(@NotNull CompoundTag tag) {
        try {
            if (tag.contains(KEY_CHAT_DISABLED, Tag.TAG_BYTE)) {
                chatDisabled = tag.getBoolean(KEY_CHAT_DISABLED);
            } else {
                // NEW: config-driven default when missing.
                boolean cfgDefault = DEFAULT_CHAT_DISABLED;
                try {
                    cfgDefault = Services.PLATFORM.getChatDisabledDefault();
                } catch (Throwable ignored) {
                }
                chatDisabled = cfgDefault;
                LOG.debug("[FeatheredFriendSettingsData] ChatDisabled missing in NBT; defaulting from config: {}", chatDisabled);
            }

            LOG.debug("[FeatheredFriendSettingsData] Loaded settings: chatDisabled={}", chatDisabled);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] readFromNbt failed safely: {}", t.toString());
            chatDisabled = DEFAULT_CHAT_DISABLED;
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        try {
            tag.putBoolean(KEY_CHAT_DISABLED, chatDisabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] writeToNbt failed safely: {}", t.toString());
        }
    }

    @NotNull
    public static FeatheredFriendSettingsData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            var storage = overworld.getDataStorage();
            SavedData.Factory<FeatheredFriendSettingsData> factory =
                    new SavedData.Factory<>(FeatheredFriendSettingsData::create, FeatheredFriendSettingsData::load, DataFixTypes.LEVEL);

            return storage.computeIfAbsent(factory, DATA_NAME);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] get(...) failed safely, returning volatile defaults: {}", t.toString());
            return new FeatheredFriendSettingsData();
        }
    }

    public boolean isChatDisabled() {
        return chatDisabled;
    }

    public void setChatDisabled(boolean disabled) {
        try {
            if (this.chatDisabled == disabled) {
                return;
            }
            this.chatDisabled = disabled;
            this.setDirty();
            LOG.debug("[FeatheredFriendSettingsData] chatDisabled set to {}", disabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] setChatDisabled failed safely: {}", t.toString());
        }
    }
}
