package net.z2six.featheredfriend.item;

/**
 * Courier-focused armor stats used for tooltip display and future raven armor gameplay.
 *
 * @param maxHits            how many landed hits the raven can take
 * @param dodgeChancePercent chance to dodge a hit, in percent (0-100)
 * @param detectionRadius    hostile detection radius in blocks
 * @param payloadSafetyPercent chance payload is kept on landed hit, in percent (0-100)
 * @param healthRegenPerMinute passive health regeneration while alive/despawned (HP per minute)
 */
public record RavenArmorStats(int maxHits,
                              int dodgeChancePercent,
                              int detectionRadius,
                              int payloadSafetyPercent,
                              int healthRegenPerMinute) {
}
