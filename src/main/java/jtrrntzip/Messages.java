package jtrrntzip;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Access to the localized user messages of the program.
 *
 * <p>The messages live in the {@code jtrrntzip.messages} resource bundle.
 * {@code messages.properties} holds the default (English) texts and sibling
 * files such as {@code messages_fr.properties} provide translations. Unknown
 * keys are reported as a {@code !key!} marker instead of raising an error, so
 * a missing translation can never break processing.</p>
 */
public final class Messages {
    private static final String BUNDLE_NAME = "jtrrntzip.messages"; //$NON-NLS-1$

    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(BUNDLE_NAME);

    private Messages() {
        /* Prevent instantiation */
    }

    /**
     * Returns the localized message registered under the given key.
     *
     * @param key
     *            the message key of the bundle entry
     * @return the localized message, or a {@code !key!} marker when the key
     *         is unknown to the bundle
     */
    public static String getString(String key) {
        try {
            return RESOURCE_BUNDLE.getString(key);
        } catch (MissingResourceException _) {
            return '!' + key + '!';
        }
    }
}
