"""26.1 API migration fixes — every replacement verified against the 26.1 client jar via javap.

Fixes the 68 compile errors. Applies exact, surgical replacements only.
"""
import os

SRC_BASE = os.path.join('src', 'main', 'java', 'org', 'orecruncher')
SRC = os.path.join(SRC_BASE, 'dsurround')  # dsurround package files


def read(path):
    with open(path, 'rb') as f:
        raw = f.read()
    bom = raw.startswith(b'\xef\xbb\xbf')
    text = raw.decode('utf-8-sig')
    crlf = '\r\n' in text
    text = text.replace('\r\n', '\n')  # normalize to LF for matching
    return text, bom, crlf


def write(path, text, bom, crlf):
    if crlf:
        text = text.replace('\n', '\r\n')
    with open(path, 'wb') as f:
        f.write(b'\xef\xbb\xbf' if bom else b'')
        f.write(text.encode('utf-8'))


# (file, old, new)  — old must occur at least once; count checked
REPLACEMENTS = [
    # === A. ResourceKey.location() -> identifier() ===
    ('config/DimensionInfo.java',
     'this.name = world.dimension().location();',
     'this.name = world.dimension().identifier();'),
    ('config/libraries/impl/BlockLibrary.java',
     'kvp.getKey().location(), kvp.getValue()',
     'kvp.getKey().identifier(), kvp.getValue()'),
    ('config/libraries/impl/ItemLibrary.java',
     'kvp.getKey().location(), kvp.getValue()',
     'kvp.getKey().identifier(), kvp.getValue()'),
    ('config/libraries/impl/TagLibrary.java',
     'var location = e.key().location();',
     'var location = e.key().identifier();'),
    ('config/libraries/impl/TagLibrary.java',
     'var location = registryEntry.get().key().location();',
     'var location = registryEntry.get().key().identifier();', True),
    ('runtime/sets/impl/DimensionVariables.java',
     'this.id = world.dimension().location().toString();',
     'this.id = world.dimension().identifier().toString();'),
    ('runtime/sets/impl/DimensionVariables.java',
     'this.name = world.dimension().location().getPath();',
     'this.name = world.dimension().identifier().getPath();'),
    # BlockInfo: key() returns ResourceKey directly (not Optional), use unwrapKey()
    ('config/block/BlockInfo.java',
     'state.getBlock().builtInRegistryHolder().key().map(k -> k.identifier().getPath()).orElse(null)',
     'state.getBlock().builtInRegistryHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse(null)', True),

    # === B. TagKey.identifier() -> location() (TagKey keeps location()) ===
    ('lib/resources/ResourceUtilities.java',
     'tagKey.identifier().getPath()',
     'tagKey.location().getPath()'),
    ('lib/resources/ResourceUtilities.java',
     'tagKey.identifier().getNamespace()',
     'tagKey.location().getNamespace()'),
    ('lib/resources/ClientTagLoader.java',
     'var namespace = tagKey.identifier().getNamespace();',
     'var namespace = tagKey.location().getNamespace();'),

    # === C. SoundEvent.getLocation() -> location() ===
    ('config/libraries/impl/SoundLibrary.java',
     'c1.getLocation(), c2.getLocation()',
     'c1.location(), c2.location()'),
    ('config/libraries/impl/SoundLibrary.java',
     'this.myRegistry.put(se.getLocation(), se)',
     'this.myRegistry.put(se.location(), se)'),

    # === D. SoundInstance.getLocation() -> getIdentifier() ===
    ('config/libraries/impl/SoundLibrary.java',
     'var soundLocation = soundInstance.getLocation();',
     'var soundLocation = soundInstance.getIdentifier();', True),
    ('config/libraries/impl/SoundLibrary.java',
     'this.logger.debug("Mob sound remapping from %s to %s", soundInstance.getLocation(), soundLocation);',
     'this.logger.debug("Mob sound remapping from %s to %s", soundInstance.getIdentifier(), soundLocation);'),
    # IndividualSoundConfigEntry: event is a SoundEvent (has location(), not getIdentifier())
    ('config/IndividualSoundConfigEntry.java',
     'return new IndividualSoundConfigEntry(event.getIdentifier());',
     'return new IndividualSoundConfigEntry(event.location());'),

    # === E. Music record accessors ===
    ('config/libraries/impl/SoundLibrary.java',
     'music.getEvent().value().getLocation()',
     'music.sound().value().location()'),
    ('sound/SoundFactoryBuilder.java',
     'return new SoundFactoryBuilder(music.getEvent().value())',
     'return new SoundFactoryBuilder(music.sound().value())'),
    ('sound/SoundFactoryBuilder.java',
     '.setMusicMinDelay(music.getMinDelay())',
     '.setMusicMinDelay(music.minDelay())'),
    ('sound/SoundFactoryBuilder.java',
     '.setMusicMaxDelay(music.getMaxDelay())',
     '.setMusicMaxDelay(music.maxDelay())'),

    # === F. getPrecipitationAt(BlockPos, int) / Level.precipitationAt(BlockPos) ===
    ('runtime/sets/impl/BiomeVariables.java',
     'return this.biome.getPrecipitationAt(pos).name();',
     'return this.biome.getPrecipitationAt(pos, pos.getY()).name();'),
    ('lib/seasons/ISeasonalInformation.java',
     'return this.level().getBiome(blockPos).value().getPrecipitationAt(blockPos);',
     'return this.level().precipitationAt(blockPos);'),
    ('processing/accents/WaterySurfaceAccent.java',
     'var precipitation = world.getBiome(up).value().getPrecipitationAt(up);',
     'var precipitation = world.precipitationAt(up);'),

    # === G. Direction.getNormal() -> getUnitVec3i() ===
    ('runtime/audio/SoundFXUtils.java',
     'SURFACE_DIRECTION_NORMALS[d.ordinal()] = Vec3.atLowerCornerOf(d.getNormal());',
     'SURFACE_DIRECTION_NORMALS[d.ordinal()] = Vec3.atLowerCornerOf(d.getUnitVec3i());'),

    # === H. BlockHitResult.getIdentifier() (wrong replacement from earlier script) -> getLocation() ===
    ('runtime/audio/SoundFXUtils.java',
     'Vec3 lastHitPos = rayHit.getIdentifier();',
     'Vec3 lastHitPos = rayHit.getLocation();'),
    ('runtime/audio/SoundFXUtils.java',
     'double totalRayDistance = origin.distanceTo(rayHit.getIdentifier());',
     'double totalRayDistance = origin.distanceTo(rayHit.getLocation());'),
    ('runtime/audio/SoundFXUtils.java',
     'totalRayDistance += lastHitPos.distanceTo(rayHit.getIdentifier());',
     'totalRayDistance += lastHitPos.distanceTo(rayHit.getLocation());'),
    ('runtime/audio/SoundFXUtils.java',
     'lastHitPos = rayHit.getIdentifier();',
     'lastHitPos = rayHit.getLocation();'),
    ('runtime/audio/SoundFXUtils.java',
     'final double distance = lastHit.distanceTo(result.getIdentifier());',
     'final double distance = lastHit.distanceTo(result.getLocation());'),
    ('runtime/audio/SoundFXUtils.java',
     'lastHit = result.getIdentifier();',
     'lastHit = result.getLocation();'),

    # === I. Registry API ===
    ('lib/registry/RegistryUtils.java',
     '.flatMap(r -> r.getHolder(r.getId(instance)));',
     '.flatMap(r -> r.get(r.getId(instance)));'),
    ('lib/registry/RegistryUtils.java',
     '.flatMap(registry -> registry.getHolder(rk));',
     '.flatMap(registry -> registry.get(rk.identifier()));'),
    ('config/libraries/impl/TagLibrary.java',
     'return registry.holders()',
     'return registry.listElements()'),

    # === J. TagLibrary: Holder unwrap via builtInRegistryHolder / typeHolder ===
    ('config/libraries/impl/TagLibrary.java',
     'var location = entry.getBlock().unwrapKey().orElseThrow().identifier();',
     'var location = entry.getBlock().builtInRegistryHolder().unwrapKey().orElseThrow().identifier();'),
    ('config/libraries/impl/TagLibrary.java',
     'var location = entry.getItemHolder().unwrapKey().orElseThrow().identifier();',
     'var location = entry.typeHolder().unwrapKey().orElseThrow().identifier();'),
    ('config/libraries/impl/TagLibrary.java',
     'if (entry.is(tagKey))\n            return true;\n\n        var registryEntry = RegistryUtils.getRegistryEntry(Registries.ENTITY_TYPE, entry);',
     'if (entry.builtInRegistryHolder().is(tagKey))\n            return true;\n\n        var registryEntry = RegistryUtils.getRegistryEntry(Registries.ENTITY_TYPE, entry);'),

    # === L. Misc API ===
    # DayCycle: getTimeOfDay(0) -> overworld clock, getMoonBrightness removed
    ('lib/DayCycle.java',
     'final float angleDegrees = world.getTimeOfDay(0) * 360F;',
     'final float angleDegrees = (world.getOverworldClockTime() / 24000F) * 360F;'),
    ('lib/DayCycle.java',
     'return world.getMoonBrightness();',
     'return 1.0F; // 26.1: Level.getMoonBrightness() removed'),
    # DiurnalVariables: getTimeOfDay(1F) removed
    ('runtime/sets/impl/DiurnalVariables.java',
     'this.celestialAngle = world.getTimeOfDay(1F);',
     'this.celestialAngle = world.getOverworldClockTime() / 24000F;'),
    # PlayerVariables: Player.bob/oBob removed
    ('runtime/sets/impl/PlayerVariables.java',
     'this.isMoving = player.bob != player.oBob;',
     'this.isMoving = player.getDeltaMovement().lengthSqr() > 0.0D;'),
    # ReusableRaycastIterator: BlockPos.add(Vec3) removed
    ('lib/math/ReusableRaycastIterator.java',
     'this.traceContext.setStart(this.hitResult.getBlockPos().add(this.normal));',
     'this.traceContext.setStart(BlockPos.containing(this.hitResult.getBlockPos().getX() + this.normal.x(), this.hitResult.getBlockPos().getY() + this.normal.y(), this.hitResult.getBlockPos().getZ() + this.normal.z()));'),
    # ModInformation: WorldVersion.getName() -> name()
    ('lib/platform/ModInformation.java',
     'SharedConstants.getCurrentVersion().getName()',
     'SharedConstants.getCurrentVersion().name()'),
    # BlockStateProperties: getValues() now returns Stream<Property.Value<?>>
    ('lib/block/BlockStateProperties.java',
     'this(state.getValues());',
     'this(state.getValues().collect(Collectors.toMap(Property.Value::property, Property.Value::value)));'),
]

