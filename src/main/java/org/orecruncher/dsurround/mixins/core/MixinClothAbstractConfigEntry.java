package org.orecruncher.dsurround.mixins.core;

import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NOTE: This mixin will fail application if Cloth Config is not present. Not harmful, just emits noise into logs
 * and can make some folks concerned.
 *
 * Uses a HEAD @Inject + setReturnValue rather than @Overwrite, matching the 1.20.1 build. Two reasons:
 * <ul>
 *   <li>@Overwrite is exclusive. This targets a method on ANOTHER MOD's class
 *       ({@code me.shedaniel.clothconfig2.api.AbstractConfigEntry}), so a Cloth Config rename or a
 *       second mod touching the same method makes one of them fail to apply - and with
 *       {@code "required": true} plus {@code defaultRequire: 1} that is a launch crash, not a
 *       silent degradation. A HEAD inject composes with other injects instead.</li>
 *   <li>@Overwrite needs a mapping for the method it replaces. The 1.20.1 build hit exactly that
 *       wall on this third-party method and had to switch; {@code remap = false} is set here for
 *       the same reason, so the three builds behave identically.</li>
 * </ul>
 */
@Mixin(AbstractConfigEntry.class)
public class MixinClothAbstractConfigEntry {

    /**
     * Preserve style of Component.  The current implementation overrides color settings to force Gray.
     */
    @Inject(method = "getDisplayedFieldName", at = @At("HEAD"), cancellable = true, remap = false)
    public void dsurround_getDisplayedFieldName(CallbackInfoReturnable<Component> cir) {
        var self = (AbstractConfigEntry)((Object)this);
        MutableComponent text = self.getFieldName().copy();
        boolean hasError = self.getConfigError().isPresent();
        boolean isEdited = self.isEdited();

        if (!hasError && !isEdited) {
            // If the text entry does not have a color set, force
            // to gray.
            var color = text.getStyle().getColor();
            if (color == null)
                text = text.withStyle(ChatFormatting.GRAY);
        }

        if (hasError) {
            text = text.withStyle(ChatFormatting.RED);
        }

        if (isEdited) {
            text = text.withStyle(ChatFormatting.ITALIC);
        }

        if (!self.isEnabled()) {
            text = text.withStyle(ChatFormatting.DARK_GRAY);
        }

        cir.setReturnValue(text);
    }

}
