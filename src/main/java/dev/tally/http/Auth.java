package dev.tally.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.UnaryOperator;

/**
 * Bearer-token auth for the API, on every endpoint except /health. See ADR-0015.
 *
 * It reads only the Authorization header, so a 401 is decided before the Idempotency-Key is consulted,
 * which is what stops an unauthenticated retry from consuming or reserving a key. There is no 403: one
 * static token has no permission model to be forbidden by.
 */
public final class Auth {
    public enum Result { OK, MISSING, INVALID }

    static final String ENV_VAR = "TALLY_API_TOKEN";
    private static final int MIN_TOKEN_LENGTH = 16;

    private final byte[] expectedDigest;

    public Auth(String token) {
        this.expectedDigest = sha256(token);
    }

    public Result check(String authorization) {
        if (authorization == null) {
            return Result.MISSING;
        }
        int space = authorization.indexOf(' ');
        if (space < 0) {
            return Result.MISSING;
        }
        String scheme = authorization.substring(0, space);
        String token = authorization.substring(space + 1);
        if (!scheme.equalsIgnoreCase("Bearer") || token.isEmpty() || token.indexOf(' ') >= 0) {
            return Result.MISSING;
        }
        // Digesting both sides to a fixed 32 bytes keeps isEqual off its length-mismatch early return, so
        // the comparison costs the same whatever was presented. String.equals would leak the token one
        // byte at a time to anyone timing the response.
        return MessageDigest.isEqual(sha256(token), expectedDigest) ? Result.OK : Result.INVALID;
    }

    public ApiHandler protect(ApiHandler inner) {
        return request -> switch (check(request.headers().getFirst("Authorization"))) {
            case OK -> inner.handle(request);
            case MISSING -> Response.error(ErrorCode.AUTH_MISSING, "a bearer token is required")
                    .withHeader("WWW-Authenticate", "Bearer realm=\"tally\"");
            case INVALID -> Response.error(ErrorCode.AUTH_INVALID, "the bearer token is not valid")
                    .withHeader("WWW-Authenticate", "Bearer realm=\"tally\", error=\"invalid_token\"");
        };
    }

    // Fail closed: a missing, blank or too-short token stops startup rather than quietly serving an open
    // write API.
    public static String requireToken(UnaryOperator<String> getenv) {
        String token = getenv.apply(ENV_VAR);
        if (token == null || token.isBlank() || token.strip().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(ENV_VAR + " must be set to a token of at least "
                    + MIN_TOKEN_LENGTH + " characters");
        }
        return token;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
