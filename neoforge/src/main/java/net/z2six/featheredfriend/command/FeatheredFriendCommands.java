// MainFile: neoforge/src/main/java/net/z2six/featheredfriend/command/FeatheredFriendCommands.java
package net.z2six.featheredfriend.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.debug.RavenChestPerchDebugService;
import net.z2six.featheredfriend.world.RavenCourierData;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server commands for FeatheredFriend.
 *
 * Changes in this revision:
 * - Removed redundant alias "/ff_clear_tamed_raven" (kept only "/featheredfriend clear_tamed_raven").
 * - Added:
 *     /featheredfriend tamed_raven list
 *       -> scans server playerdata (online + offline) and lists all stored tamed ravens, owner + raven name.
 *
 *     /featheredfriend tamed_raven add player name
 *       -> writes (online or offline) persistent tamed raven data for the given player with the given raven name.
 *
 * Dedicated server note:
 * - This is the *correct* environment for scanning playerdata .dat files; singleplayer vs dedicated doesn’t change
 *   the NBT read APIs, but dedicated makes it more common to have many offline playerdata files.
 */
public final class FeatheredFriendCommands {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int MAX_RAVEN_NAME_CHARS = 26;

    private static final String KEY_TAMED_RAVEN = "TamedRaven";
    private static final String KEY_HAS_TAMED_RAVEN = "HasTamedRaven";
    private static final String KEY_RAVEN_NAME = "RavenName";
    private static final String KEY_OWNER_UUID = "OwnerUUID";
    private static final String KEY_OWNER_DIMENSION = "OwnerDimension";

    // In player .dat files, persistent data is typically under one of these roots.
    private static final String ROOT_NEOFORGE_DATA = "NeoForgeData";
    private static final String ROOT_FORGE_DATA = "ForgeData";

    /**
     * NBT read budget. Player .dat files are normally small.
     * If you suspect very large persistent blobs, raise this.
     *
     * We use an explicit accounter so we don’t blow up memory on corrupted/hostile files.
     */
    private static final long PLAYERDAT_NBT_BUDGET_BYTES = 64L * 1024L * 1024L; // 64 MiB

    private FeatheredFriendCommands() {
        // no-op
    }

