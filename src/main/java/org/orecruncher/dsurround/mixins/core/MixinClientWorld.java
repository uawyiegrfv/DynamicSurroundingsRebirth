package org.orecruncher.dsurround.mixins.core;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import org.orecruncher.dsurround.mixinutils.IClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Mixin(ClientLevel.class)
public class MixinClientWorld implements IClientWorld {

    @Final
    @Shadow
    private ClientChunkCache chunkSource;

    @Unique
    private long dsurround_worldseed;

    // 1.20.1: ClientLevel ctor keeps the Supplier<ProfilerFiller> param (26.1 dropped it).
    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILjava/util/function/Supplier;Lnet/minecraft/client/renderer/LevelRenderer;ZJ)V", at = @At("RETURN"))
    public void dsurround_ctor(ClientPacketListener clientPacketListener, ClientLevel.ClientLevelData clientLevelData, ResourceKey resourceKey, Holder holder, int i, int j, Supplier<ProfilerFiller> profilerFiller, LevelRenderer levelRenderer, boolean bl, long l, CallbackInfo ci) {
        this.dsurround_worldseed = l;
    }

    @Override
    public long dsurround_getWorldSeed() {
        return this.dsurround_worldseed;
    }

    @Unique
    public Stream<LevelChunk> dsurround_getLoadedChunks() {
        try {
            // 1.20.1: ClientChunkCache.storage is package-private (volatile) with a
            // package-private type ClientChunkCache$Storage, so @Accessor cannot target it.
            // Use reflection (same pattern as Conversion) and the string-target mixin
            // MixinClientChunkMap to read the AtomicReferenceArray of chunks.
            Field storageField = null;
            for (var f : ClientChunkCache.class.getDeclaredFields()) {
                if (f.getName().equals("storage") || f.getName().equals("f_104410_")) {
                    f.setAccessible(true);
                    storageField = f;
                    break;
                }
            }
            if (storageField == null)
                return Stream.empty();
            Object storage = storageField.get(this.chunkSource);
            var chunks = ((MixinClientChunkMap) storage).dsurround_getChunks();

            List<LevelChunk> result = new ArrayList<>();
            for (int i = 0; i < chunks.length(); i++) {
                var chunk = chunks.get(i);
                if (chunk != null)
                    result.add(chunk);
            }
            return result.stream();
        } catch (ReflectiveOperationException e) {
            return Stream.empty();
        }
    }
}
