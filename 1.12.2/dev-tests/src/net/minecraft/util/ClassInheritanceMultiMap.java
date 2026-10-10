package net.minecraft.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal stand-in for the real 1.12.2 ClassInheritanceMultiMap, which is what a chunk section actually is.
 *
 * add and remove were never obfuscated on the real class, so the transformer matches them by their plain
 * names - which is exactly why this stand-in reproduces those names rather than func_ names.
 */
public class ClassInheritanceMultiMap {

    private final Set<Object> entries = new HashSet<Object>();

    public boolean add(Object value) {
        return entries.add(value);
    }

    public boolean remove(Object value) {
        return entries.remove(value);
    }

    public boolean contains(Object value) {
        return entries.contains(value);
    }

    public int size() {
        return entries.size();
    }
}
