package dev.souradip.surge.waitingroom;

import dev.souradip.surge.SurgeProperties;
import dev.souradip.surge.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Signed proof that a user was let out of the waiting room for one event.
 *
 * <p>The token names the event, the user and an expiry, and is signed with HMAC. Any instance
 * can check it without a lookup, a bot cannot mint one, and a token copied to another account
 * fails because the user id is part of what was signed.
 */
@Component
public class AdmissionTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final SurgeProperties.WaitingRoom config;
    private final Clock clock;

    @Autowired
    AdmissionTokens(SurgeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AdmissionTokens(SurgeProperties properties, Clock clock) {
        this.config = properties.waitingRoom();
        this.secret = config.secret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String issue(long eventId, String userId) {
        long expires = Instant.now(clock).plus(config.tokenTtl()).getEpochSecond();
        String payload = eventId + "." + userId + "." + expires;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + ENCODER.encodeToString(sign(payload));
    }

    public void verify(String token, long eventId, String userId) {
        if (token == null || token.isBlank()) {
            throw denied("an admission token from the waiting room is required");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw denied("malformed admission token");
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            signature = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw denied("malformed admission token");
        }
        // Constant time comparison, so response timing does not leak how much of a forged signature was right.
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw denied("admission token signature is invalid");
        }
        String[] fields = payload.split("\\.");
        if (fields.length != 3 || !fields[0].equals(String.valueOf(eventId)) || !fields[1].equals(userId)) {
            throw denied("admission token belongs to a different event or user");
        }
        if (Long.parseLong(fields[2]) < Instant.now(clock).getEpochSecond()) {
            throw denied("admission token has expired, rejoin the waiting room");
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException denied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "NOT_ADMITTED", message);
    }
}
