package org.orecruncher.dsurround.processing.accents;

import org.orecruncher.dsurround.lib.platform.PlatformCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.IItemLibrary;
import org.orecruncher.dsurround.config.libraries.ITagLibrary;
import org.orecruncher.dsurround.lib.collections.ObjectArray;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.processing.FootstepGenerator;
import org.orecruncher.dsurround.sound.ISoundFactory;

public class FootstepAccents {

    static final ITagLibrary TAG_LIBRARY = ContainerManager.resolve(ITagLibrary.class);

    private final ObjectArray<IFootstepAccentProvider> providers = new ObjectArray<>();

    public FootstepAccents(Configuration config, IItemLibrary itemLibrary) {
        this.providers.add(new ArmorAccents(config, itemLibrary));
        this.providers.add(new FloorSqueakAccent(config));

        // Only register these providers if Presence Footsteps is not installed
        if (!PlatformCompat.isModLoaded(Constants.MOD_PRESENCE_FOOTSTEPS)) {
            this.providers.add(new WaterySurfaceAccent(config));
        }
    }

    public void collect(final LivingEntity entity, final BlockPos pos, final BlockState blockState, final ObjectArray<ISoundFactory> in) {
        // One definition of "waterlogged" for the whole mod: a SOLID surface holding a fluid, i.e.
        // not air and not a fluid block. Asking only for a non-empty fluid state would be true for
        // pure water and lava as well, which contradicts the discriminator the footstep pipeline
        // uses (FootstepGenerator.isNotSolidSurface) and would make the two disagree the moment a
        // new caller raises a step event with a fluid state.
        var isWaterLogged = FootstepGenerator.isWaterlogged(blockState);
        this.providers.forEach(provider -> {
            if (provider.isEnabled())
                provider.collect(entity, pos, blockState, isWaterLogged, in);
        });
    }
}