package org.orecruncher.dsurround.processing;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.config.libraries.ISoundLibrary;
import org.orecruncher.dsurround.eventing.ClientState;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.logging.IModLog;
import org.orecruncher.dsurround.lib.random.Randomizer;
import org.orecruncher.dsurround.sound.IAudioPlayer;

import java.util.Set;

/**
 * Crackling for a torch held in the player's hand. A torch burns in hand too, so while
 * one is held (main or off hand) a short crackle fires on a timer - more often than the
 * block-placed torch, since the sound is right next to the ear. Reuses the same
 * {@code block.torch_burn} event as the placed torch; the sound attaches to the player so
 * it follows them. Played as discrete crackles rather than a loop - torches pop
 * intermittently, and a looping fire bed would be both unreal and obnoxious to hold.
 */
public class HeldTorchBurnHandler {

    private static final ResourceLocation TORCH_BURN = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "held_torch_burn");
    private static final Set<Item> TORCH_ITEMS = Set.of(Items.TORCH, Items.SOUL_TORCH);
    // Chance per tick while a torch is held: 0.012 -> ~4s average interval. Note the chance
    // is per tick, so it accumulates to 24%/second; 0.03 (~1.7s) reads as a near-continuous
    // crackle because each clip is 1.8s. The held sound uses its own sound factory
    // (dsurround:held_torch_burn) so its volume is independent of the block-placed torch.
    private static final float TRIGGER_CHANCE = 0.012F;

    private final ISoundLibrary soundLibrary;
    private final IAudioPlayer audioPlayer;

    public HeldTorchBurnHandler(Configuration config, IModLog logger) {
        this.soundLibrary = ContainerManager.resolve(ISoundLibrary.class);
        this.audioPlayer = ContainerManager.resolve(IAudioPlayer.class);
        ClientState.TICK_END.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        if (!GameUtils.isInGame() || GameUtils.isPaused())
            return;
        final var player = GameUtils.getPlayer().orElse(null);
        if (player == null || !isHoldingTorch(player))
            return;

        if (Randomizer.current().nextFloat() < TRIGGER_CHANCE) {
            final var factory = this.soundLibrary.getSoundFactoryOrDefault(TORCH_BURN);
            this.audioPlayer.play(factory.attachToEntity(player));
        }
    }

    private static boolean isHoldingTorch(Player player) {
        return TORCH_ITEMS.contains(player.getMainHandItem().getItem())
                || TORCH_ITEMS.contains(player.getOffhandItem().getItem());
    }
}
