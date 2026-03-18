package dev.tally.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.UnaryOperator;

/**
 * Bearer-token auth for write endpoints. It reads only the Authorization header, so a rejected
 * request never touches the body: a 401 is decided before the Idempotency-Key is ever consulted,
 * which is what keeps an unauthenticated retry from consuming or reserving a key. See ADR-0015.
 *
 * Writes and reconciliation are protected; plain reads are open. Reads stay open as a deliberate
 * demo tradeoff so a browser or curl can look around with no setup; a real money API would protect
 * them too, and the ADR says so.
 */
public final class Auth {
    public enum Result { OK, MISSING, INVALID }

    static final String ENV_VAR = "TALLY_API_TOKEN";
    private static final int MIN_TOKEN_LENGTH = 16;

    private final byte[] expectedDigest;   // sha-256 of the configured token

    public Auth(String token) {
        this.expectedDigest = sha256(token);
    }

    // A missing or unparseable header is MISSING; a well-formed Bearer carrying the wrong token is INVALID.
    // The scheme is matched case-insensitively because RFC 9110 auth schemes are case-insensitive.
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
        // Digest both sides to a fixed 32 bytes so the comparison does the same work whatever the
        // presented length and MessageDigest.isEqual never takes its length-mismatch early return.
        // A plain String.equals would leak the secret one byte at a time to anyone timing the response.
        return MessageDigest.isEqual(sha256(token), expectedDigest) ? Result.OK : Result.INVALID;
    }

    // Wrap a handler so the token is checked from headers before the body is read. A 401 always carries
    // WWW-Authenticate (RFC 9110); 403 is never used because one static token has no permission model.
    public ApiHandler protect(ApiHandler inner) {
        return request -> switch (check(request.headers().getFirst("Authorization"))) {
            case OK -> inner.handle(request);
            case MISSING -> Response.error(ErrorCode.AUTH_MISSING, "a bearer token is required")
                    .withHeader("WWW-Authenticate", "Bearer realm=\"tally\"");
            case INVALID -> Response.error(ErrorCode.AUTH_INVALID, "the bearer token is not valid")
                    .withHeader("WWW-Authenticate", "Bearer realm=\"tally\", error=\"invalid_token\"");
        };
    }

    // The one place that reads the token env var. Fail closed: a missing, blank, or too-short token stops
    // startup rather than silently serving an open write API.
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
