package net.z2six.featheredfriend.registry;

import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.z2six.featheredfriend.block.RavenChestBlock;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Common block registry map (loader agnostic).
 */
public final class FFBlocks {

    private static final Logger LOG = LogUtils.getLogger();

    public static final Map<String, Supplier<Block>> BLOCK_MAP = new LinkedHashMap<>();

    public static final Supplier<Block> RAVEN_CHEST = register(
            "raven_chest",
            () -> new RavenChestBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            )
    );

    private static Supplier<Block> register(String name, Supplier<Block> factory) {
        Supplier<Block> memoized = Suppliers.memoize(() -> {
            try {
                return factory.get();
            } catch (Throwable t) {
                LOG.error("[FFBlocks] Failed to create block '{}', falling back to plain Block", name, t);
                return new Block(BlockBehaviour.Properties.of());
            }
        });

        Supplier<Block> previous = BLOCK_MAP.put(name, memoized);
        if (previous != null) {
            LOG.warn("[FFBlocks] Duplicate common block key '{}' detected; overwriting previous supplier", name);
        }

        return memoized;
    }

    private FFBlocks() {
    }
}

