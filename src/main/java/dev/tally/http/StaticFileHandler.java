package dev.tally.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Serves the built web client from a directory, so the whole app can live at one origin. It is the
 * kernel's fallback for a path the router does not own, not a competing "/" context.
 */
public final class StaticFileHandler {
    private final Path root;
    private final Path realRoot;

    public StaticFileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
        Path real;
        try {
            // Resolve the root's own links once, so the per-request comparison is real path against real
            // path. A root reached through a link would otherwise fail every comparison below.
            real = this.root.toRealPath();
        } catch (IOException missingRoot) {
            real = this.root;   // a root that does not exist serves nothing, which every resolve below already does
        }
        this.realRoot = real;
    }

    // Present means the file's bytes plus a content type. Empty means not found, a non-GET, or a
    // rejected traversal, and the kernel renders its standard 404 envelope.
    public Optional<Response> resolve(Request request) {
        if (!"GET".equals(request.method())) {
            return Optional.empty();
        }
        String path = request.path();
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Path resolved = root.resolve(relative).normalize();
        // The normalized path must stay under the root, or a "/../" escape could read arbitrary files.
        // This traversal check is not optional in a money service. It is lexical, though, so it runs
        // first only to reject the obvious case without touching the disk.
        if (!resolved.startsWith(root)) {
            return Optional.empty();
        }
        try {
            // The check that counts is on the real path. A lexical comparison says nothing about symlinks:
            // a link inside the root, or a linked parent directory, points wherever it likes while the
            // path still reads as being under the root, and Files.isRegularFile follows it happily.
            // NOFOLLOW_LINKS on the final component would not close it either, since the escaping hop can
            // be a directory further up. Resolving the whole path and re-checking is what actually holds.
            Path real = resolved.toRealPath();
            if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            return Optional.of(Response.raw(200, Files.readAllBytes(real), contentType(relative)));
        } catch (IOException missingOrUnreadable) {
            // Missing files land here too, and stay a plain 404: whether a path exists outside the root
            // is not something a caller gets to learn from the status code.
            return Optional.empty();
        }
    }

    private static String contentType(String relative) {
        int dot = relative.lastIndexOf('.');
        String ext = dot < 0 ? "" : relative.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
