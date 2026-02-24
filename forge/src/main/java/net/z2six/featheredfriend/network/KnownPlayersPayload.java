package net.z2six.featheredfriend.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record KnownPlayersPayload(@NotNull List<KnownPlayerInfo> players) {

    private static final Logger LOG = LogUtils.getLogger();

    public static void encode(@NotNull KnownPlayersPayload msg, @NotNull FriendlyByteBuf buf) {
        try {
            List<KnownPlayerInfo> list = msg.players() == null ? List.of() : msg.players();
            buf.writeVarInt(list.size());
            for (KnownPlayerInfo info : list) {
                if (info == null || info.uuid() == null || info.name() == null) {
                    buf.writeUUID(new UUID(0L, 0L));
                    buf.writeUtf("", 1024);
                    buf.writeVarInt(0);
                    continue;
                }
                buf.writeUUID(info.uuid());
                buf.writeUtf(info.name(), 1024);
                buf.writeVarInt(Math.max(0, info.mailboxCount()));
            }
        } catch (Throwable t) {
            LOG.error("[KnownPlayersPayload] encode failed", t);
        }
    }

    public static @NotNull KnownPlayersPayload decode(@NotNull FriendlyByteBuf buf) {
        try {
            int size = buf.readVarInt();
            if (size < 0) size = 0;
            if (size > 10000) {
                LOG.warn("[KnownPlayersPayload] decode: suspicious size={} (clamping to 10000)", size);
                size = 10000;
            }

            List<KnownPlayerInfo> list = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                UUID uuid = buf.readUUID();
                String name = buf.readUtf(1024);
                int mailboxCount = buf.readVarInt();

                if (uuid == null) continue;
                if (name == null || name.isBlank()) continue;
                if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) continue;

                list.add(new KnownPlayerInfo(uuid, name, Math.max(0, mailboxCount)));
            }

            return new KnownPlayersPayload(list);
        } catch (Throwable t) {
            LOG.error("[KnownPlayersPayload] decode failed, returning safe default", t);
            return new KnownPlayersPayload(List.of());
        }
    }
}

