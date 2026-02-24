package net.z2six.featheredfriend.registry;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.z2six.featheredfriend.Constants;
import org.slf4j.Logger;

public final class FFNeoForgeBlocks {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Constants.MOD_ID);

    static {
        FFBlocks.BLOCK_MAP.forEach((id, blockSupplier) -> {
            try {
                LOG.debug("Registering NeoForge block '{}' via DeferredRegister", id);
                BLOCKS.register(id, blockSupplier);
            } catch (Throwable t) {
                LOG.error("Failed to register NeoForge block '{}'", id, t);
            }
        });
    }

    private FFNeoForgeBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("Registering {} FeatheredFriend blocks with NeoForge", FFBlocks.BLOCK_MAP.size());
        BLOCKS.register(modEventBus);
    }
}
