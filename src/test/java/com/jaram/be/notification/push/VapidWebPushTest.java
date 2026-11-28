package com.jaram.be.notification.push;

import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.BigIntegers;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VapidWebPushTest {
    @Test
    void preparesVapidAuthenticatedEncryptedPost() throws Exception {
        VapidKeys server = keyPair();
        VapidKeys browser = keyPair();
        VapidWebPush transport = new VapidWebPush(server.publicKey(), server.privateKey(), "mailto:push@example.invalid");
        try {
            PushSubscriptions.Subscription subscription = new PushSubscriptions.Subscription(UUID.randomUUID(),
                    "https://fcm.googleapis.com/fcm/send/fixture", browser.publicKey(), encode(new byte[16]));
            byte[] payload = "{\"title\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);
            HttpRequest request = transport.prepare(subscription, payload);

            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri().getHost()).isEqualTo("fcm.googleapis.com");
            assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
            assertThat(request.headers().firstValue("Authorization").orElseThrow())
                    .startsWith("vapid t=").endsWith(", k=" + server.publicKey());
            assertThat(request.bodyPublisher().orElseThrow().contentLength()).isGreaterThan(payload.length);
            assertThatThrownBy(() -> transport.prepare(new PushSubscriptions.Subscription(UUID.randomUUID(),
                    "https://example.invalid/fcm/send/fixture", browser.publicKey(), encode(new byte[16])), payload))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            transport.close();
        }
    }

    static VapidKeys keyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", new BouncyCastleProvider());
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return new VapidKeys(encode(Utils.encode((ECPublicKey) pair.getPublic())),
                encode(BigIntegers.asUnsignedByteArray(32, ((ECPrivateKey) pair.getPrivate()).getD())));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record VapidKeys(String publicKey, String privateKey) {}
}
