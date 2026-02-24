package net.z2six.featheredfriend.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.z2six.featheredfriend.registry.FFBlockEntities;
import net.z2six.featheredfriend.menu.RavenChestMenu;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class RavenChestBlockEntity extends RandomizableContainerBlockEntity implements GeoBlockEntity {

    private static final int CONTAINER_SIZE = 27;
    private static final int EVENT_SET_OPEN_COUNT = 1;
    private static final int MIN_IDLE_DELAY_TICKS = 60;  // 3s
    private static final int MAX_IDLE_DELAY_TICKS = 240; // 12s

    private static final RawAnimation ANIM_CLOSE = RawAnimation.begin().thenPlay("ChestClose");
    private static final RawAnimation ANIM_OPEN_HOLD = RawAnimation.begin().thenPlayAndHold("ChestOpen");
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenPlay("Idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
    private int openCount = 0;
    private AnimationMode animationMode = AnimationMode.CLOSED;
    private int idleDelayTicks = MIN_IDLE_DELAY_TICKS;
    private boolean idleDelayInitialized = false;
    private boolean resetIdleAnimation = false;
    private long scriptedCloseAtGameTime = -1L;

    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            playOpenCloseSound(level, pos, state, SoundEvents.CHEST_OPEN);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            playOpenCloseSound(level, pos, state, SoundEvents.CHEST_CLOSE);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int oldCount, int newCount) {
            signalOpenCount(level, pos, state, oldCount, newCount);
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            if (player.containerMenu instanceof RavenChestMenu ravenChestMenu) {
                return ravenChestMenu.getChestContainer() == RavenChestBlockEntity.this;
            }

            return false;
        }
    };

    private enum AnimationMode {
        CLOSED,
        CLOSED_IDLE,
        OPEN,
        CLOSING
    }

    public RavenChestBlockEntity(BlockPos pos, BlockState state) {
        super(FFBlockEntities.RAVEN_CHEST.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RavenChestBlockEntity blockEntity) {
        if (blockEntity.scriptedCloseAtGameTime >= 0L) {
            if (level.getGameTime() >= blockEntity.scriptedCloseAtGameTime) {
                blockEntity.finishScriptedOpen();
            }
            return;
        }

        if ((level.getGameTime() % 20L) == 0L) {
            blockEntity.recheckOpen();
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, RavenChestBlockEntity blockEntity) {
        if (!blockEntity.idleDelayInitialized) {
            blockEntity.idleDelayTicks = blockEntity.randomIdleDelayTicks();
            blockEntity.idleDelayInitialized = true;
        }

        if (blockEntity.openCount > 0) {
            return;
        }

        if (blockEntity.animationMode == AnimationMode.CLOSED) {
            if (blockEntity.idleDelayTicks > 0) {
                blockEntity.idleDelayTicks--;
            } else {
                blockEntity.animationMode = AnimationMode.CLOSED_IDLE;
                blockEntity.resetIdleAnimation = true;
            }
        }
    }

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.featheredfriend.raven_chest");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> stacks) {
        this.items = stacks;
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new RavenChestMenu(id, inventory, this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);

        if (!this.tryLoadLootTable(tag)) {
            net.minecraft.world.ContainerHelper.loadAllItems(tag, this.items, registries);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (!this.trySaveLootTable(tag)) {
            net.minecraft.world.ContainerHelper.saveAllItems(tag, this.items, registries);
        }
    }

    @Override
    public void startOpen(Player player) {
        if (this.remove || player.isSpectator() || this.level == null) {
            return;
        }

        this.openersCounter.incrementOpeners(player, this.level, this.getBlockPos(), this.getBlockState());
    }

    @Override
    public void stopOpen(Player player) {
        if (this.remove || player.isSpectator() || this.level == null) {
            return;
        }

        this.openersCounter.decrementOpeners(player, this.level, this.getBlockPos(), this.getBlockState());
    }

    @Override
    public boolean triggerEvent(int id, int type) {
        if (id == EVENT_SET_OPEN_COUNT) {
            this.openCount = type;
            handleClientOpenCountChange(type > 0);
            return true;
        }

        return super.triggerEvent(id, type);
    }

    public void recheckOpen() {
        if (this.level != null && !this.remove) {
            this.openersCounter.recheckOpeners(this.level, this.getBlockPos(), this.getBlockState());
        }
    }

    public void triggerScriptedOpenForTicks(int holdTicks) {
        if (this.level == null || this.level.isClientSide || this.remove) {
            return;
        }

        long now = this.level.getGameTime();
        this.scriptedCloseAtGameTime = now + Math.max(1, holdTicks);

        int nextCount = (this.openCount > 0) ? this.openCount : 1;
        this.level.blockEvent(this.getBlockPos(), this.getBlockState().getBlock(), EVENT_SET_OPEN_COUNT, nextCount);
        if (this.openCount <= 0) {
            playOpenCloseSound(this.level, this.getBlockPos(), this.getBlockState(), SoundEvents.CHEST_OPEN);
        }
        this.setChanged();
    }

    private void finishScriptedOpen() {
        if (this.level == null || this.level.isClientSide) {
            this.scriptedCloseAtGameTime = -1L;
            return;
        }

        this.scriptedCloseAtGameTime = -1L;
        int nextCount = Math.max(0, this.openCount - 1);
        this.level.blockEvent(this.getBlockPos(), this.getBlockState().getBlock(), EVENT_SET_OPEN_COUNT, nextCount);
        if (nextCount <= 0) {
            playOpenCloseSound(this.level, this.getBlockPos(), this.getBlockState(), SoundEvents.CHEST_CLOSE);
        }
        this.setChanged();
    }

    protected void signalOpenCount(Level level, BlockPos pos, BlockState state, int oldCount, int newCount) {
        level.blockEvent(pos, state.getBlock(), EVENT_SET_OPEN_COUNT, newCount);
    }

    private void handleClientOpenCountChange(boolean openNow) {
        if (openNow) {
            if (this.animationMode != AnimationMode.OPEN) {
                this.animationMode = AnimationMode.OPEN;
            }
            this.resetIdleAnimation = false;
            return;
        }

        if (this.animationMode == AnimationMode.OPEN) {
            this.animationMode = AnimationMode.CLOSING;
        }
    }

    private static void playOpenCloseSound(Level level, BlockPos pos, BlockState state, SoundEvent sound) {
        Direction direction = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;

        double x = pos.getX() + 0.5D + direction.getStepX() * 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D + direction.getStepZ() * 0.5D;
        float pitch = level.random.nextFloat() * 0.1F + 0.9F;

        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, 0.5F, pitch);
    }

    private int randomIdleDelayTicks() {
        int span = MAX_IDLE_DELAY_TICKS - MIN_IDLE_DELAY_TICKS + 1;
        if (span <= 1) {
            return MIN_IDLE_DELAY_TICKS;
        }
        if (this.level != null) {
            return MIN_IDLE_DELAY_TICKS + this.level.random.nextInt(span);
        }
        return MIN_IDLE_DELAY_TICKS + (int) (Math.random() * span);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, this::animationController));
    }

    private PlayState animationController(AnimationState<RavenChestBlockEntity> state) {
        return switch (this.animationMode) {
            case OPEN -> {
                if (this.openCount <= 0) {
                    this.animationMode = AnimationMode.CLOSING;
                    yield state.setAndContinue(ANIM_CLOSE);
                }

                yield state.setAndContinue(ANIM_OPEN_HOLD);
            }
            case CLOSING -> {
                PlayState result = state.setAndContinue(ANIM_CLOSE);
                if (state.getController().hasAnimationFinished()) {
                    if (this.openCount > 0) {
                        this.animationMode = AnimationMode.OPEN;
                    } else {
                        this.animationMode = AnimationMode.CLOSED;
                        this.idleDelayTicks = randomIdleDelayTicks();
                    }
                }
                yield result;
            }
            case CLOSED_IDLE -> {
                if (this.openCount > 0) {
                    this.animationMode = AnimationMode.OPEN;
                    this.resetIdleAnimation = false;
                    yield state.setAndContinue(ANIM_OPEN_HOLD);
                }

                if (this.resetIdleAnimation) {
                    state.getController().forceAnimationReset();
                    this.resetIdleAnimation = false;
                }

                PlayState result = state.setAndContinue(ANIM_IDLE);
                if (state.getController().hasAnimationFinished()) {
                    this.animationMode = AnimationMode.CLOSED;
                    this.idleDelayTicks = randomIdleDelayTicks();
                    state.getController().stop();
                    yield PlayState.STOP;
                }
                yield result;
            }
            case CLOSED -> {
                state.getController().stop();
                yield PlayState.STOP;
            }
        };
    }
}
