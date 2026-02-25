package net.z2six.featheredfriend.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.z2six.featheredfriend.platform.Services;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import net.z2six.featheredfriend.registry.FFBlockEntities;
import net.z2six.featheredfriend.registry.FFItems;
import net.z2six.featheredfriend.world.RavenChestRegistryData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class RavenChestBlock extends BaseEntityBlock {

    public static final MapCodec<RavenChestBlock> CODEC = simpleCodec(RavenChestBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public RavenChestBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RavenChestBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state,
                                               Level level,
                                               BlockPos pos,
                                               Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RavenChestBlockEntity ravenChest) {
            player.openMenu(ravenChest);
            player.awardStat(Stats.OPEN_CHEST);
        }

        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            try {
                RavenChestRegistryData.get(serverLevel).unregisterChest(
                        serverLevel.dimension().location().toString(),
                        pos.asLong()
                );
            } catch (Throwable ignored) {
            }
        }
        Containers.dropContentsOnDestroy(state, newState, level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void setPlacedBy(Level level,
                            BlockPos pos,
                            BlockState state,
                            @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level.isClientSide) {
            return;
        }
        if (!Services.PLATFORM.isSuspiciousChestEnabled()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(placer instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return;
        }

        try {
            RavenChestRegistryData data = RavenChestRegistryData.get(serverLevel);
            int max = Services.PLATFORM.getMaxRavenChestsPerPlayer();
            int count = data.getCountForOwner(serverPlayer.getUUID());
            if (max >= 0 && count >= max) {
                level.removeBlock(pos, false);
                if (!serverPlayer.getAbilities().instabuild) {
                    ItemStack refund = new ItemStack(FFItems.RAVEN_CHEST.get());
                    boolean inserted = serverPlayer.getInventory().add(refund);
                    if (!inserted) {
                        serverPlayer.drop(refund, false);
                    }
                }
                serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.featheredfriend.raven_chest.limit_reached",
                        Integer.valueOf(max)
                ));
                return;
            }

            String dim = serverLevel.dimension().location().toString();
            String defaultLabel = net.minecraft.network.chat.Component.translatable("container.featheredfriend.raven_chest").getString();
            data.registerChest(serverPlayer.getUUID(), dim, pos, defaultLabel);
            String label = data.getLabel(dim, pos.asLong());
            Services.PLATFORM.sendOpenRavenChestLabelScreen(
                    serverPlayer,
                    dim,
                    pos.asLong(),
                    label == null ? defaultLabel : label
            );
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack(FFItems.RAVEN_CHEST.get()));
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level,
                                                                             BlockState state,
                                                                             BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, FFBlockEntities.RAVEN_CHEST.get(), RavenChestBlockEntity::clientTick);
        }

        return createTickerHelper(type, FFBlockEntities.RAVEN_CHEST.get(), RavenChestBlockEntity::serverTick);
    }
}