    /**
     * Called from FeatheredFriend main class:
     *
     *   FeatheredFriendCommands.register();
     */
    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(
                    (RegisterCommandsEvent event) -> FeatheredFriendCommands.onRegisterCommands(event)
            );
            LOG.debug("[FeatheredFriendCommands] Registered command listener on NeoForge.EVENT_BUS");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] Failed to register command listener", t);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

            // Root: /featheredfriend ...
            dispatcher.register(
                    Commands.literal("featheredfriend")
                            .requires(src -> src.hasPermission(2)) // OP-only
                            // -----------------------------------------------------------------
                            // /featheredfriend clear_tamed_raven
                            // -----------------------------------------------------------------
                            .then(Commands.literal("clear_tamed_raven")
                                    .executes(FeatheredFriendCommands::executeClearTamedRavenSelf)
                            )
                            // -----------------------------------------------------------------
                            // /featheredfriend tamed_raven ...
                            // -----------------------------------------------------------------
                            .then(Commands.literal("tamed_raven")
                                    // /featheredfriend tamed_raven list
                                    .then(Commands.literal("list")
                                            .executes(FeatheredFriendCommands::executeTamedRavenListAll)
                                    )
                                    // /featheredfriend tamed_raven add <player> <name>
                                    .then(Commands.literal("add")
                                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                    .then(Commands.argument("name", StringArgumentType.greedyString())
                                                            .executes(FeatheredFriendCommands::executeTamedRavenAddForPlayer)
                                                    )
                                            )
                                    )
                            )
                            // -----------------------------------------------------------------
                            // /featheredfriend courier ...
                            // -----------------------------------------------------------------
                            .then(Commands.literal("courier")
                                    // /featheredfriend courier list
                                    // /featheredfriend courier list <player>
                                    .then(Commands.literal("list")
                                            .executes(FeatheredFriendCommands::executeCourierListAll)
                                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                    .executes(FeatheredFriendCommands::executeCourierListPlayer)
                                            )
                                    )
                                    // /featheredfriend courier clear
                                    // /featheredfriend courier clear <player>
                                    .then(Commands.literal("clear")
                                            .executes(FeatheredFriendCommands::executeCourierClearAll)
                                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                    .executes(FeatheredFriendCommands::executeCourierClearPlayer)
                                            )
                                    )
                            )
                            // -----------------------------------------------------------------
                            // /featheredfriend raven_chest_debug ...
                            // -----------------------------------------------------------------
                            .then(Commands.literal("raven_chest_debug")
                                    .then(Commands.literal("start")
                                            .executes(FeatheredFriendCommands::executeRavenChestDebugStart)
                                    )
                                    .then(Commands.literal("stop")
                                            .executes(FeatheredFriendCommands::executeRavenChestDebugStop)
                                    )
                            )
            );

            // Removed redundant alias: /ff_clear_tamed_raven

            LOG.debug("[FeatheredFriendCommands] Commands registered: " +
                    "/featheredfriend clear_tamed_raven, " +
                    "/featheredfriend tamed_raven list, " +
                    "/featheredfriend tamed_raven add <player> <name>, " +
                    "/featheredfriend courier list [player], " +
                    "/featheredfriend courier clear [player], " +
                    "/featheredfriend raven_chest_debug start|stop");
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] onRegisterCommands failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // clear_tamed_raven
    // ---------------------------------------------------------------------

    /**
     * Command handler:
     *   - /featheredfriend clear_tamed_raven
     *
     * Clears the EXECUTING PLAYER's stored TamedRaven data from NeoForge persistent
     * player data (NeoForgeData.featheredfriend.TamedRaven.*).
     */
    private static int executeClearTamedRavenSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException ex) {
            LOG.warn("[FeatheredFriendCommands] clear_tamed_raven: source is not a player (name={})",
                    source.getTextName());
            source.sendFailure(Component.translatable("message.featheredfriend.command.player_only"));
            throw ex;
        }

        MinecraftServer server = player.server;
        if (server == null) {
            LOG.warn("[FeatheredFriendCommands] clear_tamed_raven: server is null for player={}",
                    player.getGameProfile().getName());
            source.sendFailure(Component.translatable("message.featheredfriend.command.internal_server_null"));
            return 0;
        }

        boolean cleared = clearPlayerTamedRavenData(player);
        final boolean clearedFinal = cleared;

        source.sendSuccess(() -> Component.translatable(
                clearedFinal
                        ? "message.featheredfriend.command.clear_tamed_raven.cleared"
                        : "message.featheredfriend.command.clear_tamed_raven.none",
                player.getGameProfile().getName()
        ), true);

        return cleared ? 1 : 0;
    }

    /**
     * Directly manipulates the player's persistent NeoForge data (in-memory for online players):
     *
     * NeoForgeData: {
     *   featheredfriend: {
     *     TamedRaven: {
     *       OwnerUUID: ...
     *       OwnerDimension: ...
     *       RavenName: "..."
     *       HasTamedRaven: 1b
     *       ...
     *     }
     *   }
     * }
     *
     * We set HasTamedRaven=false and aggressively strip the other fields.
     *
     * @return true if we actually found HasTamedRaven == true and cleared it.
     */
    public static boolean clearPlayerTamedRavenData(ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: no persistent data for player={}",
                        player.getGameProfile().getName());
                return false;
            }

            if (!root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: no '{}' tag for player={}",
                        Constants.MOD_ID, player.getGameProfile().getName());
                return false;
            }

            CompoundTag modTag = root.getCompound(Constants.MOD_ID);
            if (modTag == null || !modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: no {} compound for player={}",
                        KEY_TAMED_RAVEN, player.getGameProfile().getName());
                return false;
            }

            CompoundTag tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            if (tamed == null || tamed.isEmpty()) {
                LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: empty {} compound for player={}",
                        KEY_TAMED_RAVEN, player.getGameProfile().getName());
                return false;
            }

            boolean had = tamed.getBoolean(KEY_HAS_TAMED_RAVEN);

            LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: BEFORE clear player={} uuid={} tag={}",
                    player.getGameProfile().getName(), player.getUUID(), tamed);

            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, false);
            tamed.remove(KEY_RAVEN_NAME);
            tamed.remove(KEY_OWNER_UUID);
            tamed.remove(KEY_OWNER_DIMENSION);
            tamed.remove("BoundRavenId");

            if (tamed.isEmpty()) {
                modTag.remove(KEY_TAMED_RAVEN);
            } else {
                modTag.put(KEY_TAMED_RAVEN, tamed);
            }

            if (modTag.isEmpty()) {
                root.remove(Constants.MOD_ID);
            } else {
                root.put(Constants.MOD_ID, modTag);
            }

            LOG.debug("[FeatheredFriendCommands] clearPlayerTamedRavenData: AFTER clear player={} uuid={} had={} nowTag={}",
                    player.getGameProfile().getName(), player.getUUID(), had, tamed);

            return had;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] clearPlayerTamedRavenData failed for player={}",
                    (player == null ? "null" : player.getGameProfile().getName()), t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // tamed_raven list / add
    // ---------------------------------------------------------------------

    /**
     * /featheredfriend tamed_raven list
     *
     * Scans ALL server playerdata (.dat) files and lists players that have
     * persistent FeatheredFriend TamedRaven data.
     */
    private static int executeTamedRavenListAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            if (server == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.internal_server_null"));
                LOG.warn("[FeatheredFriendCommands] tamed_raven list: source.getServer() was null");
                return 0;
            }

            Path playerDataDir = getPlayerDataDir(server);
            if (playerDataDir == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.playerdata_dir_missing"));
                return 0;
            }

            if (!Files.exists(playerDataDir) || !Files.isDirectory(playerDataDir)) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.playerdata_dir_not_found", playerDataDir));
                LOG.warn("[FeatheredFriendCommands] tamed_raven list: playerdata dir missing/not directory: {}", playerDataDir);
                return 0;
            }

            Map<UUID, String> onlineNameByUuid = new HashMap<>();
            try {
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    if (sp == null) continue;
                    onlineNameByUuid.put(sp.getUUID(), sp.getGameProfile().getName());
                }
            } catch (Throwable t) {
                LOG.warn("[FeatheredFriendCommands] tamed_raven list: failed building online name map: {}", t.toString());
            }

            List<Component> lines = new ArrayList<>();
            int scanned = 0;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDataDir, "*.dat")) {
                for (Path p : stream) {
                    scanned++;
                    UUID playerUuid = parseUuidFromPlayerDatName(p.getFileName().toString());
                    if (playerUuid == null) {
                        LOG.debug("[FeatheredFriendCommands] tamed_raven list: skipping non-uuid dat name={}", p.getFileName());
                        continue;
                    }

                    CompoundTag playerRoot = readPlayerDatSafe(p);
                    if (playerRoot == null || playerRoot.isEmpty()) {
                        continue;
                    }

                    CompoundTag persistent = extractPersistentDataFromPlayerFile(playerRoot);
                    if (persistent == null || persistent.isEmpty()) {
                        continue;
                    }

                    CompoundTag modTag = persistent.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)
                            ? persistent.getCompound(Constants.MOD_ID)
                            : null;

                    if (modTag == null || modTag.isEmpty()) {
                        continue;
                    }

                    CompoundTag tamed = modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)
                            ? modTag.getCompound(KEY_TAMED_RAVEN)
                            : null;

                    if (tamed == null || tamed.isEmpty()) {
                        continue;
                    }

                    boolean has = tamed.getBoolean(KEY_HAS_TAMED_RAVEN);
                    if (!has) {
                        continue;
                    }

                    String ravenName = tamed.contains(KEY_RAVEN_NAME, Tag.TAG_STRING) ? tamed.getString(KEY_RAVEN_NAME) : "";
                    if (ravenName == null) ravenName = "";
                    if (ravenName.isBlank()) ravenName = Component.translatable("message.featheredfriend.command.tamed_raven.unnamed").getString();

                    String ownerName = onlineNameByUuid.get(playerUuid);
                    if (ownerName == null || ownerName.isBlank()) {
                        ownerName = safeGuessNameFromPlayerRoot(playerRoot);
                    }
                    if (ownerName == null || ownerName.isBlank()) {
                        ownerName = Component.translatable("message.featheredfriend.command.tamed_raven.unknown_owner").getString();
                    }

                    String ownerDim = tamed.contains(KEY_OWNER_DIMENSION, Tag.TAG_STRING) ? tamed.getString(KEY_OWNER_DIMENSION) : "";
                    if (ownerDim == null) ownerDim = "";

                    lines.add(Component.translatable(
                            "message.featheredfriend.command.tamed_raven.entry",
                            ownerName, String.valueOf(playerUuid), ravenName, ownerDim
                    ));
                }
            } catch (Throwable t) {
                LOG.error("[FeatheredFriendCommands] tamed_raven list: directory scan failed for dir={}", playerDataDir, t);
                source.sendFailure(Component.translatable("message.featheredfriend.command.tamed_raven.scan_error"));
                return 0;
            }

            if (lines.isEmpty()) {
                final int scannedFinal = scanned;
                source.sendSuccess(() -> Component.translatable(
                        "message.featheredfriend.command.tamed_raven.none",
                        scannedFinal
                ), false);
                LOG.debug("[FeatheredFriendCommands] tamed_raven list: none found (scanned={} dir={})", scannedFinal, playerDataDir);
                return 0;
            }

            final int scannedFinal = scanned;
            final int foundFinal = lines.size();

            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.tamed_raven.summary",
                    foundFinal, scannedFinal
            ), false);

            for (Component line : lines) {
                final Component out = line;
                source.sendSuccess(() -> out, false);
            }

            LOG.debug("[FeatheredFriendCommands] tamed_raven list: found={} scanned={} dir={}", foundFinal, scannedFinal, playerDataDir);
            return foundFinal;

        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeTamedRavenListAll failed", t);
            source.sendFailure(Component.translatable("message.featheredfriend.command.tamed_raven.list_error"));
            return 0;
        }
    }

    /**
     * /featheredfriend tamed_raven add <player> <name>
     *
     * Writes persistent TamedRaven info for a specific player.
     * - If player is online: edits in-memory persistent data (safe + immediate).
     * - If offline: edits their playerdata .dat file on disk.
     */
    private static int executeTamedRavenAddForPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();

        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.internal_server_null"));
            LOG.warn("[FeatheredFriendCommands] tamed_raven add: source.getServer() was null");
            return 0;
        }

        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles == null || profiles.isEmpty()) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.no_matching_player"));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        UUID targetUuid = profile.getId();
        String targetName = profile.getName();
        if (targetUuid == null) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.target_uuid_null_cannot_proceed"));
            LOG.warn("[FeatheredFriendCommands] tamed_raven add: profile had null UUID (name={})", targetName);
            return 0;
        }

        String rawName = "";
        try {
            rawName = StringArgumentType.getString(ctx, "name");
        } catch (Throwable ignored) {
        }
        String ravenName = sanitizeRavenName(rawName);

        LOG.debug("[FeatheredFriendCommands] tamed_raven add: request targetName='{}' uuid={} ravenName='{}' (raw='{}')",
                targetName, targetUuid, ravenName, rawName);

        // 1) If online, update directly.
        ServerPlayer online = server.getPlayerList().getPlayer(targetUuid);
        if (online != null) {
            boolean ok = setTamedRavenPersistentDataOnline(online, ravenName);
            if (ok) {
                source.sendSuccess(() -> Component.translatable(
                        "message.featheredfriend.command.tamed_raven.add.online_success",
                        online.getGameProfile().getName(), ravenName
                ), true);
                return 1;
            } else {
                source.sendFailure(Component.translatable("message.featheredfriend.command.tamed_raven.add.online_failure"));
                return 0;
            }
        }

        // 2) Offline: edit playerdata file on disk.
        Path playerDataDir = getPlayerDataDir(server);
        if (playerDataDir == null) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.playerdata_dir_missing"));
            return 0;
        }

        Path playerDat = playerDataDir.resolve(targetUuid.toString() + ".dat");
        if (!Files.exists(playerDat)) {
            source.sendFailure(Component.translatable(
                    "message.featheredfriend.command.tamed_raven.add.playerdata_not_found",
                    (targetName != null ? targetName : targetUuid), playerDat
            ));
            LOG.warn("[FeatheredFriendCommands] tamed_raven add: offline playerdat missing: {}", playerDat);
            return 0;
        }

        boolean wrote = setTamedRavenPersistentDataOffline(playerDat, targetUuid, ravenName);
        if (wrote) {
            String display = (targetName == null || targetName.isBlank()) ? targetUuid.toString() : targetName;
            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.tamed_raven.add.offline_success",
                    display, ravenName
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.translatable("message.featheredfriend.command.tamed_raven.add.offline_failure"));
            return 0;
        }
    }

    private static boolean setTamedRavenPersistentDataOnline(ServerPlayer player, String ravenName) {
        try {
            if (player == null) return false;

            CompoundTag root = player.getPersistentData();
            if (root == null) {
                LOG.warn("[FeatheredFriendCommands] setTamedRavenPersistentDataOnline: persistentData null for player={}", safeName(player));
                return false;
            }

            CompoundTag modTag;
            if (root.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                modTag = root.getCompound(Constants.MOD_ID);
            } else {
                modTag = new CompoundTag();
            }

            CompoundTag tamed;
            if (modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            } else {
                tamed = new CompoundTag();
            }

            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, true);
            tamed.putString(KEY_RAVEN_NAME, ravenName);

            // Store a couple of helpful fields (your clear command already removes these).
            try {
                tamed.putString(KEY_OWNER_UUID, player.getUUID().toString());
            } catch (Throwable ignored) {
            }
            try {
                String dim = player.level() != null && player.level().dimension() != null
                        ? player.level().dimension().location().toString()
                        : "minecraft:overworld";
                tamed.putString(KEY_OWNER_DIMENSION, dim);
            } catch (Throwable ignored) {
                tamed.putString(KEY_OWNER_DIMENSION, "minecraft:overworld");
            }

            modTag.put(KEY_TAMED_RAVEN, tamed);
            root.put(Constants.MOD_ID, modTag);

            LOG.debug("[FeatheredFriendCommands] setTamedRavenPersistentDataOnline: player={} uuid={} ravenName='{}' tagNow={}",
                    safeName(player), player.getUUID(), ravenName, tamed);

            return true;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] setTamedRavenPersistentDataOnline failed for player={}", safeName(player), t);
            return false;
        }
    }

    private static boolean setTamedRavenPersistentDataOffline(Path playerDat, UUID playerUuid, String ravenName) {
        try {
            if (playerDat == null || playerUuid == null) return false;

            CompoundTag playerRoot = readPlayerDatSafe(playerDat);
            if (playerRoot == null) {
                LOG.warn("[FeatheredFriendCommands] setTamedRavenPersistentDataOffline: readPlayerDatSafe returned null for {}", playerDat);
                return false;
            }

            // Ensure persistent root exists under NeoForgeData (preferred) or ForgeData.
            String chosenRootKey;
            CompoundTag persistent;

            if (playerRoot.contains(ROOT_NEOFORGE_DATA, Tag.TAG_COMPOUND)) {
                chosenRootKey = ROOT_NEOFORGE_DATA;
                persistent = playerRoot.getCompound(ROOT_NEOFORGE_DATA);
            } else if (playerRoot.contains(ROOT_FORGE_DATA, Tag.TAG_COMPOUND)) {
                chosenRootKey = ROOT_FORGE_DATA;
                persistent = playerRoot.getCompound(ROOT_FORGE_DATA);
            } else {
                // Create NeoForgeData if neither exists.
                chosenRootKey = ROOT_NEOFORGE_DATA;
                persistent = new CompoundTag();
            }

            CompoundTag modTag;
            if (persistent.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                modTag = persistent.getCompound(Constants.MOD_ID);
            } else {
                modTag = new CompoundTag();
            }

            CompoundTag tamed;
            if (modTag.contains(KEY_TAMED_RAVEN, Tag.TAG_COMPOUND)) {
                tamed = modTag.getCompound(KEY_TAMED_RAVEN);
            } else {
                tamed = new CompoundTag();
            }

            tamed.putBoolean(KEY_HAS_TAMED_RAVEN, true);
            tamed.putString(KEY_RAVEN_NAME, ravenName);
            tamed.putString(KEY_OWNER_UUID, playerUuid.toString());

            // Owner dimension is unknown offline; default to overworld.
            if (!tamed.contains(KEY_OWNER_DIMENSION, Tag.TAG_STRING) || tamed.getString(KEY_OWNER_DIMENSION).isBlank()) {
                tamed.putString(KEY_OWNER_DIMENSION, "minecraft:overworld");
            }

            modTag.put(KEY_TAMED_RAVEN, tamed);
            persistent.put(Constants.MOD_ID, modTag);
            playerRoot.put(chosenRootKey, persistent);

            boolean wrote = writePlayerDatSafe(playerDat, playerRoot);
            if (!wrote) {
                LOG.error("[FeatheredFriendCommands] setTamedRavenPersistentDataOffline: writePlayerDatSafe failed for {}", playerDat);
                return false;
            }

            LOG.debug("[FeatheredFriendCommands] setTamedRavenPersistentDataOffline: wrote {} uuid={} ravenName='{}' rootKey={} tagNow={}",
                    playerDat, playerUuid, ravenName, chosenRootKey, tamed);

            return true;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] setTamedRavenPersistentDataOffline failed for file={}", playerDat, t);
            return false;
        }
    }

    private static String sanitizeRavenName(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            if (s.isEmpty()) s = Component.translatable("entity.featheredfriend.raven").getString();
            if (s.length() > MAX_RAVEN_NAME_CHARS) {
                s = s.substring(0, MAX_RAVEN_NAME_CHARS);
            }
            return s;
        } catch (Throwable t) {
            LOG.warn("[FeatheredFriendCommands] sanitizeRavenName failed safely: {}", t.toString());
            return Component.translatable("entity.featheredfriend.raven").getString();
        }
    }

    private static Path getPlayerDataDir(MinecraftServer server) {
        try {
            if (server == null) return null;
            Path p = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (p == null) {
                LOG.warn("[FeatheredFriendCommands] getPlayerDataDir: server.getWorldPath(LevelResource.PLAYER_DATA_DIR) returned null");
                return null;
            }
            return p;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] getPlayerDataDir failed", t);
            return null;
        }
    }

    private static UUID parseUuidFromPlayerDatName(String filename) {
        try {
            if (filename == null) return null;
            String base = filename;
            if (base.endsWith(".dat")) base = base.substring(0, base.length() - 4);
            return UUID.fromString(base);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CompoundTag extractPersistentDataFromPlayerFile(CompoundTag playerRoot) {
        try {
            if (playerRoot == null) return null;

            // Prefer NeoForgeData; fallback ForgeData; fallback direct (rare).
            if (playerRoot.contains(ROOT_NEOFORGE_DATA, Tag.TAG_COMPOUND)) {
                return playerRoot.getCompound(ROOT_NEOFORGE_DATA);
            }
            if (playerRoot.contains(ROOT_FORGE_DATA, Tag.TAG_COMPOUND)) {
                return playerRoot.getCompound(ROOT_FORGE_DATA);
            }

            // Some environments/mods may store persistent data under the modid directly at player root.
            // This is not typical, but we allow it as a fallback.
            if (playerRoot.contains(Constants.MOD_ID, Tag.TAG_COMPOUND)) {
                return playerRoot;
            }

            return null;
        } catch (Throwable t) {
            LOG.warn("[FeatheredFriendCommands] extractPersistentDataFromPlayerFile failed safely: {}", t.toString());
            return null;
        }
    }

    private static String safeGuessNameFromPlayerRoot(CompoundTag playerRoot) {
        try {
            if (playerRoot == null) return "";
            // Vanilla often stores LastKnownName (not guaranteed).
            if (playerRoot.contains("LastKnownName", Tag.TAG_STRING)) {
                String s = playerRoot.getString("LastKnownName");
                return s == null ? "" : s;
            }
            if (playerRoot.contains("lastKnownName", Tag.TAG_STRING)) {
                String s = playerRoot.getString("lastKnownName");
                return s == null ? "" : s;
            }
            if (playerRoot.contains("Name", Tag.TAG_STRING)) {
                String s = playerRoot.getString("Name");
                return s == null ? "" : s;
            }
            return "";
        } catch (Throwable t) {
            LOG.debug("[FeatheredFriendCommands] safeGuessNameFromPlayerRoot failed safely: {}", t.toString());
            return "";
        }
    }

    private static String safeName(ServerPlayer p) {
        try {
            if (p == null) return "null";
            String n = p.getGameProfile() != null ? p.getGameProfile().getName() : null;
            return (n == null || n.isBlank()) ? "unknown" : n;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    // ---------------------------------------------------------------------
    // Playerdat NBT IO helpers (1.21.x requires NbtAccounter)
    // ---------------------------------------------------------------------

    private static CompoundTag readPlayerDatSafe(Path path) {
        try {
            if (path == null) return null;
            if (!Files.exists(path)) {
                LOG.debug("[FeatheredFriendCommands] readPlayerDatSafe: file does not exist: {}", path);
                return null;
            }

            // 1.21.x signature requires an accounter.
            // Use a reasonable budget; player .dat should never be huge.
            NbtAccounter accounter = NbtAccounter.create(PLAYERDAT_NBT_BUDGET_BYTES);

            try {
                // Prefer Path overload if present.
                CompoundTag tag = NbtIo.readCompressed(path, accounter);
                return tag == null ? new CompoundTag() : tag;
            } catch (Throwable pathOverloadErr) {
                // Fallback to InputStream overload if needed (futureproof / loader differences).
                LOG.debug("[FeatheredFriendCommands] readPlayerDatSafe: Path overload failed for {}: {} (trying InputStream)",
                        path, pathOverloadErr.toString());
                try (InputStream in = Files.newInputStream(path)) {
                    CompoundTag tag = NbtIo.readCompressed(in, accounter);
                    return tag == null ? new CompoundTag() : tag;
                }
            }
        } catch (Throwable t) {
            LOG.warn("[FeatheredFriendCommands] readPlayerDatSafe failed for {}: {}", path, t.toString());
            return null;
        }
    }

    private static boolean writePlayerDatSafe(Path path, CompoundTag tag) {
        try {
            if (path == null) return false;
            if (tag == null) tag = new CompoundTag();

            // Avoid partial writes: write to temp then move.
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp_ff");

            try (OutputStream out = Files.newOutputStream(tmp)) {
                NbtIo.writeCompressed(tag, out);
            } catch (Throwable writeErr) {
                LOG.error("[FeatheredFriendCommands] writePlayerDatSafe: writeCompressed failed for tmp={} target={}: {}",
                        tmp, path, writeErr.toString());
                try {
                    Files.deleteIfExists(tmp);
                } catch (Throwable ignored) {
                }
                return false;
            }

            try {
                // Replace existing.
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable moveErr) {
                // ATOMIC_MOVE may fail on some FS; retry without it.
                LOG.debug("[FeatheredFriendCommands] writePlayerDatSafe: atomic move failed for {} -> {}: {} (retrying non-atomic)",
                        tmp, path, moveErr.toString());
                try {
                    Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable moveErr2) {
                    LOG.error("[FeatheredFriendCommands] writePlayerDatSafe: move failed for {} -> {}: {}", tmp, path, moveErr2.toString());
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Throwable ignored) {
                    }
                    return false;
                }
            }

            return true;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] writePlayerDatSafe failed for {}: {}", path, t.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Raven chest perch debug commands
    // ---------------------------------------------------------------------

    private static int executeRavenChestDebugStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException ex) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.player_only"));
            throw ex;
        }

        boolean started = RavenChestPerchDebugService.startFor(player);
        if (started) {
            source.sendSuccess(() -> Component.translatable("message.featheredfriend.command.raven_chest_debug.start_success"), false);
            return 1;
        }

        source.sendFailure(Component.translatable("message.featheredfriend.command.raven_chest_debug.start_failure"));
        return 0;
    }

    private static int executeRavenChestDebugStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException ex) {
            source.sendFailure(Component.translatable("message.featheredfriend.command.player_only"));
            throw ex;
        }

        boolean stopped = RavenChestPerchDebugService.stopFor(player, true);
        if (stopped) {
            source.sendSuccess(() -> Component.translatable("message.featheredfriend.command.raven_chest_debug.stop_success"), false);
            return 1;
        }

        source.sendFailure(Component.translatable("message.featheredfriend.command.raven_chest_debug.stop_none"));
        return 0;
    }

    // ---------------------------------------------------------------------
    // Courier commands
    // ---------------------------------------------------------------------

    /**
     * /featheredfriend courier list
     *
     * Lists all pending courier jobs in the world.
     */
    private static int executeCourierListAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            List<RavenCourierData.DeliveryJob> jobs = data.getAllJobsFlat();
            if (jobs.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("message.featheredfriend.command.courier.none"), false);
                return 0;
            }

            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.courier.summary", jobs.size()
            ), false);

            for (RavenCourierData.DeliveryJob job : jobs) {
                if (job == null) continue;

                source.sendSuccess(() -> Component.translatable(
                        "message.featheredfriend.command.courier.entry",
                        job.jobId,
                        job.senderName,
                        String.valueOf(job.senderUuid),
                        job.recipientName,
                        String.valueOf(job.recipientUuid),
                        job.inFlight,
                        job.failed,
                        job.failureCount,
                        job.lastFailureGameTime,
                        (job.lastFailureReason == null ? "" : job.lastFailureReason)
                ), false);
            }

            return jobs.size();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierListAll failed", t);
            source.sendFailure(Component.translatable("message.featheredfriend.command.courier.list_error"));
            return 0;
        }
    }

    /**
     * /featheredfriend courier list <player>
     *
     * Lists all courier jobs where the given player is either sender OR recipient.
     */
    private static int executeCourierListPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        try {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.no_matching_player"));
                return 0;
            }

            GameProfile profile = profiles.iterator().next();
            UUID targetUuid = profile.getId();
            String targetName = profile.getName();

            if (targetUuid == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.target_uuid_null"));
                return 0;
            }

            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            List<RavenCourierData.DeliveryJob> jobs = data.getJobsForPlayer(targetUuid);
            if (jobs.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(
                        "message.featheredfriend.command.courier.player_none",
                        targetName
                ), false);
                return 0;
            }

            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.courier.player_summary",
                    targetName, jobs.size()
            ), false);

            for (RavenCourierData.DeliveryJob job : jobs) {
                if (job == null) continue;

                source.sendSuccess(() -> Component.translatable(
                        "message.featheredfriend.command.courier.player_entry",
                        job.jobId,
                        job.senderName,
                        String.valueOf(job.senderUuid),
                        job.recipientName,
                        String.valueOf(job.recipientUuid),
                        job.inFlight,
                        job.failed
                ), false);
            }

            return jobs.size();
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierListPlayer failed", t);
            source.sendFailure(Component.translatable("message.featheredfriend.command.courier.player_list_error"));
            return 0;
        }
    }

    /**
     * /featheredfriend courier clear
     *
     * Removes ALL courier jobs from the world.
     */
    private static int executeCourierClearAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            int removed = data.clearAllJobs();
            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.courier.clear_all_success",
                    removed
            ), true);
            return removed > 0 ? 1 : 0;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierClearAll failed", t);
            source.sendFailure(Component.translatable("message.featheredfriend.command.courier.clear_error"));
            return 0;
        }
    }

    /**
     * /featheredfriend courier clear <player>
     *
     * Removes all courier jobs where the given player is either sender OR recipient.
     */
    private static int executeCourierClearPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        try {
            Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
            if (profiles.isEmpty()) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.no_matching_player"));
                return 0;
            }

            GameProfile profile = profiles.iterator().next();
            UUID targetUuid = profile.getId();
            String targetName = profile.getName();

            if (targetUuid == null) {
                source.sendFailure(Component.translatable("message.featheredfriend.command.target_uuid_null"));
                return 0;
            }

            ServerLevel level = source.getLevel();
            RavenCourierData data = RavenCourierData.get(level);

            int removed = data.clearJobsForPlayer(targetUuid);

            source.sendSuccess(() -> Component.translatable(
                    "message.featheredfriend.command.courier.clear_player_success",
                    removed, targetName
            ), true);

            return removed > 0 ? 1 : 0;
        } catch (Throwable t) {
            LOG.error("[FeatheredFriendCommands] executeCourierClearPlayer failed", t);
            source.sendFailure(Component.translatable("message.featheredfriend.command.courier.clear_player_error"));
            return 0;
        }
    }
}
