package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.enumeration.ChannelType;

import java.net.URI;

/**
 * What a channel target may show outside the database. An email address is the user's own and
 * shown as-is; a Slack or webhook URL is itself the credential (anyone holding it can post),
 * so only its scheme and host leave the server. Other targets keep their last four characters.
 */
public final class TargetMasker {

    private static final String HIDDEN = "••••";

    private TargetMasker() {
    }

    public static String mask(ChannelType type, String target) {
        if (target == null) {
            return null;
        }
        if (type == ChannelType.EMAIL) {
            return target;
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                URI uri = new URI(target);
                if (uri.getHost() != null) {
                    return uri.getScheme() + "://" + uri.getHost() + "/" + HIDDEN;
                }
            } catch (Exception ignored) {
                // fall through: never echo an unparseable secret
            }
            return HIDDEN;
        }
        return target.length() <= 4 ? HIDDEN : HIDDEN + target.substring(target.length() - 4);
    }
}
