package net.z2six.featheredfriend.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.platform.Services;
import org.jetbrains.annotations.NotNull;

/**
 * Server-authoritative storage codec for per-player mailbox contents.
 *
 * Data lives in persistent player NBT under:
 *   player.persistentData[MOD_ID].Mailbox
 */
public final class MailboxStorage {

    public static final int SLOT_COUNT = 8;

    private static final String KEY_MAILBOX = "Mailbox";

    private MailboxStorage() {
    }

    public static @NotNull NonNullList<ItemStack> load(@NotNull ServerPlayer player,
                                                       @NotNull HolderLookup.Provider registries) {
        NonNullList<ItemStack> out = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null || !root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return out;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_MAILBOX, Tag.TAG_COMPOUND)) {
                return out;
            }

            CompoundTag mailboxTag = modTag.getCompound(KEY_MAILBOX);
            if (mailboxTag == null || mailboxTag.isEmpty()) {
                return out;
            }

            ContainerHelper.loadAllItems(mailboxTag, out, registries);
        } catch (Throwable ignored) {
        }

        return out;
    }

    public static void save(@NotNull ServerPlayer player,
                            @NotNull NonNullList<ItemStack> contents,
                            @NotNull HolderLookup.Provider registries) {
        try {
            CompoundTag root = Services.PLATFORM.getPlayerPersistentData(player);
            if (root == null) {
                return;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);

            NonNullList<ItemStack> toSave = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
            for (int i = 0; i < SLOT_COUNT && i < contents.size(); i++) {
                ItemStack s = contents.get(i);
                toSave.set(i, (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy());
            }

            boolean hasAny = false;
            for (ItemStack s : toSave) {
                if (s != null && !s.isEmpty()) {
                    hasAny = true;
                    break;
                }
            }

            if (!hasAny) {
                modTag.remove(KEY_MAILBOX);
            } else {
                CompoundTag mailboxTag = new CompoundTag();
                ContainerHelper.saveAllItems(mailboxTag, toSave, registries);
                mailboxTag.putInt("Size", SLOT_COUNT);
                modTag.put(KEY_MAILBOX, mailboxTag);
            }

            root.put(Constants.MOD_ID, modTag);
        } catch (Throwable ignored) {
        }
    }
}

