package com.jaram.be.notification.push;

import java.net.URI;

final class PushEndpoint {
    private PushEndpoint() {}

    static boolean supported(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.equals(uri.getRawAuthority())
                    && uri.getRawFragment() == null && endpoint.equals(uri.toASCIIString())
                    && uri.getRawPath().length() > 1
                    && (host.equals("fcm.googleapis.com") && (uri.getRawPath().startsWith("/fcm/send/") || uri.getRawPath().startsWith("/wp/"))
                        || host.equals("updates.push.services.mozilla.com") && uri.getRawPath().startsWith("/wpush/")
                        || host.endsWith(".push.apple.com")
                        || host.endsWith(".notify.windows.com"));
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }
}
