// neoforge/src/main/java/net/z2six/featheredfriend/entity/raven/modules/Teleportation.java
package net.z2six.featheredfriend.entity.raven.modules;

import com.mojang.logging.LogUtils;
import net.z2six.featheredfriend.entity.raven.RavenEntity;
import org.slf4j.Logger;

public final class Teleportation {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final RavenEntity raven;

    public Teleportation(RavenEntity raven) {
        this.raven = raven;
    }
}
