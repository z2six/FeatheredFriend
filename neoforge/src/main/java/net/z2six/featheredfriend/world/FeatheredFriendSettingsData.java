// neoforge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * neoforge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
 *
 * World-owned settings for FeatheredFriend.
 *
 * Currently stores:
 *  - autoSummonOnScroll: whether holding a sealed scroll should automatically
 *    summon the tamed raven (global, server-owned). Default: true.
 *  - chatDisabled: whether global player chat is disabled (server-owned).
 *    Default: true (chat disabled).
 *
 * Design:
 *  - Lives on the SERVER, attached to the OVERWORLD's data storage.
 *  - Access via FeatheredFriendSettingsData.get(ServerLevel).
 *  - Public setters mark the data as dirty and never throw.
 */
public class FeatheredFriendSettingsData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_settings";

    private static final String KEY_AUTO_SUMMON = "AutoSummonOnScroll";
    private static final String KEY_CHAT_DISABLED = "ChatDisabled";

    // Defaults
    public static final boolean DEFAULT_AUTO_SUMMON = true;
    public static final boolean DEFAULT_CHAT_DISABLED = true;

    private boolean autoSummonOnScroll = DEFAULT_AUTO_SUMMON;
    private boolean chatDisabled = DEFAULT_CHAT_DISABLED;

    // ---------------------------------------------------------------------
    // Construction / factory
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // SavedData overrides
    // ---------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] save failed safely: {}", t.toString());
        }
        return tag;
    }

    // ---------------------------------------------------------------------
    // NBT (de)serialization
    // ---------------------------------------------------------------------

    private void readFromNbt(@NotNull CompoundTag tag) {
        try {
            if (tag.contains(KEY_AUTO_SUMMON, Tag.TAG_BYTE)) {
                autoSummonOnScroll = tag.getBoolean(KEY_AUTO_SUMMON);
            } else {
                autoSummonOnScroll = DEFAULT_AUTO_SUMMON;
            }

            if (tag.contains(KEY_CHAT_DISABLED, Tag.TAG_BYTE)) {
                chatDisabled = tag.getBoolean(KEY_CHAT_DISABLED);
            } else {
                chatDisabled = DEFAULT_CHAT_DISABLED;
            }

            LOG.info("[FeatheredFriendSettingsData] Loaded settings: autoSummonOnScroll={} chatDisabled={}",
                    autoSummonOnScroll, chatDisabled);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] readFromNbt failed safely: {}", t.toString());
            autoSummonOnScroll = DEFAULT_AUTO_SUMMON;
            chatDisabled = DEFAULT_CHAT_DISABLED;
        }
    }

    private void writeToNbt(@NotNull CompoundTag tag) {
        try {
            tag.putBoolean(KEY_AUTO_SUMMON, autoSummonOnScroll);
            tag.putBoolean(KEY_CHAT_DISABLED, chatDisabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] writeToNbt failed safely: {}", t.toString());
        }
    }

    // ---------------------------------------------------------------------
    // Accessor for the saved data instance
    // ---------------------------------------------------------------------

    /**
     * Returns the global FeatheredFriendSettingsData instance, attached to the
     * OVERWORLD's data storage.
     *
     * You can call this with any ServerLevel; it will internally resolve the overworld.
     */
    @NotNull
    public static FeatheredFriendSettingsData get(@NotNull ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) {
                overworld = level;
            }

            var storage = overworld.getDataStorage();
            SavedData.Factory<FeatheredFriendSettingsData> factory =
                    new SavedData.Factory<>(FeatheredFriendSettingsData::create, FeatheredFriendSettingsData::load);

            return storage.computeIfAbsent(factory, DATA_NAME);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] get(...) failed safely, returning volatile defaults: {}", t.toString());
            return new FeatheredFriendSettingsData();
        }
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public boolean isAutoSummonOnScrollEnabled() {
        return autoSummonOnScroll;
    }

    public boolean isChatDisabled() {
        return chatDisabled;
    }

    public void setAutoSummonOnScrollEnabled(boolean enabled) {
        try {
            if (this.autoSummonOnScroll == enabled) {
                return;
            }
            this.autoSummonOnScroll = enabled;
            this.setDirty();
            LOG.info("[FeatheredFriendSettingsData] autoSummonOnScroll set to {}", enabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] setAutoSummonOnScrollEnabled failed safely: {}", t.toString());
        }
    }

    public void setChatDisabled(boolean disabled) {
        try {
            if (this.chatDisabled == disabled) {
                return;
            }
            this.chatDisabled = disabled;
            this.setDirty();
            LOG.info("[FeatheredFriendSettingsData] chatDisabled set to {}", disabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] setChatDisabled failed safely: {}", t.toString());
        }
    }
}
