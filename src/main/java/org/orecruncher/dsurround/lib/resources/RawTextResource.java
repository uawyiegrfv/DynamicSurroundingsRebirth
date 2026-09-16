package org.orecruncher.dsurround.lib.resources;

/**
 * A resource file read as raw text. Used by the aggregate-file support, which needs to pull ONE
 * section out of a file before decoding anything: decoding the whole file with a section codec
 * would fail on every other key it contains.
 */
public record RawTextResource(String namespace, String path, String content) {
}