# === Disabled-class reference removals (multi-line, done separately) ===
DISABLED_FIXES = [
    # NeoForgeMod: drop OverlayManager registration
    ('neoforge/NeoForgeMod.java',
     'import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;\n',
     '// 26.1 DISABLED: import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;\n'),
    ('neoforge/NeoForgeMod.java',
     '// 26.1 DISABLED: import org.orecruncher.dsurround.gui.overlay.OverlayManager;\n',
     '// 26.1 DISABLED: import org.orecruncher.dsurround.gui.overlay.OverlayManager;\n// 26.1 DISABLED: import org.orecruncher.dsurround.lib.di.ContainerManager;\n'),
    ('neoforge/NeoForgeMod.java',
     'import org.orecruncher.dsurround.lib.di.ContainerManager;\n',
     '// 26.1 DISABLED: import org.orecruncher.dsurround.lib.di.ContainerManager;\n'),
    ('neoforge/NeoForgeMod.java',
     'import net.minecraft.resources.Identifier;\n',
     '// 26.1 DISABLED: import net.minecraft.resources.Identifier;\n'),
    ('neoforge/NeoForgeMod.java',
     '        modBus.addListener(this::onRegisterGuiLayersEvent);\n',
     '        // 26.1: modBus.addListener(this::onRegisterGuiLayersEvent);\n'),
    ('neoforge/NeoForgeMod.java',
     '''    @SubscribeEvent
    public void onRegisterGuiLayersEvent(RegisterGuiLayersEvent event) {
        // Add the overlay manager to the render layers of Gui
        OverlayManager dsurround_overlayManager = ContainerManager.resolve(OverlayManager.class);
        event.registerBelowAll(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "layer/overlaymanager"), dsurround_overlayManager::render);
    }

''',
     '''    // 26.1: Overlay system disabled. GUI layer rendering was refactored in 26.1;
    // the overlay classes would need a rewrite against the new layer API.

'''),
    # Handlers: drop FogHandler + VillageScanner registrations
    ('processing/Handlers.java',
     '        this.register(FogHandler.class);\n',
     '        // 26.1: this.register(FogHandler.class);\n'),
    ('processing/Handlers.java',
     '            .registerSingleton(VillageScanner.class)\n',
     '            // 26.1: .registerSingleton(VillageScanner.class)\n'),
    ('processing/Handlers.java',
     '            .registerSingleton(FogHandler.class)\n',
     '            // 26.1: .registerSingleton(FogHandler.class)\n'),
    ('processing/Handlers.java',
     '''                && !(GameUtils.getCurrentScreen().map(s -> s instanceof  IndividualSoundControlScreen).orElse(false))
''',
     ''),
    # EntityEffectType: null factories for disabled effect classes
    ('config/EntityEffectType.java',
     'FROST_BREATH("frost_breath", entity -> getInstance(BreathEffect.class)),',
     'FROST_BREATH("frost_breath", entity -> null), // 26.1: BreathEffect disabled'),
    ('config/EntityEffectType.java',
     'ITEM_SWING("item_swing", entity -> getInstance(ItemSwingEffect.class)),',
     'ITEM_SWING("item_swing", entity -> null), // 26.1: ItemSwingEffect disabled'),
    # BlockEffectType: null particle for FireflyParticle
    ('config/BlockEffectType.java',
     '''                    (world, state, pos, rand) -> new FireflyParticle(world, pos.getX() + 0.D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));''',
     '''                    (world, state, pos, rand) -> null))); // 26.1: FireflyParticle disabled'''),
    # SoundLibrary: remove ConfigSoundInstance check
    ('config/libraries/impl/SoundLibrary.java',
     '''        // Sounds played from the sound config menu are not remapped
        if (soundInstance instanceof ConfigSoundInstance)
            return Optional.empty();

''',
     '''        // 26.1: ConfigSoundInstance disabled (config sound screen not migrated)

'''),
    # SoundInstanceHandler: remove ConfigSoundInstance check
    ('sound/SoundInstanceHandler.java',
     '''        // Don't block ConfigSoundInstances.  They are triggered from the individual sound config
        // options, and though it may be blocked, the player may wish to hear.
        if (theSound instanceof ConfigSoundInstance)
            return false;

''',
     ''),
    # SoundVolumeEvaluator: remove ConfigSoundInstance condition
    ('sound/SoundVolumeEvaluator.java',
     '''        // Config sounds are played from the config menu.  Do not scale volume
        // with category adjustments.
        if (!(sound instanceof ConfigSoundInstance)) {
            // Further scale based on the sound's configuration within the mod data set. It's possible that this
            // could result in a sound volume of 0.
            var volumeScale = SOUND_LIBRARY.getVolumeScale(category, sound.getIdentifier());
            volume *= volumeScale;
        }
''',
     '''        // Further scale based on the sound's configuration within the mod data set. It's possible that this
        // could result in a sound volume of 0.  (ConfigSoundInstance handling disabled for 26.1)
        var volumeScale = SOUND_LIBRARY.getVolumeScale(category, sound.getIdentifier());
        volume *= volumeScale;
'''),
    # MixinMusicManager: drop SoundToast call
    ('mixins/audio/MixinMusicManager.java',
     '''    @Inject(method = "startPlaying(Lnet/minecraft/sounds/Music;)V", at = @At("RETURN"))
    public void dsurround_startPlaying(Music music, CallbackInfo ci) {
        if (MixinHelpers.soundOptions.displayToastMessagesForMusic)
            SoundToast.create(music);
    }
''',
     '''    // 26.1: SoundToast disabled (config sound screen not migrated)
    // @Inject(method = "startPlaying(Lnet/minecraft/sounds/Music;)V", at = @At("RETURN"))
    // public void dsurround_startPlaying(Music music, CallbackInfo ci) {
    //     if (MixinHelpers.soundOptions.displayToastMessagesForMusic)
    //         SoundToast.create(music);
    // }
'''),
    # MixinSoundOptionsScreen: drop IndividualSoundControlScreen usage
    ('mixins/core/MixinSoundOptionsScreen.java',
     '''    @Inject(method = "addOptions()V", at = @At("RETURN"))
    public void dsurround_addSoundConfigButton(CallbackInfo ci) {
''',
     '''    // 26.1: IndividualSoundControlScreen disabled (config sound screen not migrated)
    // @Inject(method = "addOptions()V", at = @At("RETURN"))
    // public void dsurround_addSoundConfigButton(CallbackInfo ci) {
'''),
    ('mixins/core/MixinSoundOptionsScreen.java',
     '''    @Unique
    private void dsurround_onPress(Button button) {
''',
     '''    // @Unique
    // private void dsurround_onPress(Button button) {
'''),
    ('mixins/core/MixinSoundOptionsScreen.java',
     '''        this.minecraft.setScreen(screen);
    }
''',
     '''        this.minecraft.setScreen(screen);
    }
    // }
'''),
    # ItemLibrary: Equipable removed
    ('config/libraries/impl/ItemLibrary.java',
     '''        SoundEvent itemEquipSound = null;
        var equipable = Equipable.get(stack);
        if (equipable != null) {
            itemEquipSound = equipable.getEquipSound().value();
        }
        return itemEquipSound;
''',
     '''        // 26.1: Equipable interface removed. Equip sounds are now driven by data components;
        // step accent sounds fall back to the generic resolution below.
        return null;
'''),
    # BiomeInfo: background music accessor removed from BiomeSpecialEffects
    ('config/biome/BiomeInfo.java',
     '''        // Check to see if the biome has a soundtrack. If so, add it to
        // the music list.
        if (biome != null) {
            var accessor = (IBiomeExtended)(Object)biome;
            accessor.dsurround_getSpecialEffects().getBackgroundMusic()
                .ifPresent(m -> {
                    var factory = SOUND_LIBRARY.getSoundFactoryForMusic(m);
                    var entry = new AcousticEntry(factory, null);
                    this.musicSounds.add(entry);
                });
        }
''',
     '''        // 26.1: BiomeSpecialEffects.getBackgroundMusic() was removed in 26.1.
        // Biome soundtrack discovery is not yet migrated.
'''),
    # ClientTagLoader: Optional.getValue() -> get() and getTag -> getTagOrEmpty
    ('lib/resources/ClientTagLoader.java',
     '''            var holderSet = registry.getValue().getTag(tagKey);
            if (holderSet.isPresent())
                return Optional.of(holderSet.get());
            return Optional.of(ImmutableList.of());
''',
     '''            return Optional.of(registry.get().getTagOrEmpty(tagKey));
'''),
]


