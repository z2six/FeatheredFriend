// MainFile: common/src/main/java/net/z2six/featheredfriend/util/ScrollAttachmentNbtUtil.java
package net.z2six.featheredfriend.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * common/src/main/java/net/z2six/featheredfriend/util/ScrollAttachmentNbtUtil.java
 *
 * Utility for reading/writing SealedScroll.Attachments which your logs show as:
 *   SealedScroll: {
 *     Attachments: [
 *       { id: "minecraft:oak_log", Count: 64 },
 *       ...
 *     ]
 *   }
 *
 * IMPORTANT (Forge 1.20.1):
 * - DataComponents / CustomData component do not exist.
 * - We preserve the exact same "CustomData" field in the attachment entry,
 *   but apply it to ItemStacks by storing it under stack.getOrCreateTag().put("CustomData", ...).
 */
public final class ScrollAttachmentNbtUtil {

    private static final Logger LOG = LogUtils.getLogger();

    public static final String ROOT_KEY = "SealedScroll";
    public static final String ATTACHMENTS_KEY = "Attachments";

    // Optional per-item attachment data keys
    private static final String LEGACY_TAG_KEY = "tag";
    private static final String CUSTOM_DATA_KEY = "CustomData";

    // Where we store the former "minecraft:custom_data" payload in 1.20.1
    private static final String STACK_CUSTOM_DATA_KEY = "CustomData";

    private ScrollAttachmentNbtUtil() {}

    public static boolean hasAnyAttachments(@NotNull CompoundTag sealedScrollTag) {
        try {
            if (sealedScrollTag.isEmpty()) return false;
            if (!sealedScrollTag.contains(ATTACHMENTS_KEY, Tag.TAG_LIST)) return false;
            ListTag list = sealedScrollTag.getList(ATTACHMENTS_KEY, Tag.TAG_COMPOUND);
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            LOG.error("[ScrollAttachmentNbtUtil] hasAnyAttachments failed", t);
            return false;
        }
    }

    public static @NotNull List<ItemStack> readAttachments(@NotNull CompoundTag sealedScrollTag, int max) {
        List<ItemStack> out = new ArrayList<>();
        try {
            if (max <= 0) return out;

            if (!sealedScrollTag.contains(ATTACHMENTS_KEY, Tag.TAG_LIST)) {
                return out;
            }
            ListTag list = sealedScrollTag.getList(ATTACHMENTS_KEY, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) {
                return out;
            }

            int limit = Math.min(max, list.size());
            for (int i = 0; i < limit; i++) {
                Tag e = list.get(i);
                if (!(e instanceof CompoundTag c)) continue;

                String idStr = "";
                try {
                    idStr = c.getString("id");
                } catch (Throwable ignored) {
                    idStr = "";
                }

                int count = 0;
                try {
                    count = c.getInt("Count");
                } catch (Throwable ignored) {
                    count = 0;
                }

                if (idStr == null || idStr.isBlank()) {
                    LOG.warn("[ScrollAttachmentNbtUtil] Missing item id in attachment index {}", i);
                    continue;
                }
                if (count <= 0) {
                    LOG.warn("[ScrollAttachmentNbtUtil] Invalid Count={} for attachment id='{}' index={}", count, idStr, i);
                    continue;
                }

                ResourceLocation id;
                try {
                    id = ResourceLocation.tryParse(idStr);
                } catch (Throwable parseErr) {
                    LOG.warn("[ScrollAttachmentNbtUtil] Invalid item id '{}' in attachment index {}", idStr, i);
                    continue;
                }
                if (id == null) continue;

                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == null || item == Items.AIR) {
                    LOG.warn("[ScrollAttachmentNbtUtil] Unknown/air item id '{}' in attachment index {}", id, i);
                    continue;
                }

                ItemStack stack = new ItemStack(item, count);

                // Optional data:
                // - Prefer "CustomData" (new)
                // - Else allow legacy "tag" (old)
                CompoundTag dataTag = null;

                try {
                    if (c.contains(CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) {
                        CompoundTag cd = c.getCompound(CUSTOM_DATA_KEY);
                        if (cd != null && !cd.isEmpty()) dataTag = cd;
                    } else if (c.contains(LEGACY_TAG_KEY, Tag.TAG_COMPOUND)) {
                        CompoundTag legacy = c.getCompound(LEGACY_TAG_KEY);
                        if (legacy != null && !legacy.isEmpty()) dataTag = legacy;
                    }
                } catch (Throwable readDataErr) {
                    LOG.warn("[ScrollAttachmentNbtUtil] Failed reading optional data compound for {} at index {}",
                            id, i, readDataErr);
                    dataTag = null;
                }

                if (dataTag != null && !dataTag.isEmpty()) {
                    try {
                        // 1.20.1: store our custom payload under stack tag's "CustomData" compound
                        stack.getOrCreateTag().put(STACK_CUSTOM_DATA_KEY, dataTag.copy());
                    } catch (Throwable applyErr) {
                        LOG.warn("[ScrollAttachmentNbtUtil] Failed applying CustomData payload for {} at index {}",
                                id, i, applyErr);
                    }
                }

                out.add(stack);
            }

        } catch (Throwable t) {
            LOG.error("[ScrollAttachmentNbtUtil] readAttachments failed", t);
        }
        return out;
    }

    public static void writeAttachments(@NotNull CompoundTag sealedScrollTag, @NotNull List<ItemStack> stacks) {
        try {
            ListTag list = new ListTag();

            for (int i = 0; i < stacks.size(); i++) {
                ItemStack s = stacks.get(i);
                if (s == null || s.isEmpty()) continue;

                ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (id == null) {
                    LOG.warn("[ScrollAttachmentNbtUtil] writeAttachments: missing registry key for stack {}", s);
                    continue;
                }

                CompoundTag entry = new CompoundTag();
                entry.putString("id", id.toString());
                entry.putInt("Count", s.getCount());

                // Preserve per-item CustomData payload (1.20.1: stored under stack tag sub-compound "CustomData")
                try {
                    CompoundTag tag = s.getTag();
                    if (tag != null && tag.contains(STACK_CUSTOM_DATA_KEY, Tag.TAG_COMPOUND)) {
                        CompoundTag cd = tag.getCompound(STACK_CUSTOM_DATA_KEY);
                        if (cd != null && !cd.isEmpty()) {
                            // Write in the same shape your ScrollViewMenu expects ("CustomData")
                            entry.put(CUSTOM_DATA_KEY, cd.copy());
                        }
                    }
                } catch (Throwable tagErr) {
                    LOG.warn("[ScrollAttachmentNbtUtil] writeAttachments: failed writing CustomData for {}", id, tagErr);
                }

                list.add(entry);
            }

            sealedScrollTag.put(ATTACHMENTS_KEY, list);

        } catch (Throwable t) {
            LOG.error("[ScrollAttachmentNbtUtil] writeAttachments failed", t);
        }
    }
}
