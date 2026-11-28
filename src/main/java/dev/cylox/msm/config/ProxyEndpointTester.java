package dev.cylox.msm.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Checks whether a configured proxy endpoint accepts TCP connections (not a full tunnel test). */
public final class ProxyEndpointTester {
    private ProxyEndpointTester() {
    }

    public static void testEndpoint(ProxyProfile profile, int timeoutMillis) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Proxy profile is required.");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(profile.getHost(), profile.getPort()),
                    Math.max(500, Math.min(timeoutMillis, 15000)));
        }
    }
}
