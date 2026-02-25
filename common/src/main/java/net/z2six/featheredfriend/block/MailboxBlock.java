package net.z2six.featheredfriend.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.registry.FFItems;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Wall-mounted mailbox block (sign-like placement). Storage is per-mailbox and shared for all viewers..
 */
public class MailboxBlock extends BaseEntityBlock {

    public static final MapCodec<MailboxBlock> CODEC = simpleCodec(MailboxBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Derived from mailbox.geo.json (including the 0.25 inflate) so the hitbox matches the GeckoLib model.
    private static final double MIN = 0.25D;
    private static final double MAX = 15.75D;
    private static final double MIN_Y = 0.75D;
    private static final double MAX_Y = 9.25D;
    private static final double THICKNESS = 3.5D;

    private static final VoxelShape SHAPE_NORTH = Block.box(MIN, MIN_Y, 0.0D, MAX, MAX_Y, THICKNESS);
    private static final VoxelShape SHAPE_SOUTH = Block.box(MIN, MIN_Y, 16.0D - THICKNESS, MAX, MAX_Y, 16.0D);
    private static final VoxelShape SHAPE_WEST = Block.box(0.0D, MIN_Y, MIN, THICKNESS, MAX_Y, MAX);
    private static final VoxelShape SHAPE_EAST = Block.box(16.0D - THICKNESS, MIN_Y, MIN, 16.0D, MAX_Y, MAX);

    // Full-face support planes so fences can visually connect when a mailbox is mounted to them.
    private static final VoxelShape SUPPORT_FACE_NORTH = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
    private static final VoxelShape SUPPORT_FACE_SOUTH = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SUPPORT_FACE_WEST = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);
    private static final VoxelShape SUPPORT_FACE_EAST = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    public MailboxBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MailboxBlockEntity(pos, state);
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
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) {
            return null;
        }

        BlockState state = this.defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos supportPos = pos.relative(facing.getOpposite());
        BlockState supportState = level.getBlockState(supportPos);
        return supportState.isFaceSturdy(level, supportPos, facing)
                || supportState.is(BlockTags.FENCES)
                || supportState.is(BlockTags.FENCE_GATES);
    }

    @Override
    public BlockState updateShape(BlockState state,
                                  Direction direction,
                                  BlockState neighborState,
                                  LevelAccessor level,
                                  BlockPos pos,
                                  BlockPos neighborPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }

        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public VoxelShape getShape(BlockState state,
                               BlockGetter level,
                               BlockPos pos,
                               CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            // For wall-mounted blocks, the model sits on the opposite face (the supporting block side).
            // Example: FACING=NORTH means the mailbox is attached to the SOUTH wall of its block space.
            case SOUTH -> SHAPE_NORTH;
            case WEST -> SHAPE_EAST;
            case EAST -> SHAPE_WEST;
            default -> SHAPE_SOUTH;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state,
                                        BlockGetter level,
                                        BlockPos pos,
                                        CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        // Only the "back" face (against the supporting block) should be considered sturdy.
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            case SOUTH -> SUPPORT_FACE_NORTH;
            case WEST -> SUPPORT_FACE_EAST;
            case EAST -> SUPPORT_FACE_WEST;
            default -> SUPPORT_FACE_SOUTH;
        };
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
        if (blockEntity instanceof MailboxBlockEntity mailbox) {
            player.openMenu(mailbox);
            player.awardStat(Stats.OPEN_CHEST);
        }

        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(@NotNull Level level,
                            @NotNull BlockPos pos,
                            @NotNull BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level.isClientSide) {
            return;
        }
        if (!(placer instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return;
        }

        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MailboxBlockEntity mailbox) {
                mailbox.setOwner(serverPlayer.getUUID(), serverPlayer.getGameProfile().getName());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of(new ItemStack(FFItems.MAILBOX.get()));
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        try {
            if (!state.is(newState.getBlock())) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof MailboxBlockEntity mailbox) {
                    if (!level.isClientSide) {
                        Containers.dropContents(level, pos, mailbox);
                        level.updateNeighbourForOutputSignal(pos, this);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }
}
