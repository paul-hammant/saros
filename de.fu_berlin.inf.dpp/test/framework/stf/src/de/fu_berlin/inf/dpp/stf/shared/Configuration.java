package de.fu_berlin.inf.dpp.stf.shared;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * This class was auto-generated by Eclipse and is used to access the strings in
 * messages.properties.
 */
class Configuration {
    private static final String BUNDLE_NAME = "de.fu_berlin.inf.dpp.stf.shared.configuration"; //$NON-NLS-1$

    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle
        .getBundle(BUNDLE_NAME);

    private Configuration() {
        // do nothing
    }

    /**
     * Returns the value for the given key.
     * 
     * @param key
     * @return the value for the key as a String
     */
    public static String getString(String key) {
        try {
            return RESOURCE_BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }
}
