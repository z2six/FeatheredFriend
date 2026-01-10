// common/src/main/java/net/z2six/featheredfriend/menu/SealBreakGate.java
package net.z2six.featheredfriend.menu;



/**
 * // common/src/main/java/net/z2six/featheredfriend/menu/SealBreakGate.java
 *
 * Common (loader-agnostic) hook implemented by loader-side menus that want to be
 * notified that a seal was broken during the current container session.
 *
 * This exists specifically to avoid common code referencing loader-only menu classes.
 */
public interface SealBreakGate {

    /**
     * Marks the current container session as having had its seal broken.
     * Server-side only usage.
     */
    void markSealBroken(String reason);
}
