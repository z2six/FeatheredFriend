// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/SigilPreviewPayload.java
package net.z2six.featheredfriend.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;

public record SigilPreviewPayload(String seed) implements CustomPacketPayload {

    public static final Type<SigilPreviewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sigil_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SigilPreviewPayload> STREAM_CODEC =
            StreamCodec.of(SigilPreviewPayload::encode, SigilPreviewPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SigilPreviewPayload payload) {
        buf.writeUtf(payload.seed(), 2048);
    }

    private static SigilPreviewPayload decode(RegistryFriendlyByteBuf buf) {
        return new SigilPreviewPayload(buf.readUtf(2048));
    }

    @Override
    public @NotNull Type<SigilPreviewPayload> type() {
        return TYPE;
    }
}
