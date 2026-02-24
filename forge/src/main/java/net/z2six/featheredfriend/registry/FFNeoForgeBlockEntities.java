package net.z2six.featheredfriend.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.block.entity.MailboxBlockEntity;
import net.z2six.featheredfriend.block.entity.RavenChestBlockEntity;
import org.slf4j.Logger;

public final class FFNeoForgeBlockEntities {

    private static final Logger LOG = Constants.LOG;

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Constants.MOD_ID);

    public static final RegistryObject<BlockEntityType<RavenChestBlockEntity>> RAVEN_CHEST =
            BLOCK_ENTITY_TYPES.register(
                    "raven_chest",
                    () -> BlockEntityType.Builder
                            .of(RavenChestBlockEntity::new, FFBlocks.RAVEN_CHEST.get())
                            .build(null)
            );

    public static final RegistryObject<BlockEntityType<MailboxBlockEntity>> MAILBOX =
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
