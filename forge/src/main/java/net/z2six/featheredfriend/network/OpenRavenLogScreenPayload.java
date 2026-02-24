package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public record OpenRavenLogScreenPayload(@NotNull List<RavenLogEntryInfo> entries) {
    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull OpenRavenLogScreenPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            List<RavenLogEntryInfo> list = msg.entries() == null ? List.of() : msg.entries();
            buf.writeVarInt(list.size());
            for (RavenLogEntryInfo entry : list) {
                if (entry == null) {
                    buf.writeLong(0L);
                    buf.writeLong(0L);
                    buf.writeLong(0L);
                    buf.writeUtf("", 64);
                    buf.writeUtf("", 2048);
                    continue;
                }
                buf.writeLong(entry.entryId());
                buf.writeLong(entry.gameTime());
                buf.writeLong(entry.createdAtMillis());
                buf.writeUtf(entry.categoryId(), 64);
                buf.writeUtf(entry.message(), 2048);
            }
        } catch (Throwable t) {
            LOG.error("[OpenRavenLogScreenPayload] encode failed", t);
        }
    }

    public static @NotNull OpenRavenLogScreenPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            int size = Math.max(0, Math.min(4096, buf.readVarInt()));
            List<RavenLogEntryInfo> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                long entryId = buf.readLong();
                long gameTime = buf.readLong();
                long createdAtMillis = buf.readLong();
                String categoryId = buf.readUtf(64);
                String message = buf.readUtf(2048);
                if (categoryId == null) categoryId = "";
                if (message == null) message = "";
                list.add(new RavenLogEntryInfo(entryId, gameTime, createdAtMillis, categoryId, message));
            }
            return new OpenRavenLogScreenPayload(list);
        } catch (Throwable t) {
            LOG.error("[OpenRavenLogScreenPayload] decode failed, returning safe default", t);
            return new OpenRavenLogScreenPayload(List.of());
        }
    }
}
