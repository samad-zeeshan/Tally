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

    public StaticFileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
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
        // This traversal check is not optional in a money service.
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Response.raw(200, Files.readAllBytes(resolved), contentType(relative)));
        } catch (IOException unreadable) {
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
