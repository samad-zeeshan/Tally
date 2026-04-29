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
            // Resolve the root's own links once, so the per-request check below is real path against real
            // path. A root reached through a link would otherwise fail every comparison.
            real = this.root.toRealPath();
        } catch (IOException missingRoot) {
            real = this.root;
        }
        this.realRoot = real;
    }

    // Empty means not found, a non-GET, or a rejected traversal, and the kernel renders its 404 envelope.
    public Optional<Response> resolve(Request request) {
        if (!"GET".equals(request.method())) {
            return Optional.empty();
        }
        String path = request.path();
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Path resolved = root.resolve(relative).normalize();
        // Lexical, so it only rejects the obvious "/../" escape without touching the disk.
        if (!resolved.startsWith(root)) {
            return Optional.empty();
        }
        try {
            // The check that counts. A lexical comparison says nothing about links: one inside the root
            // points wherever it likes while the path still reads as being under the root, and
            // isRegularFile follows it. NOFOLLOW_LINKS would not close it either, because the escaping
            // hop can be a directory further up, so the whole path is resolved and re-checked instead.
            Path real = resolved.toRealPath();
            if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            return Optional.of(Response.raw(200, Files.readAllBytes(real), contentType(relative)));
        } catch (IOException missingOrUnreadable) {
            // Whether a path exists outside the root is not something a caller learns from a status code.
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
