package net.z2six.featheredfriend.registry;

import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.MailboxBlock;
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
    private static final ThreadLocal<ResourceLocation> REGISTRATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> REGISTRATION_NAME = new ThreadLocal<>();

    public static final Map<String, Supplier<Block>> BLOCK_MAP = new LinkedHashMap<>();

    public static final Supplier<Block> RAVEN_CHEST = register(
            "raven_chest",
            () -> new RavenChestBlock(
                    blockProperties()
                            .strength(2.5F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            )
    );

    public static final Supplier<Block> MAILBOX = register(
            "mailbox",
            () -> new MailboxBlock(
                    blockProperties()
                            .strength(2.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            )
    );

    public static <T> T withRegistrationId(ResourceLocation id, Supplier<T> supplier) {
        ResourceLocation previous = REGISTRATION_ID.get();
        REGISTRATION_ID.set(id);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                REGISTRATION_ID.remove();
            } else {
                REGISTRATION_ID.set(previous);
            }
        }
    }

    private static <T> T withRegistrationName(String name, Supplier<T> supplier) {
        String previous = REGISTRATION_NAME.get();
        REGISTRATION_NAME.set(name);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                REGISTRATION_NAME.remove();
            } else {
                REGISTRATION_NAME.set(previous);
            }
        }
    }

    private static BlockBehaviour.Properties blockProperties() {
        ResourceLocation id = REGISTRATION_ID.get();
        if (id == null) {
            String name = REGISTRATION_NAME.get();
            if (name != null) {
                id = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name);
            }
        }

        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of();
        if (id != null) {
            properties = properties.setId(ResourceKey.create(Registries.BLOCK, id));
        }
        return properties;
    }

    private static Supplier<Block> register(String name, Supplier<Block> factory) {
        Supplier<Block> memoized = Suppliers.memoize(() -> {
            return withRegistrationName(name, () -> {
                try {
                    return factory.get();
                } catch (Throwable t) {
                    LOG.error("[FFBlocks] Failed to create block '{}', falling back to plain Block", name, t);
                    return new Block(blockProperties());
                }
            });
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
