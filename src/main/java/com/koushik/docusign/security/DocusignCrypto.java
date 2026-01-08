package com.koushik.docusign.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lightweight encryption helper for storing secrets (tokens, webhook keys) at rest.
 *
 * Storage format: enc:v1:&lt;base64(iv)&gt;:&lt;base64(ciphertext+tag)&gt;
 *
 * Key source precedence:
 *  1) DOCUSIGN_MASTER_KEY_B64 (32 bytes base64)
 *  2) file in Jira shared/home: &lt;jira.shared.home|jira.home&gt;/docusign-plugin/master.key
 */
public final class DocusignCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final String KEY_ENV = "DOCUSIGN_MASTER_KEY_B64";
    private static final String KEY_PROP = "DOCUSIGN_MASTER_KEY_B64";
    private static final int KEY_LEN = 32;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RAND = new SecureRandom();

    private static volatile byte[] cachedKey;

    private DocusignCrypto() {}

    public static boolean isEncryptedValue(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String encryptToString(String plaintext) {
        if (plaintext == null) return null;
        byte[] key = getOrCreateKey();
        if (key == null) {
            throw new IllegalStateException("Encryption key unavailable");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            RAND.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt secret: " + e.getMessage(), e);
        }
    }

    public static String decryptFromString(String value) {
        if (value == null) return null;
        if (!isEncryptedValue(value)) return value;
        byte[] key = getOrCreateKey();
        if (key == null) {
            throw new IllegalStateException("Encryption key unavailable");
        }
        try {
            String rest = value.substring(PREFIX.length());
            int sep = rest.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException("Invalid encrypted value format");
            }
            String ivB64 = rest.substring(0, sep);
            String ctB64 = rest.substring(sep + 1);
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] ct = Base64.getDecoder().decode(ctB64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt secret: " + e.getMessage(), e);
        }
    }

    private static byte[] getOrCreateKey() {
        byte[] existing = cachedKey;
        if (existing != null) return existing;
        synchronized (DocusignCrypto.class) {
            if (cachedKey != null) return cachedKey;
            byte[] key = readKeyFromEnvOrProp();
            if (key == null) {
                key = readOrCreateKeyFile();
            }
            cachedKey = key;
            return key;
        }
    }

    private static byte[] readKeyFromEnvOrProp() {
        String b64 = System.getProperty(KEY_PROP);
        if (b64 == null || b64.trim().isEmpty()) {
            b64 = System.getenv(KEY_ENV);
        }
        if (b64 == null || b64.trim().isEmpty()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64.trim());
        } catch (Exception e) {
            throw new IllegalStateException(KEY_ENV + " must be base64: " + e.getMessage(), e);
        }
        if (decoded.length != KEY_LEN) {
            throw new IllegalStateException(KEY_ENV + " must decode to " + KEY_LEN + " bytes");
        }
        return decoded;
    }

    private static byte[] readOrCreateKeyFile() {
        Path home = resolveJiraHomeDir();
        if (home == null) {
            throw new IllegalStateException("jira.home/jira.shared.home not set; set " + KEY_ENV + " for production");
        }
        Path dir = home.resolve("docusign-plugin");
        Path keyFile = dir.resolve("master.key");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create key directory: " + dir + " (" + e.getMessage() + ")", e);
        }
        try {
            if (Files.exists(keyFile)) {
                String b64 = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(b64);
                if (decoded.length != KEY_LEN) {
                    throw new IllegalStateException("Invalid key length in " + keyFile);
                }
                return decoded;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read key file: " + keyFile + " (" + e.getMessage() + ")", e);
        }

        byte[] key = new byte[KEY_LEN];
        RAND.nextBytes(key);
        String b64 = Base64.getEncoder().encodeToString(key);
        try {
            Files.write(keyFile, b64.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return key;
        } catch (FileAlreadyExistsException race) {
            try {
                String b64Existing = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(b64Existing);
                if (decoded.length != KEY_LEN) {
                    throw new IllegalStateException("Invalid key length in " + keyFile);
                }
                return decoded;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read key file after race: " + keyFile + " (" + e.getMessage() + ")", e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write key file: " + keyFile + " (" + e.getMessage() + ")", e);
        }
    }

    private static Path resolveJiraHomeDir() {
        String shared = System.getProperty("jira.shared.home");
        if (shared != null && !shared.trim().isEmpty()) {
            return Paths.get(shared.trim());
        }
        String home = System.getProperty("jira.home");
        if (home != null && !home.trim().isEmpty()) {
            return Paths.get(home.trim());
        }
        return null;
    }
}

