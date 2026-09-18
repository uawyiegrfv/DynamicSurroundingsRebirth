package org.orecruncher.dsurround.mixins;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Vetoes the one mixin that would otherwise take the launch down when Sound Physics
 * Remastered is installed.
 *
 * <p>Background: our {@code MixinSoundLibraryContext} redirects
 * {@code ALC10.alcCreateContext(JLjava/nio/IntBuffer;)J} inside {@code Library.init}.
 * SPR's own {@code LibraryMixin} redirects the same instruction, and {@code @Redirect}
 * is exclusive - so one of the two is guaranteed to fail its injection check, and with
 * {@code "required": true} plus {@code defaultRequire: 1} that failure kills the client
 * during startup. Giving our redirect {@code require = 0} does NOT help: we still
 * consumed the instruction, so SPR was the one that failed. The only answer is to not
 * apply our mixin at all when the other mod is around - which costs nothing, because
 * {@code AudioUtilities.autoDisabledBecauseOf} already switches our enhanced audio off
 * when SPR is present.
 *
 * <p>Loaded very early (during mixin config setup), so it deliberately touches no
 * Minecraft classes at class-initialisation time and depends on no loader API: mod
 * presence is decided by probing the classpath for SPR's own classes and mixin configs,
 * read lazily at transform time and cached.
 */
public final class DSurroundMixinPlugin implements IMixinConfigPlugin {

    /** The one mixin that must stand down when another mod owns the instruction. */
    private static final String MIXIN_SOUND_LIBRARY_CONTEXT =
            "org.orecruncher.dsurround.mixins.audio.MixinSoundLibraryContext";

    /**
     * Anything that only Sound Physics Remastered ships. Several markers because the
     * class layout and mixin config location moved between SPR versions (1.1.1 shipped
     * its config under {@code assets/sound_physics_remastered/}, 1.5.1 at the jar root).
     */
    private static final String[] SOUND_PHYSICS_CLASSES = {
            "com.sonicether.soundphysics.SoundPhysics",
            "com.sonicether.soundphysics.ForgeSoundPhysicsMod",
    };
    private static final String[] SOUND_PHYSICS_RESOURCES = {
            "sound_physics_remastered.mixins.json",
            "assets/sound_physics_remastered/sound_physics_remastered.mixins.json",
    };

    private static volatile Boolean soundPhysicsPresent;

    @Override
    public void onLoad(String mixinPackage) {
        // nothing to prepare
    }

    @Override
    public String getRefMapperConfig() {
        return null;                                  // use the default refmap
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (MIXIN_SOUND_LIBRARY_CONTEXT.equals(mixinClassName) && soundPhysicsPresent()) {
            // Deliberately quiet at WARN: AudioUtilities already logs the auto-disable, and a
            // second warning about the same fact would be noise. println because this runs
            // before the mod's logging service is reliably usable.
            System.out.println("[dsurround] Sound Physics Remastered detected: not claiming the "
                    + "alcCreateContext instruction (it owns it); aux sends come from its context.");
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // nothing to do
    }

    @Override
    public List<String> getMixins() {
        return null;                                  // no extra mixins supplied by the plugin
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // nothing to do
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        // nothing to do
    }

    private static boolean soundPhysicsPresent() {
        Boolean cached = soundPhysicsPresent;
        if (cached != null)
            return cached;
        boolean present = probe();
        soundPhysicsPresent = present;
        return present;
    }

    private static boolean probe() {
        // The plugin may be instantiated by Mixin's own class provider rather than by the
        // mod class loader, so do not trust a single loader: try ours, the thread context
        // loader and the system loader, and treat any hit as "present".
        ClassLoader[] loaders = {
                DSurroundMixinPlugin.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader loader : loaders) {
            if (loader == null)
                continue;
            for (String resource : SOUND_PHYSICS_RESOURCES) {
                try {
                    if (loader.getResource(resource) != null)
                        return true;
                } catch (Throwable ignored) {
                    // try the next probe
                }
            }
            for (String className : SOUND_PHYSICS_CLASSES) {
                try {
                    Class.forName(className, false, loader);
                    return true;
                } catch (ClassNotFoundException absent) {
                    // try the next probe
                } catch (Throwable other) {
                    // Present but not loadable (e.g. a linkage problem): assume present, so that we
                    // are the one that stands down rather than the one that causes a launch failure.
                    return true;
                }
            }
        }
        // Last resort: ask the loader API by reflection, so the file stays identical across the
        // three repo copies (Forge and NeoForge put ModList in different packages).
        for (String modListName : new String[]{"net.minecraftforge.fml.ModList",
                                               "net.neoforged.fml.ModList"}) {
            try {
                Class<?> modList = Class.forName(modListName, false, DSurroundMixinPlugin.class.getClassLoader());
                Object instance = modList.getMethod("get").invoke(null);
                Object loaded = modList.getMethod("isLoaded", String.class)
                        .invoke(instance, "sound_physics_remastered");
                if (Boolean.TRUE.equals(loaded))
                    return true;
            } catch (Throwable ignored) {
                // this loader API is not the one in use
            }
        }
        return false;
    }
}
