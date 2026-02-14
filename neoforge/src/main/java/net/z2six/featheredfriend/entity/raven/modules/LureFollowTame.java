// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/LureFollowTame.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.z2six.featheredfriend.entity.raven.RavenAIState;
import net.z2six.featheredfriend.entity.raven.RavenAnimMode;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.z2six.featheredfriend.Constants;
import net.z2six.featheredfriend.world.TamedRavenPlayerData;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LureFollowTame {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int FOLLOW_DIRECT_REARM_TICKS = 10;
    private static final int FOLLOW_DIRECT_TTL_TICKS = 20;

    /**
     * Owning raven instance. All Minecraft-facing state & methods are delegated to this.
     */
    private final RavenEntity raven;

    // ---------------------------------------------------------------------------------------------
    // VARIABLES -----------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Taming "agree caw" sequence (plays raven.caw_agree N times, where N = tame cost)
    // ---------------------------------------------------------------------------------------------

    // Full sounds.json ID for the "agree caw" sound.
    private static final String TAMING_AGREE_SOUND_ID = Constants.MOD_ID + ":raven.caw_agree";

    // Arrival air-woosh sound (owner follow arrival).
    private static final String ARRIVAL_SOUND_ID = Constants.MOD_ID + ":raven.caw_whistle";

    // Config: global cooldown between *whole* agree-caw sequences,
    // and per-caw interval bounds (speed control inside the sequence).
    // 30 * 20 = 600 ticks = 30 seconds.
    private static final int LURE_AGREE_SEQUENCE_COOLDOWN_TICKS = 30 * 20;

    // How fast we play each individual caw inside the sequence.
    // You can tweak these to change the "rattle speed" of the agree sound.
    private static final int LURE_AGREE_CAW_INTERVAL_MIN_TICKS = 10; // 0.5s
    private static final int LURE_AGREE_CAW_INTERVAL_MAX_TICKS = 16; // 0.8s

    // Sequence state; once armed, it will keep ticking until it finishes or is explicitly cancelled.
    private boolean lureAgreeSequenceActive = false;
    private int lureAgreeCawsRemaining = 0;
    private int lureAgreeCawCooldownTicks = 0;
    private int lureAgreeSequenceCursor = 0;

    // Global cooldown after a full agree-caw sequence finishes.
    // While > 0, we will NOT start a new sequence even if we arrive again.
    private int lureAgreeSequenceGlobalCooldownTicks = 0;

    // Tracks whether we were "at the lure goal" last tick so we only start
    // the agree sequence once per arrival, not every tick while hovering there.
    private boolean wasAtLureGoalLastTick = false;

    // Tracks whether we were "at the follow goal" (owner or lure) last tick,
    // so we can fire arrival events and sounds only once per arrival.
    private boolean wasAtFollowGoalLastTick = false;

    // Random per-spawn "tame cost" (3..6 golden nuggets). Persisted via NBT.
    // NOTE: NBT read/write for this field still lives in RavenEntity; this is just the backing store.
    public static final String NBT_TAME_NUGGETS_REQUIRED = "GoldenNuggetsRequiredToTame";
    private int goldenNuggetsRequiredToTame = 0;

    /**
     * New taming requirement: a per-raven ordered sequence of nugget types.
     *
     * Stored as a short string of 'I' (iron) and 'G' (gold), e.g. "IGGIG".
     * Index tracks how many steps have been paid (0..len).
     */
    public static final String NBT_TAME_NUGGET_SEQUENCE = "TameNuggetSequence";
    public static final String NBT_TAME_NUGGET_SEQUENCE_INDEX = "TameNuggetSequenceIndex";

    private String tameNuggetSequence = "";
    private int tameNuggetSequenceIndex = 0;

    // Total nuggets actually paid toward taming (by the lure player only).
    // This is *not* persisted yet (you can wire NBT later in RavenEntity).
    private int tamingNuggetsPaidTotal = 0;

    // Hand-feed "countdown" caw sequence (per RMB nugget).
    // This is separate from the arrival-based lureAgreeSequence logic.
    private boolean handFeedSequenceActive = false;
    private int handFeedCawsRemaining = 0;
    private int handFeedCawCooldownTicks = 0;
    private int handFeedSequenceCursor = 0;

    // Prevent multiple agree-caws in the same tick (interaction + follow tick can both fire).
    private int lastAgreeCawTick = -10_000_000;

    // Tunables for how fast the hand-feed caws play.
    private static final int HAND_FEED_CAW_INTERVAL_MIN_TICKS = 6;  // ~0.3s
    private static final int HAND_FEED_CAW_INTERVAL_MAX_TICKS = 10; // ~0.5s

    // Follow owner: 3x3x3 pocket targeting + override gating
    private boolean followOverrideActive = false;
    private @Nullable BlockPos followPocketAnchor = null; // anchor block for 3x3x3 pocket (center block at y)
    private int followPocketRecalcCooldownTicks = 0;

    // Lure-follow (pre-taming) state
    private @Nullable java.util.UUID lureFollowPlayerUuid = null;
    private int lureFollowTicks = 0;

    // Small grace so follow doesn't flap off instantly if player briefly swaps items
    private static final int LURE_FOLLOW_GRACE_TICKS = 10; // 0.5s
    private static final int LURE_FOLLOW_REFRESH_TICKS = 3 * 20; // keep active for 3s per refresh

    // Follow goal stability (prevents constant re-target + vertical "hops")
    private int followDirectRearmTicks = 0;

    // Follow: desired distance band
    private static final double FOLLOW_MIN_DIST = 3.0D;
    private static final double FOLLOW_MAX_DIST = 8.0D;

    // Follow cooldown after bounds violation
    private static final int FOLLOW_COOLDOWN_MIN_TICKS = 5 * 20;
    private static final int FOLLOW_COOLDOWN_MAX_TICKS = 10 * 20;

    // Synced follow cooldown
    public static final EntityDataAccessor<Integer> DATA_FOLLOW_COOLDOWN_TICKS =
            RavenEntity.DATA_FOLLOW_COOLDOWN_TICKS;

    // NBT Key
    public static final String NBT_FOLLOW_CD = "RavenFollowCooldown";

    public LureFollowTame(RavenEntity raven) {
        this.raven = raven;
    }

    // ---------------------------------------------------------------------------------------------
    // ACCESSORS / BASIC HELPERS -------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns true if this player already has a stored/tamed raven
     * according to the TamedRaven player-persistent NBT.
     *
     * Structure (from TamedRaven.storeTamedRavenForPlayer):
     *
     *   player.getPersistentData() -> <root> {
     *       <Constants.MOD_ID>: {
     *           TamedRaven: {
     *               HasTamedRaven: 1b
     *               ...
     *           }
     *       }
     *   }
     */
    private boolean playerHasTamedRaven(Player player) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return false;
            }
            TamedRavenPlayerData.TamedRavenInfo info = TamedRavenPlayerData.getTamedRavenInfo(serverPlayer);
            boolean has = info != null && info.hasTamedRaven();

            if (has && raven.tickCount % 200 == 0) {
                LOG.debug("[RavenEntity] playerHasTamedRaven: player={} hasBoundRaven=true",
                        serverPlayer.getName().getString());
            }

            return has;

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] playerHasTamedRaven failed safely: {}", t.toString());
            }
            return false;
        }
    }

    /**
     * Handle RMB interaction for taming-related actions (feeding golden nuggets).
     *
     * This is called from RavenEntity.mobInteract(...) on both client and server.
     * - CLIENT: only decides whether the interaction should visually succeed
     *           (hand swing, sound sync handled by server) – NO game logic here.
     * - SERVER: performs the actual nugget feeding via tryFeedLureTamingNugget(...).
     *
     * Returns:
     *   - InteractionResult.PASS   -> RavenEntity falls back to default behavior.
     *   - InteractionResult.SUCCESS / sidedSuccess(...) -> interaction handled by taming.
     */
    public InteractionResult handleTamingInteract(Player player, InteractionHand hand) {
        try {
            if (raven == null || player == null) {
                return InteractionResult.PASS;
            }

            boolean clientSide = raven.level() != null && raven.level().isClientSide;
            ItemStack stack = player.getItemInHand(hand);
            String itemKey = (stack == null || stack.isEmpty())
                    ? "EMPTY"
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int itemCount = (stack == null) ? -1 : stack.getCount();

            LOG.debug("[RavenEntity] handleTamingInteract ENTER: side={} player={} hand={} item={} {}",
                    clientSide ? "CLIENT" : "SERVER",
                    player.getName().getString(),
                    hand,
                    itemCount,
                    itemKey);

            // If the player already has a stored/tamed raven, completely disable
            // the taming interaction (no lure-based taming for multiple ravens).
            if (!clientSide && playerHasTamedRaven(player)) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] handleTamingInteract: player={} already has a tamed raven; taming disabled.",
                            player.getName().getString());
                }
                return InteractionResult.PASS;
            }

            // CLIENT: just play the hand animation when the server accepts it.
            if (clientSide) {
                LOG.debug("[RavenEntity] handleTamingInteract: CLIENT side, returning sidedSuccess(true)");
                return InteractionResult.sidedSuccess(true);
            }

            // SERVER: actually try to feed / consume.
            boolean handled = tryFeedLureTamingNugget(player, hand);

            if (handled) {
                ItemStack after = player.getItemInHand(hand);
                int afterCount = (after == null) ? -1 : after.getCount();
                String afterKey = (after == null || after.isEmpty())
                        ? "EMPTY"
                        : BuiltInRegistries.ITEM.getKey(after.getItem()).toString();

                LOG.debug("[RavenEntity] handleTamingInteract: SERVER tryFeedLureTamingNugget handled=true " +
                                "(player={} before={} {} after={} {})",
                        player.getName().getString(),
                        itemCount, itemKey,
                        afterCount, afterKey);

                return InteractionResult.CONSUME;
            }

            LOG.debug("[RavenEntity] handleTamingInteract: SERVER not handled (PASS).");
            return InteractionResult.PASS;

        } catch (Throwable t) {
            LOG.warn("[RavenEntity] handleTamingInteract failed safely: {}", t.toString());
            return InteractionResult.PASS;
        }
    }

    /**
     * Try to feed ONE golden nugget for taming via RMB on the raven.
     *
     * Rules:
     *  - Only the *current lure player* may feed (their UUID must match lureFollowPlayerUuid).
     *  - If conditions are met, exactly ONE nugget is consumed (unless in creative),
     *    taming progress is advanced, and a "countdown" agree-caw sequence is started
     *    with length = remaining nuggets needed.
     *  - If the raven is already fully paid, we consume nothing and only return true
     *    if you want to treat the interaction as "handled".
     *
     * @return true if this method handled the interaction (even if no nugget was consumed),
     *         false if the caller should treat it as "not a taming feed".
     */
    public boolean tryFeedLureTamingNugget(Player player, InteractionHand hand) {
        try {
            if (player == null) return false;
            if (raven == null) return false;
            if (raven.level() == null) return false;
            if (raven.level().isClientSide) return false; // server-only
            if (!raven.isAlive()) return false;

            // If the player already has a stored/tamed raven, do NOT allow feeding
            // nuggets for a new tame.
            if (playerHasTamedRaven(player)) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] tryFeedLureTamingNugget: player={} already has a tamed raven; rejecting feed.",
                            player.getName().getString());
                }
                return false;
            }

            // Grab the *actual* held stack for this hand.
            ItemStack stack = player.getItemInHand(hand);
            if (stack == null || stack.isEmpty()) {
                return false;
            }

            // Must be a nugget we can use for this taming sequence.
            if (!stack.is(Items.GOLD_NUGGET) && !stack.is(Items.IRON_NUGGET)) {
                return false;
            }

            boolean creative = false;
            try {
                creative = player.getAbilities().instabuild;
            } catch (Throwable ignored) {
            }

            int countBefore = stack.getCount();

            // Lure-follow must be active, and player must be the current lure source.
            if (!isLureFollowActive()) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] tryFeedLureTamingNugget: lure not active, reject feed. pos={}", raven.position());
                }
                return false;
            }

            if (lureFollowPlayerUuid == null || !lureFollowPlayerUuid.equals(player.getUUID())) {
                // Someone other than the lure player is trying to feed:
                // DO NOT consume, DO NOT advance taming.
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] tryFeedLureTamingNugget: player {} is not current lure player, rejecting feed.",
                            player.getName().getString());
                }
                return false;
            }

            // Make sure the per-raven taming sequence is initialized.
            initGoldenNuggetsRequiredToTameIfNeeded("tryFeedLureTamingNugget");

            final String seq = getTameNuggetSequence();
            final int totalRequired = seq.length();

            if (totalRequired <= 0) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn("[RavenEntity] tryFeedLureTamingNugget: tame sequence missing/empty, nothing to pay. pos={}",
                            raven.position());
                }
                return true; // treat as handled, but nothing to do
            }

            // If we've already fully paid, don't consume more; just treat as handled.
            if (tameNuggetSequenceIndex >= totalRequired) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] tryFeedLureTamingNugget: already fully paid (paid={} / required={})",
                            tameNuggetSequenceIndex, totalRequired);
                }
                return true;
            }

            // Check the required nugget type for this step (I=iron, G=gold).
            char required = seq.charAt(Math.max(0, tameNuggetSequenceIndex));
            boolean isGoldStep = required == 'G';
            boolean wrongNugget =
                    (isGoldStep && !stack.is(Items.GOLD_NUGGET))
                            || (!isGoldStep && !stack.is(Items.IRON_NUGGET));
            if (wrongNugget) {
                if (!creative) {
                    try {
                        stack.shrink(1);
                    } catch (Throwable t) {
                        LOG.warn("[RavenEntity] tryFeedLureTamingNugget: failed to shrink wrong-nugget stack: {}", t.toString());
                    }
                    try {
                        player.setItemInHand(hand, stack);
                    } catch (Throwable t) {
                        LOG.warn("[RavenEntity] tryFeedLureTamingNugget: setItemInHand failed after wrong nugget: {}", t.toString());
                    }
                }

                try {
                    if (raven.level() instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                        net.z2six.featheredfriend.entity.raven.modules.TamedRaven tamed = raven.getTamedRavenModule();
                        if (tamed != null) {
                            tamed.beginDespawnWithFx(serverLevel, serverPlayer, "Raven");
                        } else {
                            raven.discard();
                        }
                    } else {
                        raven.discard();
                    }
                } catch (Throwable t) {
                    LOG.warn("[RavenEntity] tryFeedLureTamingNugget: wrong-nugget despawn failed safely: {}", t.toString());
                    raven.discard();
                }

                clearLureFollowState("wrong nugget despawn");
                return true;
            }

            // Consume ONE nugget (unless creative / insta-build).
            if (!creative) {
                try {
                    stack.shrink(1);
                } catch (Throwable t) {
                    LOG.warn("[RavenEntity] tryFeedLureTamingNugget: failed to shrink stack: {}", t.toString());
                }

                // Force the mutated stack back into the hand to be absolutely sure it syncs.
                try {
                    player.setItemInHand(hand, stack);
                } catch (Throwable t) {
                    LOG.warn("[RavenEntity] tryFeedLureTamingNugget: setItemInHand failed: {}", t.toString());
                }
            }

            int countAfter = stack.getCount();
            LOG.debug("[RavenEntity] tryFeedLureTamingNugget: stack count {} -> {} (creative={}, player={})",
                    countBefore, countAfter, creative, player.getName().getString());

            // Advance taming progress.
            tameNuggetSequenceIndex = Math.min(totalRequired, Math.max(0, tameNuggetSequenceIndex + 1));
            tamingNuggetsPaidTotal = tameNuggetSequenceIndex; // keep legacy counter in sync

            int remaining = Math.max(0, totalRequired - tameNuggetSequenceIndex);

            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] tryFeedLureTamingNugget: player={} paid=1 -> totalPaid={} / required={} remaining={}",
                        player.getName().getString(),
                        tameNuggetSequenceIndex,
                        totalRequired,
                        remaining);
            }

            // IMPORTANT: do NOT play a caw directly on feed.
            // All taming audio is the remaining-sequence playback started below.

            // NEW: Tamed raven flow – if fully paid, trigger naming GUI via TamedRaven module.
            if (remaining == 0) {
                try {
                    net.z2six.featheredfriend.entity.raven.modules.TamedRaven tamed =
                            raven.getTamedRavenModule();
                    if (tamed != null) {
                        if (raven.tickCount % 40 == 0) {
                            LOG.debug("[RavenEntity] tryFeedLureTamingNugget: tame cost fully paid; triggering TamedRaven.onTamingFullyPaid. pos={}",
                                    raven.position());
                        }
                        tamed.onTamingFullyPaid(player);
                    } else if (raven.tickCount % 80 == 0) {
                        LOG.warn("[RavenEntity] tryFeedLureTamingNugget: TamedRaven module is null on fully-paid event. pos={}",
                                raven.position());
                    }
                } catch (Throwable t) {
                    if (raven.tickCount % 80 == 0) {
                        LOG.warn("[RavenEntity] tryFeedLureTamingNugget: onTamingFullyPaid failed safely: {}", t.toString());
                    }
                }
            }

            // Start / restart the remaining-sequence playback.
            startHandFeedAgreeSequence(remaining, "nugget feed");

            if (remaining == 0 && raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] tryFeedLureTamingNugget: tame cost fully paid; remaining=0. pos={}", raven.position());
            }

            return true;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] tryFeedLureTamingNugget failed safely: {}", t.toString());
            }
            return false;
        }
    }

    /**
     * Start (or restart) the hand-feed "countdown" agree-caw sequence.
     *
     * It plays raven.caw_agree exactly remainingCaws times with a short interval
     * between each, independent of the arrival-based ceremonial sequence.
     *
     * If remainingCaws <= 0, this simply cancels any active hand-feed sequence.
     *
     * Spam RMB is handled by always cancelling/resetting previous hand-feed
     * state before starting the new sequence.
     */
    private void startHandFeedAgreeSequence(int remainingCaws, String context) {
        try {
            // Always cancel any in-progress hand-feed sequence.
            handFeedSequenceActive = false;
            handFeedCawsRemaining = 0;
            handFeedCawCooldownTicks = 0;
            handFeedSequenceCursor = 0;

            if (raven.level() == null || raven.level().isClientSide) {
                LOG.debug("[RavenEntity] startHandFeedAgreeSequence: abort (no level or client-side). context={} remaining={}",
                        context, remainingCaws);
                return;
            }
            if (!raven.isAlive()) {
                LOG.debug("[RavenEntity] startHandFeedAgreeSequence: abort (raven not alive). context={} remaining={}",
                        context, remainingCaws);
                return;
            }

            if (remainingCaws <= 0) {
                LOG.debug("[RavenEntity] startHandFeedAgreeSequence: remaining<=0, cancelling. context={} remaining={} pos={}",
                        context, remainingCaws, raven.position());
                return;
            }

            // When the player feeds a nugget, the ONLY taming audio we want is the remaining-sequence playback.
            // Cancel any arrival-based "agree" sequence so it cannot interleave.
            lureAgreeSequenceActive = false;
            lureAgreeCawsRemaining = 0;
            lureAgreeCawCooldownTicks = 0;
            lureAgreeSequenceCursor = 0;

            handFeedSequenceActive = true;
            handFeedCawsRemaining = remainingCaws;
            // Start immediately: this sequence *is* the taming feedback.
            handFeedCawCooldownTicks = 0;
            handFeedSequenceCursor = Math.max(0, tameNuggetSequenceIndex);

            LOG.debug("[RavenEntity] startHandFeedAgreeSequence: START hand-feed sequence caws={} context={} pos={}",
                    remainingCaws, context, raven.position());

        } catch (Throwable t) {
            LOG.warn("[RavenEntity] startHandFeedAgreeSequence failed safely: {}", t.toString());
            // Hard-cancel on failure
            handFeedSequenceActive = false;
            handFeedCawsRemaining = 0;
            handFeedCawCooldownTicks = 0;
        }
    }

    /**
     * Try to keep the hover height under low ceilings from causing "bounce"
     * by nudging the target Y slightly downward until we get a 2-block tall
     * air column, without going below the player's feet.
     *
     * This is ONLY used for follow / lure goals and does not change the core
     * flight behavior. It just gives a more realistic hover altitude to aim for.
     */
    private double computeSafeHoverYUnderCeiling(double x, double preferredY, double z, Player player) {
        try {
            if (player == null) return preferredY;
            if (raven.level() == null) return preferredY;

            int baseX = Mth.floor(x);
            int baseZ = Mth.floor(z);

            // Do not let the raven hover below the player's feet.
            int playerFeetY = Mth.floor(player.getY());

            // How far down from preferredY we are willing to search.
            final int MAX_DOWN = 2;

            int preferredBlockY = Mth.floor(preferredY);

            double chosenCenterY = preferredY;
            boolean found = false;

            for (int down = 0; down <= MAX_DOWN; down++) {
                int colY = preferredBlockY - down;
                if (colY < playerFeetY) {
                    break;
                }

                BlockPos p0 = new BlockPos(baseX, colY, baseZ);
                BlockPos p1 = p0.above();

                boolean ok;
                try {
                    boolean empty0 = raven.level().isEmptyBlock(p0) && raven.level().getFluidState(p0).isEmpty();
                    boolean empty1 = raven.level().isEmptyBlock(p1) && raven.level().getFluidState(p1).isEmpty();
                    ok = empty0 && empty1;
                } catch (Throwable t) {
                    ok = false;
                }

                if (ok) {
                    chosenCenterY = colY + 0.5D;
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Nothing obviously better; fall back to the original height.
                chosenCenterY = preferredY;
            }

            return chosenCenterY;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] computeSafeHoverYUnderCeiling failed safely: {}", t.toString());
            }
            return preferredY;
        }
    }

    public int getFollowCooldownTicks() {
        try {
            return raven.getEntityData().get(DATA_FOLLOW_COOLDOWN_TICKS);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getFollowCooldownTicks failed safely: {}", t.toString());
            }
            return 0;
        }
    }

    public void setFollowCooldownTicks(int ticks) {
        try {
            int clamped = Math.max(0, ticks);
            int prev = 0;
            try {
                prev = raven.getEntityData().get(DATA_FOLLOW_COOLDOWN_TICKS);
            } catch (Throwable ignored) {
            }

            raven.getEntityData().set(DATA_FOLLOW_COOLDOWN_TICKS, clamped);

            if (prev != clamped) {
                if (!raven.level().isClientSide) {
                    LOG.debug("[RavenEntity] FollowCooldown set: {} -> {} pos={} ai={}",
                            prev, clamped, raven.position(), raven.getAIState());
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] setFollowCooldownTicks failed safely: {}", t.toString());
            }
        }
    }

    // Simple accessors for the tame-cost

    public int getGoldenNuggetsRequiredToTame() {
        return goldenNuggetsRequiredToTame;
    }

    public void setGoldenNuggetsRequiredToTame(int value) {
        goldenNuggetsRequiredToTame = value;
    }

    /**
     * Ensure the taming nugget sequence is initialized.
     *
     * Back-compat note:
     * - Older saves only stored {@link #NBT_TAME_NUGGETS_REQUIRED} (a count of gold nuggets).
     * - If that exists but the sequence does not, we upgrade to an all-gold sequence of that length.
     */
    public void initGoldenNuggetsRequiredToTameIfNeeded(String context) {
        try {
            if (tameNuggetSequence == null || tameNuggetSequence.isEmpty()) {
                // If we have an old count, default to that many gold nuggets.
                int desiredLen = goldenNuggetsRequiredToTame;
                if (desiredLen <= 0) {
                    RandomSource rnd = raven.getRandom();
                    desiredLen = 3 + (rnd == null ? 1 : rnd.nextInt(4)); // 3..6
                }

                // Clamp defensively
                if (desiredLen < 3) desiredLen = 3;
                if (desiredLen > 6) desiredLen = 6;

                // Randomize a sequence containing BOTH iron and gold.
                String seq = buildRandomNuggetSequence(desiredLen);
                tameNuggetSequence = seq;
                tameNuggetSequenceIndex = 0;

                // Keep the legacy "count" in sync for existing code paths.
                goldenNuggetsRequiredToTame = desiredLen;
                tamingNuggetsPaidTotal = 0;

                if (!raven.level().isClientSide && raven.tickCount % 200 == 0) {
                    LOG.debug("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded: context={} seq='{}' pos={}",
                            context, tameNuggetSequence, raven.position());
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 200 == 0) {
                LOG.warn("[RavenEntity] initGoldenNuggetsRequiredToTameIfNeeded failed safely: {}", t.toString());
            }
            if (tameNuggetSequence == null || tameNuggetSequence.isEmpty()) {
                tameNuggetSequence = "IGGG"; // safe fallback (len=4, includes both)
                tameNuggetSequenceIndex = 0;
                goldenNuggetsRequiredToTame = 4;
                tamingNuggetsPaidTotal = 0;
            }
        }
    }

    private static String buildRandomNuggetSequence(int len) {
        // Default "IGGG..." style fallback if no RNG is available elsewhere.
        if (len <= 1) return "G";

        StringBuilder sb = new StringBuilder(len);
        boolean hasI = false;
        boolean hasG = false;

        // We don't have access to the raven RNG here; use a simple PRNG from nanoTime.
        long seed = System.nanoTime() ^ (((long) len) << 32);

        for (int i = 0; i < len; i++) {
            seed = (seed * 6364136223846793005L) + 1442695040888963407L;
            boolean gold = ((seed >>> 33) & 1L) == 0L;
            char c = gold ? 'G' : 'I';
            sb.append(c);
            if (c == 'G') hasG = true;
            else hasI = true;
        }

        // Guarantee at least one of each (design request: both iron and gold).
        if (!hasI) {
            sb.setCharAt((int) ((seed >>> 3) % len), 'I');
        } else if (!hasG) {
            sb.setCharAt((int) ((seed >>> 3) % len), 'G');
        }

        return sb.toString();
    }

    public String getTameNuggetSequence() {
        return tameNuggetSequence == null ? "" : tameNuggetSequence;
    }

    public int getTameNuggetSequenceIndex() {
        return Math.max(0, tameNuggetSequenceIndex);
    }

    public void setTameNuggetSequence(String seq, int index) {
        try {
            tameNuggetSequence = (seq == null) ? "" : seq.trim().toUpperCase();
            if (tameNuggetSequence.isEmpty()) {
                tameNuggetSequenceIndex = 0;
                return;
            }

            // Sanitize to only I/G
            StringBuilder sb = new StringBuilder(tameNuggetSequence.length());
            for (int i = 0; i < tameNuggetSequence.length(); i++) {
                char c = tameNuggetSequence.charAt(i);
                if (c == 'I' || c == 'G') sb.append(c);
            }
            tameNuggetSequence = sb.toString();

            int len = tameNuggetSequence.length();
            if (len <= 0) {
                tameNuggetSequenceIndex = 0;
                return;
            }

            if (index < 0) index = 0;
            if (index > len) index = len;
            tameNuggetSequenceIndex = index;

            goldenNuggetsRequiredToTame = len;
            tamingNuggetsPaidTotal = index;
        } catch (Throwable t) {
            LOG.warn("[RavenEntity] setTameNuggetSequence failed safely: {}", t.toString());
        }
    }

    /**
     * Start the taming "agree caw" sequence:
     * plays raven.caw_agree exactly goldenNuggetsRequiredToTame times over time.
     * Once started, this sequence is driven by tickFollowOwner via tickLureAgreeSequenceProgress().
     */
    private void maybeStartLureAgreeSequence() {
        try {
            if (raven.level() == null || raven.level().isClientSide) {
                return;
            }
            if (!raven.isAlive()) {
                return;
            }
            if (lureAgreeSequenceActive) {
                // Already running; do not re-arm.
                return;
            }

            // Respect global cooldown between whole sequences
            if (lureAgreeSequenceGlobalCooldownTicks > 0) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug(
                            "[RavenEntity] maybeStartLureAgreeSequence: blocked by global cooldown={}t pos={}",
                            lureAgreeSequenceGlobalCooldownTicks,
                            raven.position()
                    );
                }
                return;
            }

            // Make sure tame cost is initialized.
            if (goldenNuggetsRequiredToTame <= 0) {
                initGoldenNuggetsRequiredToTameIfNeeded("lure agree sequence start");
            }

            int total = Math.max(0, goldenNuggetsRequiredToTame);
            int paid  = Math.max(0, tamingNuggetsPaidTotal);
            if (paid > total) {
                paid = total;
            }
            int remaining = Math.max(0, total - paid);

            // If nothing remains to pay, do not play the "how many nuggets" sequence anymore.
            if (remaining <= 0) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] maybeStartLureAgreeSequence: no remaining tame cost (total={} paid={}), skipping sequence.",
                            total, paid);
                }
                return;
            }

            lureAgreeSequenceActive = true;
            lureAgreeCawsRemaining = remaining;
            lureAgreeCawCooldownTicks = 0; // first caw as soon as possible
            lureAgreeSequenceCursor = Math.max(0, tameNuggetSequenceIndex);

            if (raven.tickCount % 40 == 0) {
                LOG.debug(
                        "[RavenEntity] maybeStartLureAgreeSequence: starting agree-caw sequence count={} total={} paid={} pos={}",
                        remaining,
                        total,
                        paid,
                        raven.position()
                );
            }

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] maybeStartLureAgreeSequence failed safely: {}", t.toString());
            }
            stopLureAgreeSequence("exception starting");
        }
    }

    /**
     * Advance the agree-caw sequence once per tick.
     * Called unconditionally from tickFollowOwner so once started it keeps going,
     * even if the player rotates and causes re-targeting.
     */
    private void tickLureAgreeSequenceProgress() {
        try {
            if (!lureAgreeSequenceActive) {
                return;
            }
            if (raven == null) {
                stopLureAgreeSequence("raven null");
                return;
            }
            if (raven.level() == null || raven.level().isClientSide) {
                // We only drive the sequence on the logical server.
                return;
            }
            if (!raven.isAlive()) {
                stopLureAgreeSequence("raven not alive");
                return;
            }

            // Once the player is actively hand-feeding nuggets, the hand-feed countdown is the
            // authoritative taming audio; don't let the arrival-based sequence compete.
            if (handFeedSequenceActive) {
                return;
            }

            if (lureAgreeCawCooldownTicks > 0) {
                lureAgreeCawCooldownTicks--;
                return;
            }

            // Play one agree caw at the raven's position via RavenSoundEngine.
            float volume = 0.9F;
            float center = 1.0F;
            try {
                String seq = getTameNuggetSequence();
                if (seq != null && !seq.isEmpty() && lureAgreeSequenceCursor >= 0 && lureAgreeSequenceCursor < seq.length()) {
                    char step = seq.charAt(lureAgreeSequenceCursor);
                    center = (step == 'G') ? 1.15F : 0.85F;
                }
            } catch (Throwable ignored) {
                center = 1.0F;
            }

            float pitch = center;
            try {
                RandomSource rnd = raven.getRandom();
                if (rnd != null) {
                    pitch = center + (rnd.nextFloat() - 0.5F) * 0.06F;
                }
            } catch (Throwable ignored) {
            }

            if (lastAgreeCawTick == raven.tickCount) {
                lureAgreeCawCooldownTicks = 1;
                return;
            }

            RavenSoundEngine.playAt(
                    raven.level(),
                    TAMING_AGREE_SOUND_ID,
                    SoundSource.NEUTRAL,
                    raven.position(),
                    volume,
                    pitch
            );
            lastAgreeCawTick = raven.tickCount;

            lureAgreeCawsRemaining--;
            lureAgreeSequenceCursor++;

            if (lureAgreeCawsRemaining <= 0) {
                // Full sequence done -> normal completion (this will start the 30s cooldown).
                stopLureAgreeSequence("sequence complete");
            } else {
                // Random small delay between caws so it sounds natural, but controlled by tunables.
                int delay = LURE_AGREE_CAW_INTERVAL_MIN_TICKS;
                try {
                    RandomSource rnd = raven.getRandom();
                    if (rnd != null) {
                        int span = Math.max(
                                0,
                                LURE_AGREE_CAW_INTERVAL_MAX_TICKS - LURE_AGREE_CAW_INTERVAL_MIN_TICKS
                        );
                        if (span == 0) {
                            delay = LURE_AGREE_CAW_INTERVAL_MIN_TICKS;
                        } else {
                            delay = LURE_AGREE_CAW_INTERVAL_MIN_TICKS + rnd.nextInt(span + 1);
                        }
                    }
                } catch (Throwable ignored) {
                    // fallback: just use the min interval
                    delay = LURE_AGREE_CAW_INTERVAL_MIN_TICKS;
                }
                lureAgreeCawCooldownTicks = Math.max(0, delay);
            }

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] tickLureAgreeSequenceProgress failed safely: {}", t.toString());
            }
            stopLureAgreeSequence("exception");
        }
    }

    private void stopLureAgreeSequence(String reason) {
        try {
            if (lureAgreeSequenceActive && raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] stopLureAgreeSequence: reason={} remaining={} pos={}",
                        reason, lureAgreeCawsRemaining, raven.position());
            }
        } catch (Throwable ignored) {
            // Logging best effort
        }

        boolean completed = "sequence complete".equals(reason);

        lureAgreeSequenceActive = false;
        lureAgreeCawsRemaining = 0;
        lureAgreeCawCooldownTicks = 0;
        lureAgreeSequenceCursor = 0;

        // Only start the big cooldown when we actually completed the sequence normally.
        if (completed && LURE_AGREE_SEQUENCE_COOLDOWN_TICKS > 0) {
            lureAgreeSequenceGlobalCooldownTicks = LURE_AGREE_SEQUENCE_COOLDOWN_TICKS;
        }
    }

    /**
     * Drive the hand-feed "countdown" agree-caw sequence once per tick.
     * Call this from RavenEntity's main tick (e.g. aiStep or tickFollowOwner),
     * on the *server side* only.
     *
     * This is separate from tickLureAgreeSequenceProgress(), which handles
     * the ceremonial arrival-based sequence with global cooldown.
     */
    public void tickHandFeedAgreeSequence() {
        try {
            if (!handFeedSequenceActive) {
                return;
            }

            if (raven == null) {
                LOG.warn("[RavenEntity] tickHandFeedAgreeSequence: raven null, cancelling.");
                handFeedSequenceActive = false;
                handFeedCawsRemaining = 0;
                handFeedCawCooldownTicks = 0;
                return;
            }

            if (raven.level() == null || raven.level().isClientSide) {
                LOG.debug("[RavenEntity] tickHandFeedAgreeSequence: wrong side or no level, cancelling. side={}",
                        raven.level() == null ? "null" : (raven.level().isClientSide ? "CLIENT" : "SERVER"));
                handFeedSequenceActive = false;
                handFeedCawsRemaining = 0;
                handFeedCawCooldownTicks = 0;
                return;
            }

            if (!raven.isAlive()) {
                LOG.debug("[RavenEntity] tickHandFeedAgreeSequence: raven not alive, cancelling.");
                handFeedSequenceActive = false;
                handFeedCawsRemaining = 0;
                handFeedCawCooldownTicks = 0;
                return;
            }

            if (handFeedCawCooldownTicks > 0) {
                handFeedCawCooldownTicks--;
                return;
            }

            // Play one agree caw at the raven's position via RavenSoundEngine.
            int before = handFeedCawsRemaining;
            try {
                if (lastAgreeCawTick == raven.tickCount) {
                    handFeedCawCooldownTicks = 1;
                    return;
                }

                float volume = 0.9F;
                // Pitch reflects the *remaining* nugget sequence: iron=lower, gold=higher.
                float center = 1.0F;
                try {
                    String seq = getTameNuggetSequence();
                    if (seq != null && !seq.isEmpty() && handFeedSequenceCursor >= 0 && handFeedSequenceCursor < seq.length()) {
                        char step = seq.charAt(handFeedSequenceCursor);
                        center = (step == 'G') ? 1.15F : 0.85F;
                    }
                } catch (Throwable ignored) {
                    center = 1.0F;
                }

                float pitch = center;
                try {
                    RandomSource rnd = raven.getRandom();
                    if (rnd != null) {
                        pitch = center + (rnd.nextFloat() - 0.5F) * 0.06F;
                    }
                } catch (Throwable ignored) {
                }

                RavenSoundEngine.playAt(
                        raven.level(),
                        TAMING_AGREE_SOUND_ID,
                        SoundSource.NEUTRAL,
                        raven.position(),
                        volume,
                        pitch
                );
                lastAgreeCawTick = raven.tickCount;
            } catch (Throwable t) {
                LOG.warn("[RavenEntity] tickHandFeedAgreeSequence: play failed: {}", t.toString());
            }

            handFeedCawsRemaining--;
            handFeedSequenceCursor++;

            LOG.debug("[RavenEntity] tickHandFeedAgreeSequence: played hand-feed caw (before={} after={} pos={})",
                    before, handFeedCawsRemaining, raven.position());

            if (handFeedCawsRemaining <= 0) {
                // Sequence done.
                handFeedSequenceActive = false;
                handFeedCawsRemaining = 0;
                handFeedCawCooldownTicks = 0;

                LOG.debug("[RavenEntity] tickHandFeedAgreeSequence: sequence COMPLETE at pos={}", raven.position());
                return;
            }

            // Schedule next caw with a small random delay.
            int delay = HAND_FEED_CAW_INTERVAL_MIN_TICKS;
            try {
                RandomSource rnd = raven.getRandom();
                if (rnd != null) {
                    int span = Math.max(0, HAND_FEED_CAW_INTERVAL_MAX_TICKS - HAND_FEED_CAW_INTERVAL_MIN_TICKS);
                    if (span == 0) {
                        delay = HAND_FEED_CAW_INTERVAL_MIN_TICKS;
                    } else {
                        delay = HAND_FEED_CAW_INTERVAL_MIN_TICKS + rnd.nextInt(span + 1);
                    }
                }
            } catch (Throwable ignored) {
                delay = HAND_FEED_CAW_INTERVAL_MIN_TICKS;
            }

            handFeedCawCooldownTicks = Math.max(0, delay);

        } catch (Throwable t) {
            LOG.warn("[RavenEntity] tickHandFeedAgreeSequence failed safely: {}", t.toString());
            // Fail-safe: cancel to avoid stuck loops.
            handFeedSequenceActive = false;
            handFeedCawsRemaining = 0;
            handFeedCawCooldownTicks = 0;
        }
    }

    // Expose followOverrideActive as a read-only flag

    public boolean isFollowOverrideActive() {
        return followOverrideActive;
    }

    @Nullable
    public Player getOwnerPlayerServerSafe() {
        try {
            if (!(raven.level() instanceof ServerLevel serverLevel)) {
                return null;
            }
            if (!raven.isTame()) {
                return null;
            }
            if (raven.getOwnerUUID() == null) {
                return null;
            }
            return serverLevel.getPlayerByUUID(raven.getOwnerUUID());
        } catch (Throwable t) {
            LOG.error("[RavenEntity] getOwnerPlayerServerSafe failed", t);
            return null;
        }
    }

    public boolean isLureFollowActive() {
        try {
            if (raven.level().isClientSide) {
                return false;
            }
            if (!raven.isAlive()) {
                // If the raven died while we still had lure state, clear it.
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("isLureFollowActive: raven not alive");
                }
                return false;
            }

            // No remembered lure => not active
            if (this.lureFollowPlayerUuid == null) {
                // Make sure all related fields are reset if they somehow weren't.
                if (lureFollowTicks != 0 || followOverrideActive || followPocketAnchor != null) {
                    clearLureFollowState("isLureFollowActive: missing UUID");
                } else {
                    this.lureFollowTicks = 0;
                }
                return false;
            }

            // Tick down the memory
            if (this.lureFollowTicks > 0) {
                this.lureFollowTicks--;
            }

            // Timer expired -> full state clear
            if (this.lureFollowTicks <= 0) {
                clearLureFollowState("isLureFollowActive: timer expired");
                return false;
            }

            // Resolve the player by stored UUID
            Player p = raven.level().getPlayerByUUID(this.lureFollowPlayerUuid);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                clearLureFollowState("isLureFollowActive: lure player invalid");
                return false;
            }

            // Lure player must STILL be holding a lure item (iron/gold nugget)
            // in either hand, otherwise we drop lure-follow immediately.
            if (!isLureItemInHand(p)) {
                clearLureFollowState("isLureFollowActive: lure player no longer holding lure item");
                return false;
            }

            // All checks passed: lure-follow is considered active.
            return true;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureFollowActive failed safely: {}", t.toString());
            }
            // On any failure, drop lure state so we don't get stuck.
            clearLureFollowState("isLureFollowActive: exception");
            return false;
        }
    }

    public @Nullable Player getLureFollowPlayerServerSafe() {
        try {
            if (!(raven.level() instanceof ServerLevel serverLevel)) return null;
            if (!isLureFollowActive()) return null;

            Player p = serverLevel.getPlayerByUUID(lureFollowPlayerUuid);
            if (p == null) return null;
            if (!p.isAlive() || p.isSpectator()) return null;

            return p;
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] getLureFollowPlayerServerSafe failed safely: {}", t.toString());
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // LURE-FOLLOW REQUEST -------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void requestLureFollowPlayer(@Nullable Player player, double distToPlayer) {
        try {
            if (raven.level().isClientSide) return;
            if (!raven.isAlive()) return;

            // Basic sanity on player
            if (player == null || !player.isAlive() || player.isSpectator()) {
                // Player is not a valid lure source -> drop lure if we had one.
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("requestLureFollowPlayer: player invalid");
                }
                return;
            }

            // If this player already has a stored/tamed raven, completely disable lure-follow
            // for them. They are only allowed a single raven companion.
            if (playerHasTamedRaven(player)) {
                if (raven.tickCount % 80 == 0) {
                    LOG.debug("[RavenEntity] requestLureFollowPlayer: player={} already has a tamed raven; ignoring lure.",
                            player.getName().getString());
                }
                return;
            }

            // If the player is NOT currently holding a lure item (iron/gold nugget),
            // treat this as "lure dropped" and clear state.
            if (!isLureItemInHand(player)) {
                if (lureFollowPlayerUuid != null || lureFollowTicks > 0 || followOverrideActive) {
                    clearLureFollowState("requestLureFollowPlayer: player no longer holding lure item");
                }
                return;
            }

            // -------------------------------------------------------------------------------------
            // LURE LOCKING:
            // If we already have an active lure player that is NOT this one, do NOT steal the bird.
            // Only when the existing lure becomes invalid (isLureFollowActive() returns false),
            // may a new player claim the lure-follow.
            // -------------------------------------------------------------------------------------
            java.util.UUID incomingId = player.getUUID();

            if (this.lureFollowPlayerUuid != null && !incomingId.equals(this.lureFollowPlayerUuid)) {
                // Check if the existing lure is still considered active.
                if (isLureFollowActive()) {
                    if (raven.tickCount % 40 == 0) {
                        LOG.debug("[RavenEntity] requestLureFollowPlayer: already lured by {} – ignoring new lure from {}",
                                this.lureFollowPlayerUuid,
                                player.getName().getString());
                    }
                    return;
                }
                // If isLureFollowActive() returned false, it has already cleared the stale state.
                // Fall through and allow this new player to take over as the lure source.
            }

            // Refresh / claim lure memory (either first time, or same player as before).
            this.lureFollowPlayerUuid = incomingId;
            int refresh = LURE_FOLLOW_REFRESH_TICKS;
            if (this.lureFollowTicks < refresh) {
                this.lureFollowTicks = refresh;
            }

            // Force FOLLOW_OWNER AI state + override
            if (raven.getAIState() != RavenAIState.FOLLOW_OWNER) {
                raven.setAIState(RavenAIState.FOLLOW_OWNER);
            }
            followOverrideActive = true;

            // Kill avoidance overrides so FOLLOW_OWNER truly wins
            try {
                setPrivateInt("playerAvoidanceOverrideTicks", 0);
                setPrivateInt("playerAvoidanceRearmCooldownTicks", 0);
            } catch (Throwable ignored) {}

            // Cancel idle/landing so we actually start moving
            invokeResetLandingState("lure follow arm");
            setPrivateInt("idleCommitTicks", 0);
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);
            setPrivateInt("roamTicksRemaining", 0);

            // Put bird into flight
            raven.setNoGravity(true);
            if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                raven.setAnimMode(RavenAnimMode.IN_AIR);
            }

            // --------------------------------------------------
            // Pocket near player (optional, mostly for logging / sanity)
            // --------------------------------------------------
            BlockPos pocket = null;
            try {
                pocket = findFollowPocketNearPlayer(player);
            } catch (Throwable ignored) {
                pocket = null;
            }

            if (pocket == null) {
                // Fallback: hover above player, still clamped to home Y
                int py = Mth.floor(player.getY());
                int y = invokeClampYToHomeBounds(py + 3);
                pocket = new BlockPos(Mth.floor(player.getX()), y, Mth.floor(player.getZ()));
            }

            followPocketAnchor = pocket;
            followPocketRecalcCooldownTicks = 10; // small pause before re-scanning

            // Desired final follow position: in front of player's face.
            Vec3 desiredFront = computeFollowFrontPosition(player);

            Vec3 safeGoal = computeSafeFollowGoalXZLockedY(desiredFront);
            invokeSetFlyTargetNoClamp(safeGoal, FOLLOW_DIRECT_TTL_TICKS);
            followDirectRearmTicks = FOLLOW_DIRECT_REARM_TICKS;

            if (raven.tickCount % 20 == 0) {
                LOG.debug("[RavenEntity] LURE FOLLOW armed: player={} dist={} pocket={} desiredFront={} safeGoal={} lureTicks={} aiState={} pos={}",
                        player.getName().getString(),
                        String.format("%.2f", distToPlayer),
                        pocket,
                        desiredFront,
                        safeGoal,
                        this.lureFollowTicks,
                        raven.getAIState(),
                        raven.position());
            }

        } catch (Throwable t) {
            LOG.error("[RavenEntity] requestLureFollowPlayer failed safely", t);
            try {
                // Keep a tiny grace but drop the hard override if something exploded
                if (this.lureFollowTicks > LURE_FOLLOW_GRACE_TICKS) {
                    this.lureFollowTicks = LURE_FOLLOW_GRACE_TICKS;
                }
                followOverrideActive = false;
                followPocketAnchor = null;
            } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FOLLOW OWNER / LURE TICK --------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void tickFollowOwner() {
        tickFollowOwnerDirect();
    }

    private void tickFollowOwnerDirect() {
        try {
            raven.setNoGravity(true);
            if (raven.getAnimMode() != RavenAnimMode.IN_AIR) {
                raven.setAnimMode(RavenAnimMode.IN_AIR);
            }

            try {
                if (!raven.level().isClientSide && lureAgreeSequenceGlobalCooldownTicks > 0) {
                    lureAgreeSequenceGlobalCooldownTicks--;
                }
            } catch (Throwable ignored) {
            }

            tickLureAgreeSequenceProgress();
            tickHandFeedAgreeSequence();

            invokeResetLandingState("follow-direct");
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);

            try {
                if (!raven.level().isClientSide) {
                    if (lureFollowTicks > 0) {
                        lureFollowTicks--;
                    }
                    if (lureFollowTicks <= 0 && lureFollowPlayerUuid != null) {
                        clearLureFollowState("follow: lure timer expired");
                    }
                }
            } catch (Throwable ignored) {
            }

            Player target = null;
            boolean usingLure = false;

            try {
                target = getOwnerPlayerServerSafe();
            } catch (Throwable ignored) {
                target = null;
            }

            if (target == null) {
                try {
                    Player lure = getLureFollowPlayerServerSafe();
                    if (lure != null) {
                        target = lure;
                        usingLure = true;
                    }
                } catch (Throwable ignored) {
                    target = null;
                }
            }

            if (target == null) {
                if (isLureFollowActive()) {
                    clearLureFollowState("follow: target missing");
                }
                wasAtLureGoalLastTick = false;
                wasAtFollowGoalLastTick = false;

                raven.setAIState(RavenAIState.IDLE_GROUND);
                setPrivateInt("idleTicksRemaining", 0);
                invokeClearFlyTarget();
                setPrivateInt("roamTicksRemaining", 0);
                return;
            }

            if (!usingLure && followOverrideActive) {
                followOverrideActive = false;
            }

            if (usingLure) {
                try {
                    if (lureFollowTicks < LURE_FOLLOW_GRACE_TICKS && lureFollowPlayerUuid != null) {
                        lureFollowTicks = LURE_FOLLOW_GRACE_TICKS;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (invokeIsOutOfHomeBounds(target.position())) {
                triggerFollowCooldownAndReturn();
                return;
            }

            boolean atGoal = false;
            Vec3 computedFront = null;
            try {
                computedFront = computeFollowFrontPosition(target);
                atGoal = isCloseEnoughToFollowPlayer(target);
            } catch (Throwable ignored) {
                atGoal = false;
            }

            boolean firstArrivalAny = atGoal && !wasAtFollowGoalLastTick;
            wasAtFollowGoalLastTick = atGoal;

            boolean shouldStartAgreeSequence = false;
            if (usingLure && atGoal && !wasAtLureGoalLastTick && lureAgreeSequenceGlobalCooldownTicks <= 0) {
                shouldStartAgreeSequence = true;
            }
            wasAtLureGoalLastTick = usingLure && atGoal;

            if (atGoal) {
                if (firstArrivalAny) {
                    onRavenArrivedAtFollowTarget(target, usingLure, computedFront);
                }
                if (shouldStartAgreeSequence) {
                    maybeStartLureAgreeSequence();
                }
                invokeClearFlyTarget();
                raven.setDeltaMovement(raven.getDeltaMovement().scale(0.6D));
                return;
            }

            if (followDirectRearmTicks > 0) {
                followDirectRearmTicks--;
            }

            int flyTimeout = getPrivateInt("flyTargetTimeoutTicks", 0);
            if (flyTimeout > 0) {
                setPrivateInt("flyTargetTimeoutTicks", flyTimeout - 1);
            }

            Vec3 currentFlyTarget = getFlyTargetField();
            if (currentFlyTarget == null || getPrivateInt("flyTargetTimeoutTicks", 0) <= 0 || followDirectRearmTicks <= 0) {
                Vec3 desired = (computedFront != null) ? computeSafeFollowGoalXZLockedY(computedFront) : target.position();
                invokeSetFlyTargetNoClamp(desired, FOLLOW_DIRECT_TTL_TICKS);
                followDirectRearmTicks = FOLLOW_DIRECT_REARM_TICKS;
            }

            invokeMaybeAvoidOrRetargetDuringFlight(raven.getRandom());
            invokeFlyTowardTarget(getFlySpeedBase());

        } catch (Throwable t) {
            LOG.error("[RavenEntity] tickFollowOwnerDirect failed safely", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FOLLOW COOLDOWN / RETURN --------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public void triggerFollowCooldownAndReturn() {
        try {
            RandomSource rnd = raven.getRandom();

            int cd = FOLLOW_COOLDOWN_MIN_TICKS + rnd.nextInt(Math.max(1, FOLLOW_COOLDOWN_MAX_TICKS - FOLLOW_COOLDOWN_MIN_TICKS + 1));
            setFollowCooldownTicks(cd);

            raven.setAIState(RavenAIState.ROAM_FLY);

            Vec3 ret = invokeHomeCenterReturnTarget();
            invokeSetFlyTarget(ret, 10 * 20);

            setPrivateInt("roamTicksRemaining", 0);

            invokeResetLandingState("follow cooldown");
            setPrivateInt("idleLockTicks", 0);
            setPrivateInt("idleLeafLossTicks", 0);

            followDirectRearmTicks = 0;

            // Leaving follow mode due to bounds -> stop the taming agree-caw sequence for this session.
            stopLureAgreeSequence("follow cooldown");
            wasAtLureGoalLastTick = false;

            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] Follow bounds violated -> cooldown {} ticks and return to home", cd);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] triggerFollowCooldownAndReturn failed safely: {}", t.toString());
            }
        }
    }

    // ------------------------
    // Lure/follow resetting
    // ------------------------

    public void clearLureFollowState(String reason) {
        try {
            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] clearLureFollowState: reason={} hadUuid={} ticksLeft={} overrideActive={} pocket={} pos={}",
                        reason,
                        (lureFollowPlayerUuid != null),
                        lureFollowTicks,
                        followOverrideActive,
                        followPocketAnchor,
                        raven.position());
            }
        } catch (Throwable ignored) {
            // Logging is best-effort only.
        }

        // Hard reset of all lure-follow specific state.
        lureFollowPlayerUuid = null;
        lureFollowTicks = 0;

        followOverrideActive = false;
        followPocketAnchor = null;
        followPocketRecalcCooldownTicks = 0;

        // Also reset any in-progress agree-caw sequence for this lure session.
        stopLureAgreeSequence("clearLureFollowState");
        wasAtLureGoalLastTick = false;

        // Stop any hand-feed countdown that might still be running.
        handFeedSequenceActive = false;
        handFeedCawsRemaining = 0;
        handFeedCawCooldownTicks = 0;
        handFeedSequenceCursor = 0;
    }

    // Returns true if the player is *currently* holding a lure item (iron/gold nugget) in either hand.
    private boolean isLureItemInHand(Player player) {
        try {
            if (player == null) return false;

            ItemStack main = player.getMainHandItem();
            ItemStack off  = player.getOffhandItem();

            return isLureItem(main) || isLureItem(off);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isLureItemInHand failed safely: {}", t.toString());
            }
            return false;
        }
    }

    private boolean isLureItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(Items.GOLD_NUGGET) || stack.is(Items.IRON_NUGGET);
    }

    // ------------------------
    // Follow player TP helpers
    // ------------------------

    @Nullable
    public BlockPos findFollowPocketNearPlayer(Player player) {
        try {
            if (player == null || !player.isAlive() || player.isSpectator()) {
                return null;
            }

            // -----------------------------
            // Base point: IN FRONT of player
            // -----------------------------
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;
            double lenXZ = Math.sqrt(lx * lx + lz * lz);

            if (lenXZ < 1.0E-4D) {
                // Degenerate look (e.g. straight up/down) -> pick a stable fallback.
                lx = 0.0D;
                lz = 1.0D;
                lenXZ = 1.0D;
            }
            lx /= lenXZ;
            lz /= lenXZ;

            // Distance from player center to the "front" pocket center.
            final double FRONT_DISTANCE = 2.0D;

            double baseX = player.getX() + lx * FRONT_DISTANCE;
            double baseZ = player.getZ() + lz * FRONT_DISTANCE;

            // Place the bottom of the 3x3x3 pocket slightly below eye level.
            double baseYRaw = player.getY() + player.getEyeHeight() - 1.0D;
            int baseYInt = invokeClampYToHomeBounds(Mth.floor(baseYRaw));
            double baseY = baseYInt + 0.5D;

            Vec3 centerVec = new Vec3(baseX, baseY, baseZ);

            // Respect home radius bounds.
            centerVec = invokeClampTargetToHomeBounds(centerVec);

            BlockPos center = BlockPos.containing(centerVec);

            long seed =
                    raven.getUUID().getLeastSignificantBits()
                            ^ player.getUUID().getMostSignificantBits()
                            ^ (long) raven.tickCount
                            ^ 0xF0110FACE5L; // valid hex salt

            // -----------------------------
            // Reuse your 3x3x3 empty-pocket scan
            // -----------------------------
            // Small radius so we stay around "front of face".
            net.z2six.featheredfriend.entity.raven.modules.Teleportation tp = raven.getTeleportation();
            BlockPos pocket = (tp == null) ? null : tp.findEmptyTeleportBlock3x3x3Near(center, 4, 80, seed, raven);

            if (pocket == null) {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: no 3x3x3 pocket near front center={} player={} pos={}",
                            center, player.getName().getString(), raven.position());
                }
            } else {
                if (raven.tickCount % 40 == 0) {
                    LOG.debug("[RavenEntity] findFollowPocketNearPlayer: pocket={} for player={} frontCenter={} pos={}",
                            pocket, player.getName().getString(), center, raven.position());
                }
            }

            return pocket;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findFollowPocketNearPlayer failed safely: {}", t.toString());
            }
            return null;
        }
    }

    @Nullable
    public BlockPos findFollowPocketNearPlayerInternal(BlockPos playerCenter, int playerY, int[] yOffsets, int maxR) {
        try {
            if (playerCenter == null) return null;

            // Ring scan: r=0..maxR, testing perimeter of square ring in a stable order.
            // This guarantees “closest” in a discrete sense.
            for (int r = 0; r <= maxR; r++) {
                // For each ring, try preferred Y first (above)
                for (int yo : yOffsets) {
                    int yRaw = playerY + yo;

                    // Clamp to your home vertical bounds policy
                    int y = invokeClampYToHomeBounds(yRaw);

                    // We also refuse “below player” in the preferred pass by caller choosing yOffsets accordingly.
                    // Still, if clamp pushes down unexpectedly, keep it sane:
                    if (yo >= 0 && y < playerY) {
                        y = playerY;
                    }

                    // Perimeter scan of square ring
                    int x0 = playerCenter.getX() - r;
                    int x1 = playerCenter.getX() + r;
                    int z0 = playerCenter.getZ() - r;
                    int z1 = playerCenter.getZ() + r;

                    // Top edge z0: x0..x1
                    for (int x = x0; x <= x1; x++) {
                        BlockPos cand = new BlockPos(x, y, z0);
                        if (isFollowPocketCandidateOk(cand)) return cand;
                    }
                    // Bottom edge z1: x0..x1
                    if (z1 != z0) {
                        for (int x = x0; x <= x1; x++) {
                            BlockPos cand = new BlockPos(x, y, z1);
                            if (isFollowPocketCandidateOk(cand)) return cand;
                        }
                    }
                    // Left edge x0: z0+1..z1-1
                    for (int z = z0 + 1; z <= z1 - 1; z++) {
                        BlockPos cand = new BlockPos(x0, y, z);
                        if (isFollowPocketCandidateOk(cand)) return cand;
                    }
                    // Right edge x1: z0+1..z1-1
                    if (x1 != x0) {
                        for (int z = z0 + 1; z <= z1 - 1; z++) {
                            BlockPos cand = new BlockPos(x1, y, z);
                            if (isFollowPocketCandidateOk(cand)) return cand;
                        }
                    }
                }
            }

            return null;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] findFollowPocketNearPlayerInternal failed safely: {}", t.toString());
            }
            return null;
        }
    }

    private boolean isFollowPocketCandidateOk(BlockPos anchor) {
        try {
            if (anchor == null) return false;

            // Must be within home bounds (using pocket center)
            Vec3 center = new Vec3(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            if (invokeIsOutOfHomeBounds(center)) return false;

            // Must be a fully empty 3x3x3 pocket (air + no fluid) at anchor, with y..y+2
            if (!isEmptyTeleportPocket3x3x3At(anchor)) return false;

            return true;

        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isEmptyTeleportPocket3x3x3At(BlockPos anchor) {
        try {
            if (anchor == null) return false;

            int x0 = anchor.getX() - 1;
            int y0 = anchor.getY();
            int z0 = anchor.getZ() - 1;

            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    for (int dy = 0; dy < 3; dy++) {
                        BlockPos p = new BlockPos(x0 + dx, y0 + dy, z0 + dz);
                        if (!raven.level().isEmptyBlock(p)) return false;
                        if (!raven.level().getFluidState(p).isEmpty()) return false;
                    }
                }
            }

            return true;
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] isEmptyTeleportPocket3x3x3At failed safely: {}", t.toString());
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GOAL COMPUTATION ---------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    public Vec3 computeSafeFollowGoalXZLockedY(Vec3 rawGoal) {
        try {
            if (rawGoal == null) return null;

            // Lock Y exactly to the follow goal Y.
            final int y = Mth.floor(rawGoal.y + 1.0E-4D);

            // Snap XZ to block centers to stabilize.
            final double baseX = Math.floor(rawGoal.x) + 0.5D;
            final double baseZ = Math.floor(rawGoal.z) + 0.5D;

            BlockPos base = BlockPos.containing(baseX, y, baseZ);

            // If base is already empty enough, take it.
            if (raven.level().isEmptyBlock(base) && raven.level().getFluidState(base).isEmpty()
                    && raven.level().isEmptyBlock(base.above())) {
                return new Vec3(baseX, rawGoal.y, baseZ);
            }

            // Otherwise: search a small horizontal ring at SAME Y.
            final int R = 3;
            BlockPos chosen = null;

            for (int r = 1; r <= R && chosen == null; r++) {
                for (int dx = -r; dx <= r && chosen == null; dx++) {
                    int dzA = -r;
                    int dzB = r;

                    BlockPos p1 = base.offset(dx, 0, dzA);
                    if (raven.level().isEmptyBlock(p1) && raven.level().getFluidState(p1).isEmpty() && raven.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dzB != dzA) {
                        BlockPos p2 = base.offset(dx, 0, dzB);
                        if (raven.level().isEmptyBlock(p2) && raven.level().getFluidState(p2).isEmpty() && raven.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }

                for (int dz = -r + 1; dz <= r - 1 && chosen == null; dz++) {
                    int dxA = -r;
                    int dxB = r;

                    BlockPos p1 = base.offset(dxA, 0, dz);
                    if (raven.level().isEmptyBlock(p1) && raven.level().getFluidState(p1).isEmpty() && raven.level().isEmptyBlock(p1.above())) {
                        chosen = p1;
                        break;
                    }

                    if (dxB != dxA) {
                        BlockPos p2 = base.offset(dxB, 0, dz);
                        if (raven.level().isEmptyBlock(p2) && raven.level().getFluidState(p2).isEmpty() && raven.level().isEmptyBlock(p2.above())) {
                            chosen = p2;
                            break;
                        }
                    }
                }
            }

            if (chosen != null) {
                Vec3 out = new Vec3(chosen.getX() + 0.5D, rawGoal.y, chosen.getZ() + 0.5D);
                out = invokeClampTargetToHomeBounds(out);
                return out;
            }

            // Fallback: return raw goal after clamping.
            return invokeClampTargetToHomeBounds(new Vec3(baseX, rawGoal.y, baseZ));

        } catch (Throwable t) {
            return rawGoal;
        }
    }

    private void onRavenArrivedAtFollowTarget(Player target, boolean usingLure, @Nullable Vec3 frontGoal) {
        try {
            if (raven == null) return;
            if (raven.level() == null || raven.level().isClientSide) return;
            if (target == null) return;

            LOG.debug("[RavenEntity] onRavenArrivedAtFollowTarget: target={} usingLure={} pos={} frontGoal={}",
                    target.getName().getString(),
                    usingLure,
                    raven.position(),
                    frontGoal);

            // For lure-follow, the taming audio feedback is handled by the dedicated
            // (low/high pitch) agree-caw sequences, so we intentionally do not play
            // an extra "arrival" caw here to avoid double-playing in the same moment.
            if (usingLure) {
                return;
            }

            String soundId;
            float volume;
            float pitchMin;
            float pitchMax;

            // Owner-follow arrival: gentle air-woosh.
            soundId = ARRIVAL_SOUND_ID;       // "featheredfriend:raven.arrival"
            volume = 0.8F;
            pitchMin = 0.95F;
            pitchMax = 1.05F;

            try {
                float lo = pitchMin;
                float hi = pitchMax;
                if (lo > hi) {
                    float tmp = lo;
                    lo = hi;
                    hi = tmp;
                }

                float pitch;
                try {
                    RandomSource rnd = raven.getRandom();
                    float t = (rnd == null) ? 0.5F : rnd.nextFloat();
                    pitch = lo + (hi - lo) * t;
                } catch (Throwable ignored) {
                    pitch = (lo + hi) * 0.5F;
                }

                RavenSoundEngine.playAt(
                        raven.level(),
                        soundId,
                        SoundSource.NEUTRAL,
                        raven.position(),
                        volume,
                        pitch
                );

                LOG.debug("[RavenEntity] onRavenArrivedAtFollowTarget: played arrival sound={} usingLure={} pos={}",
                        soundId,
                        usingLure,
                        raven.position());
            } catch (Throwable t) {
                if (raven.tickCount % 80 == 0) {
                    LOG.warn("[RavenEntity] onRavenArrivedAtFollowTarget: sound playback failed safely: {}", t.toString());
                }
            }

            // If later you want to hook Teleportation / achievements / stats,
            // this remains the central arrival hook.

        } catch (Throwable t) {
            if (raven != null && raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] onRavenArrivedAtFollowTarget failed safely: {}", t.toString());
            }
        }
    }

    public Vec3 computeFollowFrontPosition(Player player) {
        try {
            Vec3 playerPos = player.position();

            // Look direction, XZ only (stable)
            Vec3 look = player.getLookAngle();
            double lx = look.x;
            double lz = look.z;

            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1.0E-4D) {
                // Fallback: use player->raven direction as "front" so we don't get a junk look vector.
                double dx = raven.getX() - playerPos.x;
                double dz = raven.getZ() - playerPos.z;
                double len2 = Math.sqrt(dx * dx + dz * dz);
                if (len2 < 1.0E-4D) {
                    lx = 1.0D;
                    lz = 0.0D;
                    len = 1.0D;
                } else {
                    lx = dx / len2;
                    lz = dz / len2;
                    len = 1.0D;
                }
            } else {
                lx /= len;
                lz /= len;
            }

            // "Right in front of it (about a block between raven and player)"
            final double FOLLOW_FRONT_DISTANCE = 3.0D;

            double tx = playerPos.x + lx * FOLLOW_FRONT_DISTANCE;
            double tz = playerPos.z + lz * FOLLOW_FRONT_DISTANCE;

            // Base policy: near the player's eyes.
            double eyeY = player.getEyeY();
            double preferredY = eyeY;

            // Nudge the hover height down slightly if there is a low ceiling
            // above the desired front position so the raven doesn't scrape it.
            double safeY = computeSafeHoverYUnderCeiling(tx, preferredY, tz, player);

            // Clamp to home bounds *after* we've picked a safe local hover height.
            int tyInt = invokeClampYToHomeBounds(Mth.floor(safeY));
            double ty = tyInt + 0.05D;

            Vec3 raw = new Vec3(tx, ty, tz);
            Vec3 clamped = invokeClampTargetToHomeBounds(raw);

            if (raven.tickCount % 40 == 0) {
                LOG.debug("[RavenEntity] computeFollowFrontPosition: playerPos={} eyeY={} preferredY={} safeY={} out={} raw={} dist={}",
                        playerPos,
                        String.format("%.2f", eyeY),
                        String.format("%.2f", preferredY),
                        String.format("%.2f", safeY),
                        clamped,
                        raw,
                        String.format("%.2f", FOLLOW_FRONT_DISTANCE));
            }

            return clamped;

        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] computeFollowFrontPosition failed safely: {}", t.toString());
            }
            // Fallback: hover slightly above the raven itself.
            return raven.position().add(0.0D, 1.5D, 0.0D);
        }
    }

    public boolean isCloseEnoughToFollowPlayer(Player player) {
        try {
            if (player == null) return false;

            Vec3 goal = computeFollowFrontPosition(player);
            if (goal == null) return false;

            Vec3 pos = raven.position();

            double dx = pos.x - goal.x;
            double dz = pos.z - goal.z;
            double dXZ2 = dx * dx + dz * dz;

            double dy = Math.abs(pos.y - goal.y);

            // Loosened tolerances a bit so we don't micro-chase directly into ceilings.
            // XZ: ~1.05 blocks radius
            // Y : ~1.25 blocks vertical tolerance
            boolean closeXZ = dXZ2 <= (1.05D * 1.05D);
            boolean closeY  = dy <= 1.25D;

            return closeXZ && closeY;

        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REFLECTION HELPERS INTO RavenEntity PRIVATES ------------------------------------------------
    // ---------------------------------------------------------------------------------------------

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FIELD_MISS_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> METHOD_MISS_CACHE = ConcurrentHashMap.newKeySet();

    @Nullable
    private static Field getCachedField(String fieldName) {
        if (fieldName == null) return null;
        if (FIELD_MISS_CACHE.contains(fieldName)) return null;

        Field cached = FIELD_CACHE.get(fieldName);
        if (cached != null) return cached;

        try {
            Field f = RavenEntity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            FIELD_CACHE.put(fieldName, f);
            return f;
        } catch (NoSuchFieldException ex) {
            FIELD_MISS_CACHE.add(fieldName);
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildMethodKey(String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null ? "" : name);
        sb.append('#').append(params == null ? 0 : params.length).append(':');
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(params[i] == null ? "null" : params[i].getName());
            }
        }
        return sb.toString();
    }

    @Nullable
    private static Method getCachedMethod(String methodName, Class<?>... params) {
        if (methodName == null) return null;
        String key = buildMethodKey(methodName, params);
        if (METHOD_MISS_CACHE.contains(key)) return null;

        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;

        try {
            Method m = RavenEntity.class.getDeclaredMethod(methodName, params);
            m.setAccessible(true);
            METHOD_CACHE.put(key, m);
            return m;
        } catch (NoSuchMethodException ex) {
            METHOD_MISS_CACHE.add(key);
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setPrivateInt(String fieldName, int value) {
        try {
            Field f = getCachedField(fieldName);
            if (f == null) return;
            f.setInt(raven, value);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.setPrivateInt({}) failed: {}", fieldName, t.toString());
            }
        }
    }

    private int getPrivateInt(String fieldName, int fallback) {
        try {
            Field f = getCachedField(fieldName);
            if (f == null) return fallback;
            return f.getInt(raven);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateInt({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private int getPrivateStaticInt(String fieldName, int fallback) {
        try {
            Field f = getCachedField(fieldName);
            if (f == null) return fallback;
            return f.getInt(null);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateStaticInt({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private double getPrivateStaticDouble(String fieldName, double fallback) {
        try {
            Field f = getCachedField(fieldName);
            if (f == null) return fallback;
            return f.getDouble(null);
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getPrivateStaticDouble({}) failed: {}", fieldName, t.toString());
            }
            return fallback;
        }
    }

    private Vec3 getFlyTargetField() {
        try {
            Field f = getCachedField("flyTarget");
            if (f == null) return null;
            Object v = f.get(raven);
            if (v instanceof Vec3) {
                return (Vec3) v;
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.getFlyTargetField failed: {}", t.toString());
            }
        }
        return null;
    }

    private int getStuckTicksThreshold() {
        return getPrivateStaticInt("STUCK_TICKS_THRESHOLD", 40);
    }

    private double getFlySpeedBase() {
        return getPrivateStaticDouble("FLY_SPEED_BASE", 0.25D);
    }

    private void invokeResetLandingState(String reason) {
        try {
            Method m = getCachedMethod("resetLandingState", String.class);
            if (m != null) {
                m.invoke(raven, reason);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeResetLandingState failed: {}", t.toString());
            }
        }
    }

    private void invokeClearFlyTarget() {
        try {
            Method m = getCachedMethod("clearFlyTarget");
            if (m != null) {
                m.invoke(raven);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClearFlyTarget failed: {}", t.toString());
            }
        }
    }

    private int invokeClampYToHomeBounds(int y) {
        try {
            Method m = getCachedMethod("clampYToHomeBounds", int.class);
            if (m != null) {
                Object res = m.invoke(raven, y);
                if (res instanceof Integer) {
                    return (Integer) res;
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClampYToHomeBounds failed: {}", t.toString());
            }
        }
        return y;
    }

    private Vec3 invokeClampTargetToHomeBounds(Vec3 in) {
        try {
            Method m = getCachedMethod("clampTargetToHomeBounds", Vec3.class);
            if (m != null) {
                Object res = m.invoke(raven, in);
                if (res instanceof Vec3) {
                    return (Vec3) res;
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeClampTargetToHomeBounds failed: {}", t.toString());
            }
        }
        return in;
    }

    private boolean invokeIsOutOfHomeBounds(Vec3 pos) {
        try {
            Method m = getCachedMethod("isOutOfHomeBounds", Vec3.class);
            if (m != null) {
                Object res = m.invoke(raven, pos);
                if (res instanceof Boolean) {
                    return (Boolean) res;
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeIsOutOfHomeBounds failed: {}", t.toString());
            }
        }
        return false;
    }

    private Vec3 invokeHomeCenterReturnTarget() {
        try {
            Method m = getCachedMethod("homeCenterReturnTarget");
            if (m != null) {
                Object res = m.invoke(raven);
                if (res instanceof Vec3) {
                    return (Vec3) res;
                }
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeHomeCenterReturnTarget failed: {}", t.toString());
            }
        }
        // Fallback: current pos to avoid nulls
        return raven.position();
    }

    private void invokeSetFlyTarget(Vec3 target, int ttlTicks) {
        try {
            Method m = getCachedMethod("setFlyTarget", Vec3.class, int.class);
            if (m != null) {
                m.invoke(raven, target, ttlTicks);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeSetFlyTarget failed: {}", t.toString());
            }
        }
    }

    private void invokeSetFlyTargetNoClamp(Vec3 target, int ttlTicks) {
        try {
            Method m = getCachedMethod("setFlyTargetNoClamp", Vec3.class, int.class);
            if (m != null) {
                m.invoke(raven, target, ttlTicks);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeSetFlyTargetNoClamp failed: {}", t.toString());
            }
        }
    }

    private void invokeMaybeAvoidOrRetargetDuringFlight(RandomSource rnd) {
        try {
            Method m = getCachedMethod("maybeAvoidOrRetargetDuringFlight", RandomSource.class);
            if (m != null) {
                m.invoke(raven, rnd);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeMaybeAvoidOrRetargetDuringFlight failed: {}", t.toString());
            }
        }
    }

    private void invokeFlyTowardTarget(double speed) {
        try {
            Method m = getCachedMethod("flyTowardTarget", double.class);
            if (m != null) {
                m.invoke(raven, speed);
            }
        } catch (Throwable t) {
            if (raven.tickCount % 80 == 0) {
                LOG.warn("[RavenEntity] LureFollowTame.invokeFlyTowardTarget failed: {}", t.toString());
            }
        }
    }

}
