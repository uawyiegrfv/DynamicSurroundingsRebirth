package org.orecruncher.dsurround.mixins.core;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public interface MixinClientChunkMap {

    @Accessor("chunks")
    AtomicReferenceArray<LevelChunk> dsurround_getChunks();
}
