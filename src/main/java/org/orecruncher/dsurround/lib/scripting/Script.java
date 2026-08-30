package org.orecruncher.dsurround.lib.scripting;

import com.mojang.serialization.Codec;
import org.jetbrains.annotations.Nullable;

import javax.script.CompiledScript;
import java.util.Optional;

public class Script {

    public static final Codec<Script> CODEC = Codec.STRING.xmap(Script::new, (script) -> script.script);

    /**
     * Default script that always returns true.
     */
    public static final Script TRUE = new Script("true");

    private final String script;
    private CompiledScript compiledScript;

    public Script(String script) {
        this.script = script;
    }

    /**
     * Retrieves the result of a previous compilation if present.
     * @return Compiled script, if any.
     */
    Optional<CompiledScript> getCompiledScript() {
        return Optional.ofNullable(this.compiledScript);
    }

    /**
     * Sets the state of the script with the result of a previous compilation.
     * @param compiled Compiled script to cache
     */
    void setCompiledScript(@Nullable CompiledScript compiled) {
        this.compiledScript = compiled;
    }

    /**
     * Obtains the string version of the script for compilation
     * @return The script to be compiled.
     */
    public String asString() {
        return this.script;
    }

    /**
     * Fast path for scripts that are plain numeric or boolean literals (e.g. block
     * soundChance = "0.05" or the default conditions "true"). These are evaluated very
     * frequently (every block sample / acoustic hit) and never need the JavaScript engine -
     * the Nashorn interpretation of a bare literal is a measurable fixed cost. Returns
     * empty when the script is a real expression.
     */
    Optional<Object> getConstant() {
        final String s = this.script.trim();
        if (s.equals("true"))
            return Optional.of(Boolean.TRUE);
        if (s.equals("false"))
            return Optional.of(Boolean.FALSE);
        try {
            return Optional.of(Double.valueOf(s));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return this.script;
    }

    @Override
    public int hashCode() {
        return this.script.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Script s) {
            return s.script.equals(this.script);
        }
        return false;
    }
}
