package org.orecruncher.dsurround.lib.resources;

import com.mojang.serialization.Codec;

import java.util.Collection;

public interface IResourceFinder {

    <T> Collection<DiscoveredResource<T>> find(Codec<T> codec, String path);

    /**
     * Locates {@code path} and returns its files as raw text, without decoding them. Needed by the
     * per-namespace aggregate file support: that has to pull ONE section out of a file before any
     * codec sees it.
     *
     * @param path the resource path, with or without the .json suffix
     * @return one entry per file found; empty when nothing matches
     */
    Collection<RawTextResource> findRaw(String path);
}