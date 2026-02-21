package net.z2six.featheredfriend.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class FFNeoForgeBlockEntities {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Constants.MOD_ID);

    @SuppressWarnings("unchecked")
    public static final Supplier<BlockEntityType<RavenChestBlockEntity>> RAVEN_CHEST =
            (Supplier<BlockEntityType<RavenChestBlockEntity>>) (Supplier<?>)
                    BLOCK_ENTITY_TYPES.register(
                            "raven_chest",
                            () -> BlockEntityType.Builder
                                    .of(RavenChestBlockEntity::new, FFBlocks.RAVEN_CHEST.get())
                                    .build(null)
                    );

    @SuppressWarnings("unchecked")
    public static final Supplier<BlockEntityType<MailboxBlockEntity>> MAILBOX =
            (Supplier<BlockEntityType<MailboxBlockEntity>>) (Supplier<?>)
                    BLOCK_ENTITY_TYPES.register(
                            "mailbox",
                            () -> BlockEntityType.Builder
                                    .of(MailboxBlockEntity::new, FFBlocks.MAILBOX.get())
                                    .build(null)
                    );

    static {
        FFBlockEntities.bindRavenChestType(RAVEN_CHEST);
        FFBlockEntities.bindMailboxType(MAILBOX);
    }

    private FFNeoForgeBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        LOG.debug("Registering {} FeatheredFriend block entities with NeoForge", FFBlockEntities.BLOCK_ENTITY_MAP.size());
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
