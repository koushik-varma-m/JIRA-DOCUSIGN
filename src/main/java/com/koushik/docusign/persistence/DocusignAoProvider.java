package com.koushik.docusign.persistence;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Provides the plugin-scoped ActiveObjects instance associated with this plugin's {@code <ao>} module.
 *
 * AO must be injected (not looked up globally) so the AO framework can map calls to this plugin's schema.
 */
@Named
public class DocusignAoProvider {

    private static volatile ActiveObjects AO;

    @Inject
    public DocusignAoProvider(@ComponentImport ActiveObjects ao) {
        AO = ao;
    }

    public static ActiveObjects get() {
        return AO;
    }
}

