package net.z2six.featheredfriend.block.entity;

import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Clearable;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.z2six.featheredfriend.menu.MailboxMenu;
import net.z2six.featheredfriend.registry.FFBlockEntities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class MailboxBlockEntity extends BlockEntity implements GeoBlockEntity, MenuProvider, Container, Clearable {

    public static final int SLOT_COUNT = 9;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private @Nullable UUID ownerUuid;
    private @NotNull String ownerName = "";

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);

    public MailboxBlockEntity(BlockPos pos, BlockState state) {
        super(FFBlockEntities.MAILBOX.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.featheredfriend.mailbox");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new MailboxMenu(containerId, playerInventory, this);
    }

    public @Nullable UUID getOwnerUuid() {
        return ownerUuid;
    }

    public @NotNull String getOwnerName() {
        return ownerName == null ? "" : ownerName;
    }

    public void setOwner(@NotNull UUID uuid, @NotNull String name) {
        this.ownerUuid = uuid;
        this.ownerName = (name == null) ? "" : name;
        setChanged();
        try {
            if (this.level != null && !this.level.isClientSide) {
                this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            if (tag.hasUUID("OwnerUUID")) {
                this.ownerUuid = tag.getUUID("OwnerUUID");
            } else if (tag.contains("OwnerUUIDStr", Tag.TAG_STRING)) {
                this.ownerUuid = parseUuidSafe(tag.getString("OwnerUUIDStr"));
            } else {
                this.ownerUuid = null;
            }

            this.ownerName = tag.contains("OwnerName", Tag.TAG_STRING) ? tag.getString("OwnerName") : "";
            if (this.ownerName == null) {
                this.ownerName = "";
            }

            for (int i = 0; i < SLOT_COUNT; i++) {
                this.items.set(i, ItemStack.EMPTY);
            }
            if (tag.contains("Items", Tag.TAG_LIST)) {
                ContainerHelper.loadAllItems(tag, this.items);
            }
        } catch (Throwable ignored) {
            this.ownerUuid = null;
            this.ownerName = "";
            for (int i = 0; i < SLOT_COUNT; i++) {
                this.items.set(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            if (this.ownerUuid != null) {
                tag.putUUID("OwnerUUID", this.ownerUuid);
                tag.putString("OwnerUUIDStr", this.ownerUuid.toString());
            } else {
                tag.remove("OwnerUUID");
                tag.remove("OwnerUUIDStr");
            }
            tag.putString("OwnerName", getOwnerName());

            ContainerHelper.saveAllItems(tag, this.items);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public @NotNull CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        try {
            saveAdditional(tag);
        } catch (Throwable ignored) {
        }
        return tag;
    }

    @Override
    public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket() {
        try {
            return ClientboundBlockEntityDataPacket.create(this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean tryInsertFirstEmpty(@NotNull ItemStack stack) {
        try {
            if (stack.isEmpty()) {
                return true;
            }
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack cur = items.get(i);
                if (cur.isEmpty()) {
                    items.set(i, stack.copy());
                    setChanged();
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // No animations (yet). Kept for future mailbox open/close or flag animations.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    private static @Nullable UUID parseUuidSafe(@Nullable String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return UUID.fromString(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Container
    // ---------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return SLOT_COUNT;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!items.get(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = items.get(slot);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        try {
            if (slot < 0 || slot >= SLOT_COUNT || amount <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack result = ContainerHelper.removeItem(items, slot, amount);
            if (!result.isEmpty()) {
                setChanged();
            }
            return result;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        try {
            if (slot < 0 || slot >= SLOT_COUNT) {
                return ItemStack.EMPTY;
            }
            return ContainerHelper.takeItem(items, slot);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void setItem(int slot, @NotNull ItemStack stack) {
        try {
            if (slot < 0 || slot >= SLOT_COUNT) {
                return;
            }
            items.set(slot, stack == null ? ItemStack.EMPTY : stack);
            setChanged();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        if (this.level == null || this.isRemoved()) {
            return false;
        }
        if (this.worldPosition == null) {
            return true;
        }
        return player.distanceToSqr(
                this.worldPosition.getX() + 0.5D,
                this.worldPosition.getY() + 0.5D,
                this.worldPosition.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            items.set(i, ItemStack.EMPTY);
        }
        setChanged();
    }
}
