package com.koushik.docusign.config;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

/**
 * Central configuration reader.
 *
 * Precedence: Jira global plugin settings -> JVM system properties -> environment variables -> default.
 */
public final class DocusignConfig {

    private static final String PLUGIN_KEY = "com.koushik.docusign.jira-docusign-plugin";
    private static final String PREFIX = PLUGIN_KEY + ".";

    private DocusignConfig() {}

    public static String getString(String key, String def) {
        String v = readFromPluginSettings(key);
        if (isPresent(v)) return v.trim();

        v = System.getProperty(key);
        if (isPresent(v)) return v.trim();

        v = System.getenv(key);
        if (isPresent(v)) return v.trim();

        return def;
    }

    public static String getSecretString(String key, String def) {
        String v = readFromPluginSettings(key);
        if (isPresent(v)) {
            return decryptIfNeeded(v.trim());
        }

        v = System.getProperty(key);
        if (isPresent(v)) return decryptIfNeeded(v.trim());

        v = System.getenv(key);
        if (isPresent(v)) return decryptIfNeeded(v.trim());

        return def;
    }

    public static String getRequiredString(String key) {
        String v = getString(key, null);
        if (!isPresent(v)) {
            throw new IllegalStateException(key + " is required (set as plugin setting or environment variable)");
        }
        return v.trim();
    }

    public static String getRequiredSecretString(String key) {
        String v = getSecretString(key, null);
        if (!isPresent(v)) {
            throw new IllegalStateException(key + " is required (set as plugin setting or environment variable)");
        }
        return v.trim();
    }

    public static void setGlobalString(String key, String value) {
        PluginSettings settings = getGlobalSettings();
        if (settings == null) {
            throw new IllegalStateException("PluginSettingsFactory not available");
        }
        String storeKey = PREFIX + key;
        if (value == null) {
            settings.remove(storeKey);
        } else {
            settings.put(storeKey, value);
        }
    }

    public static void setGlobalSecretString(String key, String value) {
        PluginSettings settings = getGlobalSettings();
        if (settings == null) {
            throw new IllegalStateException("PluginSettingsFactory not available");
        }
        String storeKey = PREFIX + key;
        if (value == null) {
            settings.remove(storeKey);
        } else {
            settings.put(storeKey, encryptIfNeeded(value));
        }
    }

    private static String readFromPluginSettings(String key) {
        try {
            PluginSettings settings = getGlobalSettings();
            if (settings == null) return null;
            Object v = settings.get(PREFIX + key);
            return v != null ? String.valueOf(v) : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static PluginSettings getGlobalSettings() {
        try {
            PluginSettingsFactory factory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
            if (factory == null) return null;
            return factory.createGlobalSettings();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String decryptIfNeeded(String value) {
        try {
            if (com.koushik.docusign.security.DocusignCrypto.isEncryptedValue(value)) {
                return com.koushik.docusign.security.DocusignCrypto.decryptFromString(value);
            }
        } catch (Exception ignore) {
            // If decryption fails, treat as missing to force reconfiguration.
            return null;
        }
        return value;
    }

    private static String encryptIfNeeded(String plaintext) {
        try {
            if (plaintext == null) return null;
            String trimmed = plaintext.trim();
            if (trimmed.isEmpty()) return "";
            if (com.koushik.docusign.security.DocusignCrypto.isEncryptedValue(trimmed)) {
                return trimmed;
            }
            return com.koushik.docusign.security.DocusignCrypto.encryptToString(trimmed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret for plugin settings: " + e.getMessage(), e);
        }
    }

    private static boolean isPresent(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
