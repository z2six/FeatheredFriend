// MainFile: forge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
package net.z2six.featheredfriend.world;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.config.FFCalendarConfig;

import org.slf4j.Logger;

/**
 * forge/src/main/java/net/z2six/featheredfriend/world/FeatheredFriendSettingsData.java
 *
 * World-owned settings for FeatheredFriend.
 *
 * Currently stores:
 *  - chatDisabled: whether global player chat is disabled (server-owned).
 *
 * NOTE:
 *  - autoSummonOnScroll was previously world-owned; you requested it to be client config.
 *    We keep the old NBT key around for backward compatibility, but the GUI will now
 *    edit a client config instead.
 *
 * Design:
 *  - Lives on the SERVER, attached to the OVERWORLD's data storage.
 *  - Access via FeatheredFriendSettingsData.get(ServerLevel).
 *  - Public setters mark the data as dirty and never throw.
 */
public class FeatheredFriendSettingsData extends SavedData {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String DATA_NAME = Constants.MOD_ID + "_settings";

    // Kept for backward compatibility only.
    private static final String KEY_AUTO_SUMMON = "AutoSummonOnScroll";

    private static final String KEY_CHAT_DISABLED = "ChatDisabled";

    // Back-compat default (not used anymore as authoritative)
    public static final boolean DEFAULT_AUTO_SUMMON = true;

    // If NBT is missing, we now default from server config.
    public static final boolean DEFAULT_CHAT_DISABLED = true;

    // Back-compat only
    private boolean autoSummonOnScroll = DEFAULT_AUTO_SUMMON;

    private boolean chatDisabled = DEFAULT_CHAT_DISABLED;

    public FeatheredFriendSettingsData() {
        // no-op
    }

    public static FeatheredFriendSettingsData create() {
        return new FeatheredFriendSettingsData();
    }

    public static FeatheredFriendSettingsData load(CompoundTag tag) {
        FeatheredFriendSettingsData data = new FeatheredFriendSettingsData();
        data.readFromNbt(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        try {
            writeToNbt(tag);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] save failed safely: {}", t.toString());
        }
        return tag;
    }

    private void readFromNbt(CompoundTag tag) {
        try {
            // Backward compatibility only.
            if (tag.contains(KEY_AUTO_SUMMON, Tag.TAG_BYTE)) {
                autoSummonOnScroll = tag.getBoolean(KEY_AUTO_SUMMON);
            } else {
                autoSummonOnScroll = DEFAULT_AUTO_SUMMON;
            }

            if (tag.contains(KEY_CHAT_DISABLED, Tag.TAG_BYTE)) {
                chatDisabled = tag.getBoolean(KEY_CHAT_DISABLED);
            } else {
                // NEW: config-driven default when missing.
                boolean cfgDefault = DEFAULT_CHAT_DISABLED;
                try {
                    cfgDefault = FFCalendarConfig.getChatDisabledDefault();
                } catch (Throwable ignored) {
                }
                chatDisabled = cfgDefault;
                LOG.info("[FeatheredFriendSettingsData] ChatDisabled missing in NBT; defaulting from config: {}", chatDisabled);
            }

            LOG.info("[FeatheredFriendSettingsData] Loaded settings: chatDisabled={}", chatDisabled);

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] readFromNbt failed safely: {}", t.toString());
            autoSummonOnScroll = DEFAULT_AUTO_SUMMON;
            chatDisabled = DEFAULT_CHAT_DISABLED;
        }
    }

    private void writeToNbt(CompoundTag tag) {
        try {
            // Backward compatibility only.
            tag.putBoolean(KEY_AUTO_SUMMON, autoSummonOnScroll);

            tag.putBoolean(KEY_CHAT_DISABLED, chatDisabled);
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] writeToNbt failed safely: {}", t.toString());
        }
    }


    public static FeatheredFriendSettingsData get(ServerLevel level) {
        try {
            ServerLevel overworld = level.getServer().overworld();
            if (overworld == null) overworld = level;

            var storage = overworld.getDataStorage();
            return storage.computeIfAbsent(
                    FeatheredFriendSettingsData::load,
                    FeatheredFriendSettingsData::create,
                    DATA_NAME
            );

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendSettingsData] get(...) failed safely, returning volatile defaults: {}", t.toString());
            return new FeatheredFriendSettingsData();
        }
    }

    // Back-compat only (not authoritative anymore)
    public boolean isAutoSummonOnScrollEnabled() {
        return autoSummonOnScroll;
    }

    public boolean isChatDisabled() {
        return chatDisabled;
    }

    // Back-compat only (not authoritative anymore)
    public void setAutoSummonOnScrollEnabled(boolean enabled) {
        try {
            if (this.autoSummonOnScroll == enabled) {
                return;
            }
            this.autoSummonOnScroll = enabled;
            this.setDirty();
            LOG.info("[FeatheredFriendSettingsData] (back-compat) autoSummonOnScroll set to {}", enabled);
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