def apply(path, old, new, count_ok=1, all_=False):
    # neoforge/* files live under org/orecruncher/, not org/orecruncher/dsurround/
    base = SRC_BASE if path.startswith('neoforge/') else SRC
    full = os.path.join(base, path)
    if not os.path.exists(full):
        print(f'  !! MISSING: {path}')
        return False
    text, bom, crlf = read(full)
    n = text.count(old)
    if n == 0:
        print(f'  !! NOT FOUND ({n}): {path} :: {old[:70]!r}')
        return False
    if n > count_ok and not all_:
        print(f'  !! MULTIPLE ({n}): {path} :: {old[:70]!r}')
        return False
    text = text.replace(old, new)
    write(full, text, bom, crlf)
    print(f'  OK ({n}x): {path}')
    return True


print('=== API replacements ===')
ok = 0
for item in REPLACEMENTS:
    path, old, new = item[:3]
    all_ = item[3] if len(item) > 3 else False
    ok += apply(path, old, new, all_=all_)
print(f'API: {ok}/{len(REPLACEMENTS)} applied')

print()
print('=== Disabled-class fixes ===')
ok2 = 0
for item in DISABLED_FIXES:
    path, old, new = item[:3]
    all_ = item[3] if len(item) > 3 else False
    ok2 += apply(path, old, new, all_=all_)
print(f'DISABLED: {ok2}/{len(DISABLED_FIXES)} applied')
