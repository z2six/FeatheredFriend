package net.z2six.featheredfriend.mixin;

import net.z2six.featheredfriend.Constants;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    
    @Inject(at = @At("TAIL"), method = "<init>")
    private void init(CallbackInfo info) {
        
        Constants.LOG.debug("This line is printed by FeatheredFriend common mixin!");
        Constants.LOG.debug("MC Version: {}", Minecraft.getInstance().getVersionType());
    }
}