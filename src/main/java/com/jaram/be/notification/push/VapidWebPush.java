package com.jaram.be.notification.push;

import com.jaram.be.common.ApiException;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.lang.JoseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Duration;
import java.util.Base64;

@Component
class VapidWebPush {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int TTL_SECONDS = 300;

    private final String publicKey;
    private final RequestEncoder encoder;
    private final HttpClient client;

    VapidWebPush(@Value("${jaram.notifications.push.vapid-public-key:}") String publicKey,
                 @Value("${jaram.notifications.push.vapid-private-key:}") String privateKey,
                 @Value("${jaram.notifications.push.vapid-subject:}") String subject) {
        this.publicKey = publicKey;
        if (publicKey.isBlank() && privateKey.isBlank() && subject.isBlank()) {
            encoder = null;
            client = null;
            return;
        }
        if (!validPublicKey(publicKey) || !privateKey.matches("[A-Za-z0-9_-]{43}")
                || !(subject.matches("mailto:[^\\s@]+@[^\\s@]+") || validHttpsSubject(subject))) {
            throw new IllegalStateException("Invalid Web Push VAPID configuration");
        }

        Security.addProvider(new BouncyCastleProvider());
        try {
            encoder = new RequestEncoder(publicKey, privateKey, subject);
            if (!Utils.verifyKeyPair(encoder.getPrivateKey(), encoder.getPublicKey())) {
                throw new IllegalStateException("Web Push VAPID keys do not match");
            }
        } catch (GeneralSecurityException | IllegalArgumentException invalidKey) {
            throw new IllegalStateException("Invalid Web Push VAPID keys");
        }
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    void close() {
        if (client != null) client.shutdownNow();
    }

    boolean configured() {
        return encoder != null;
    }

    String publicKey() {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER", "웹 푸시 설정을 사용할 수 없습니다.");
        }
        return publicKey;
    }

    int send(PushSubscriptions.Subscription subscription, byte[] payload)
            throws IOException, InterruptedException, GeneralSecurityException, JoseException {
        HttpResponse<InputStream> response = client.send(prepare(subscription, payload),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return response.statusCode();
        }
    }

    HttpRequest prepare(PushSubscriptions.Subscription subscription, byte[] payload)
            throws GeneralSecurityException, IOException, JoseException {
        if (!PushEndpoint.supported(subscription.endpoint())) {
            throw new IllegalArgumentException("Unsupported Web Push endpoint");
        }
        Notification notification = new Notification(subscription.endpoint(), subscription.p256dh(),
                subscription.auth(), payload, TTL_SECONDS);
        nl.martijndwars.webpush.HttpRequest encoded = encoder.encode(notification);
        if (!PushEndpoint.supported(encoded.getUrl())) {
            throw new IllegalArgumentException("Unsupported Web Push destination");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(encoded.getUrl()))
                .timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.ofByteArray(encoded.getBody()));
        encoded.getHeaders().forEach(request::header);
        return request.build();
    }

    static boolean validPublicKey(String key) {
        try {
            if (!key.matches("[A-Za-z0-9_-]{87}")) return false;
            byte[] bytes = Base64.getUrlDecoder().decode(key);
            return bytes[0] == 4 && ECNamedCurveTable.getParameterSpec("prime256v1").getCurve()
                    .decodePoint(bytes).isValid();
        } catch (IllegalArgumentException invalidKey) {
            return false;
        }
    }

    private static boolean validHttpsSubject(String subject) {
        try {
            URI uri = URI.create(subject);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }

    private static final class RequestEncoder extends AbstractPushService<RequestEncoder> {
        RequestEncoder(String publicKey, String privateKey, String subject) throws GeneralSecurityException {
            super(publicKey, privateKey, subject);
        }

        nl.martijndwars.webpush.HttpRequest encode(Notification notification)
                throws GeneralSecurityException, IOException, JoseException {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
