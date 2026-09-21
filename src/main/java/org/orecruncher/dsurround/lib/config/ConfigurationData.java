package org.orecruncher.dsurround.lib.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.lib.events.EventingFactory;
import org.orecruncher.dsurround.lib.events.IEvent;
import org.orecruncher.dsurround.lib.platform.ModInformation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public abstract class ConfigurationData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Class<? extends ConfigurationData>, Collection<ConfigElement<?>>> SPECIFICATIONS = new IdentityHashMap<>();
    private static final Map<Class<? extends ConfigurationData>, ConfigurationData> CONFIGS = new IdentityHashMap<>();

    transient final Path configFilePath;

    protected ConfigurationData() {
        this.configFilePath = computePath(this.getClass());
    }

    @SuppressWarnings("unchecked")
    public static <T extends ConfigurationData> @NotNull T getConfig(Class<T> clazz) {
        return (T) Objects.requireNonNull(CONFIGS.computeIfAbsent(clazz, ConfigurationData::computeConfiguration));
    }

    @Nullable
    public static <T extends ConfigurationData> Collection<ConfigElement<?>> getSpecification(Class<T> clazz) {
        var spec = SPECIFICATIONS.get(clazz);
        if (spec == null) {
            var specResult = ConfigProcessor.generateAccessors(clazz);
            if (specResult.isPresent()) {
                spec = specResult.get();
                SPECIFICATIONS.put(clazz, spec);
            }
        }
        return spec;
    }

    private static Path computePath(Class<?> clazz) {
        var configFolderAnnotation = clazz.getAnnotation(ConfigPlacement.class);
        if (configFolderAnnotation == null)
            throw new RuntimeException("Configuration class must have a ConfigFolder annotation");
        return ModInformation.getConfigPath(configFolderAnnotation.folderName())
                .resolve(configFolderAnnotation.fileName() + ".json");
    }

    private static <T extends ConfigurationData> T computeConfiguration(Class<T> clazz) {
        try {
            // Construct the path to the configuration folder
            var configFolderPath = computePath(clazz);

            // We need to construct a new instance to capture the specification. Once that is done, we can load
            // from disk if present.
            var ignored = getSpecification(clazz);

            // Check to see if it exists on the disk, and if so, load it up. Otherwise, save it so the defaults are
            // persisted and the user can edit manually.
            T config = ConfigProcessor.createPrototype(clazz).orElseThrow();

            // Whether the file on disk may be overwritten at the end. A file that could not be READ
            // must not be replaced: it is the user's only copy of their settings, and it is very likely
            // the thing they need to look at to see what they got wrong.
            //
            // Without this the user's file was destroyed on any parse failure. `config` still held the
            // freshly built prototype, so the save() below wrote the complete default set over a file
            // that had, say, one trailing comma in it - and the only trace was one error line that
            // printed the exception's toString and never mentioned the file or that it had been
            // replaced.
            boolean mayOverwrite = true;
            try {
                if (Files.exists(configFolderPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(configFolderPath)) {
                        final T loaded = GSON.fromJson(reader, clazz);
                        if (loaded != null) {
                            config = loaded;
                        } else {
                            // The file contained a JSON null, or nothing. Do not replace it.
                            mayOverwrite = false;
                        }
                    }
                }
            } catch (Throwable t) {
                mayOverwrite = false;
                Library.LOGGER.error(t, "Unable to read %s - the file is left UNTOUCHED so you can "
                        + "inspect it. Running with defaults for this session.", configFolderPath);
            }

            if (config == null) {
                var ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                config = ctor.newInstance();
            }

            // Post-load processing
            config.postLoad();

            // Repair anything the file left null. Gson accepts an unknown enum name as null and
            // {"section": null} as a null section; both then crash a consumer, and the enum case also
            // silently drops the key on the next save. See sanitize().
            final int repaired = config.sanitize();
            if (repaired > 0) {
                Library.LOGGER.warn("Repaired %d invalid value(s) in %s - an unknown enum name or a null "
                        + "section was replaced with the default. Check the file for a typo.",
                        repaired, configFolderPath);
            }

            // Save it out.  Config parameters may have been added, removed, clamped, etc. - but never
            // over a file that could not be read.
            if (mayOverwrite)
                config.save();

            return config;
        } catch (Throwable t) {
            Library.LOGGER.error(t, "Unable to handle configuration");
        }

        return null;
    }

    public Collection<ConfigElement<?>> getSpecification() {
        return SPECIFICATIONS.get(this.getClass());
    }

    /**
     * Saves the state of the config to disk
     */
    public void save() {
        try {
            Files.createDirectories(this.configFilePath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(this.configFilePath)) {
                GSON.toJson(this, writer);
            }
        } catch (Throwable t) {
            Library.LOGGER.error(t, "Unable to save configuration %s", t.getMessage());
        } finally {
            CONFIG_CHANGED.raise().onChange(this);
        }
    }

    /**
     * Hook to provide processing after the configuration is loaded from the disk
     */
    public void postLoad() {
    }

    /**
     * Replaces null enum values with the enum's first constant, and null sections with a fresh instance.
     *
     * <p>Gson does NOT reject an unknown enum constant: a typo such as {@code "PIXELATED_CIRCL"} loads
     * as null with no exception at all, and the key is then dropped from the user's file on the next
     * save - so the mistake is silent AND the setting is lost. The null then reaches consumers that
     * assume a value: the compass overlay's constructor calls {@code compassStyle.getSpriteNumber()},
     * the water-ripple gate compares {@code waterRippleStyle != NONE} (true for null, so it builds a
     * particle that dereferences it), and the footprint particle calls {@code style.ordinal()}. Each
     * of those is a crash from a one-character typo in a hand-edited file.
     *
     * <p>A null SECTION is worse: {@code {"logging": null}} makes Gson write null into that final
     * field, and the mod's own constructor then dereferences it during mod loading - a startup crash
     * whose message says nothing about the config file.
     *
     * <p>Done reflectively over the whole tree so a newly added enum or section is covered
     * automatically, rather than relying on every consumer to remember a null check.
     *
     * @return the number of values repaired, for the caller to report
     */
    public int sanitize() {
        int repaired = 0;
        for (final var field : this.getClass().getFields()) {
            try {
                final Object value = field.get(this);

                if (value == null) {
                    if (field.getType().isEnum()) {
                        final Object[] constants = field.getType().getEnumConstants();
                        if (constants != null && constants.length > 0) {
                            // The section fields are FINAL, and Field.set on a final field throws
                            // IllegalAccessException without this. Verified: without it the null
                            // section survived and the startup crash remained.
                            field.setAccessible(true);
                            field.set(this, constants[0]);
                            repaired++;
                        }
                    } else if (!field.getType().isPrimitive()
                            && ConfigurationData.class.isAssignableFrom(field.getType())) {
                        // A nested config section was nulled by the file. Gson writes these fields
                        // even though they are final, which is why they can be null at all.
                        final var ctor = field.getType().getDeclaredConstructor();
                        ctor.setAccessible(true);
                        field.setAccessible(true);
                        field.set(this, ctor.newInstance());
                        repaired++;
                    }
                    continue;
                }

                // Recurse into nested sections so their enums are covered too.
                if (value instanceof ConfigurationData nested && value != this)
                    repaired += nested.sanitize();
            } catch (final Throwable ignored) {
                // A field that cannot be repaired is left alone; the alternative is refusing to start.
            }
        }
        return repaired;
    }

    /**
     * Defines the folder within the config directory that option state will be saved. All configuration
     * instances need this annotation.
     */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ConfigPlacement {
        String folderName();
        String fileName();
    }

    /**
     * Defines the root of language translation keys
     */
    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface TranslationRoot {
        String value() default Constants.MOD_ID;
    }

    /**
     * Indicates the field is a property
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Property {
        /**
         * The key segment for formulating a lookup key to generate language resource ids.
         */
        String value() default "";
    }

    /**
     * Value range of an Integer
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface IntegerRange {
        int min();

        int max() default Integer.MAX_VALUE;
    }

    /**
     * Value range of a Double
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DoubleRange {
        double min();

        double max() default Double.MAX_VALUE;
    }

    /**
     * Changing the value of this property will require a restart for it to have an effect.
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface RestartRequired {
        boolean client() default true;
    }

    /**
     * Changing the value of this property will require the assets to be reloaded to have an effect.
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AssetReloadRequired {
    }

    /**
     * Changing the value of this property will require the world to be reloaded to have an effect.
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface WorldReloadRequired {
    }

    /**
     * Comment associated with a property, if any. This is used if a translation is not available. Depending on
     * config file format, the comment may be persisted with the data as well.
     */
    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Comment {
        String value();
    }

    /**
     * Indicates the preference for a slider in GUI when modifying the integer property
     */
    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Slider {

    }

    /**
     * Indicates the property will not show in the GUI
     */
    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Hidden {

    }

    /**
     * The class of the Enum in question. Thanks type erasure.
     */
    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface EnumType {
        Class<? extends Enum<?>> value();
    }

    public static final IEvent<IConfigChangedEvent> CONFIG_CHANGED = EventingFactory.createEvent(callbacks -> config -> {
        for (var callback : callbacks) {
            callback.onChange(config);
        }
    });

    @FunctionalInterface
    public interface IConfigChangedEvent {
        void onChange(ConfigurationData config);
    }
}