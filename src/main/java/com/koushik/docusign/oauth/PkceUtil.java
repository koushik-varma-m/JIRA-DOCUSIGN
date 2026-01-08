package com.koushik.docusign.oauth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (Proof Key for Code Exchange) utility class.
 * 
 * Implements RFC 7636 for OAuth 2.0 Authorization Code flow with PKCE.
 * Used to enhance security for public clients by generating code verifiers
 * and code challenges.
 */
public class PkceUtil {

    /**
     * Default length for code verifier (43-128 characters recommended by RFC 7636).
     * 32 bytes = 43 characters when base64url encoded.
     */
    private static final int CODE_VERIFIER_LENGTH = 32;

    /**
     * Secure random generator for code verifier
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Base64 URL-safe alphabet (without padding)
     */
    private static final String BASE64URL_CHARS = 
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /**
     * Generates a cryptographically secure random code_verifier.
     * 
     * The code_verifier is a high-entropy cryptographic random string that is:
     * - 43 to 128 characters long
     * - Base64URL-safe (uses characters: A-Z, a-z, 0-9, -, _)
     * 
     * @return A random code_verifier string (43 characters by default)
     */
    public static String generateCodeVerifier() {
        return generateCodeVerifier(CODE_VERIFIER_LENGTH);
    }

    /**
     * Generates a cryptographically secure random code_verifier with custom length.
     * 
     * @param length Number of random bytes to generate (will be base64url encoded)
     * @return A random code_verifier string
     * @throws IllegalArgumentException if length is less than 32 or greater than 96
     */
    public static String generateCodeVerifier(int length) {
        if (length < 32 || length > 96) {
            throw new IllegalArgumentException(
                "Code verifier length must be between 32 and 96 bytes (RFC 7636)"
            );
        }

        byte[] randomBytes = new byte[length];
        SECURE_RANDOM.nextBytes(randomBytes);
        
        // Base64URL encode without padding
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Generates a code_challenge from a code_verifier using SHA-256.
     * 
     * The code_challenge is computed as:
     * BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))
     * 
     * @param codeVerifier The code_verifier string
     * @return The code_challenge string
     * @throws IllegalArgumentException if codeVerifier is null or empty
     * @throws RuntimeException if SHA-256 algorithm is not available (should never happen)
     */
    public static String generateCodeChallenge(String codeVerifier) {
        if (codeVerifier == null || codeVerifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Code verifier cannot be null or empty");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            
            // Base64URL encode without padding
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by Java spec, so this should never happen
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Generates both code_verifier and code_challenge as a pair.
     * 
     * @return A two-element array: [code_verifier, code_challenge]
     */
    public static String[] generateCodePair() {
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        return new String[]{codeVerifier, codeChallenge};
    }

    /**
     * Validates that a code_verifier matches its code_challenge.
     * 
     * This is useful for verifying the code_verifier during token exchange.
     * 
     * @param codeVerifier The code_verifier to validate
     * @param codeChallenge The expected code_challenge
     * @return true if the code_verifier matches the code_challenge
     */
    public static boolean validateCodeVerifier(String codeVerifier, String codeChallenge) {
        if (codeVerifier == null || codeChallenge == null) {
            return false;
        }
        
        String computedChallenge = generateCodeChallenge(codeVerifier);
        return computedChallenge.equals(codeChallenge);
    }
}




