// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/network/payload/SigilPreviewPayload.java
package net.z2six.featheredfriend.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.z2six.featheredfriend.Constants;
import org.jetbrains.annotations.NotNull;

/**

 neoforge/src/main/java/net/z2six/featheredfriend/network/payload/SigilPreviewPayload.java

 SigilPreviewPayload

 S2C payload that carries all parameters needed for the sigil preview:

 slices : number of symmetry slices

 shapeSetIndex : index of shape set

 seed : string seed (hashed on client)
 */
public record SigilPreviewPayload(int slices, int shapeSetIndex, String seed) implements CustomPacketPayload {

    public static final Type<SigilPreviewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sigil_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SigilPreviewPayload> STREAM_CODEC =
            StreamCodec.of(SigilPreviewPayload::encode, SigilPreviewPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SigilPreviewPayload p) {
        buf.writeVarInt(p.slices());
        buf.writeVarInt(p.shapeSetIndex());
        buf.writeUtf(p.seed(), 128);
    }

    private static SigilPreviewPayload decode(RegistryFriendlyByteBuf buf) {
        int slices = buf.readVarInt();
        int shapeSetIndex = buf.readVarInt();
        String seed = buf.readUtf(128);
        return new SigilPreviewPayload(slices, shapeSetIndex, seed);
    }

    @Override
    public @NotNull Type<SigilPreviewPayload> type() {
        return TYPE;
    }
}