package dev.cylox.msm.network;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NetworkInterfaceAddress {
    private final String interfaceName;
    private final String address;
    private final InetAddress inetAddress;

    private NetworkInterfaceAddress(String interfaceName, InetAddress inetAddress) {
        this.interfaceName = interfaceName;
        this.inetAddress = inetAddress;
        this.address = inetAddress.getHostAddress();
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getAddress() {
        return address;
    }

    public static List<NetworkInterfaceAddress> discover() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return Collections.emptyList();

        Map<String, NetworkInterfaceAddress> detected =
                new LinkedHashMap<String, NetworkInterfaceAddress>();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
            } catch (SocketException ignored) {
                continue;
            }

            String name = networkInterface.getDisplayName();
            if (name == null || name.trim().isEmpty()) {
                name = networkInterface.getName();
            }

            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress address = interfaceAddress.getAddress();
                if (address == null || address.isAnyLocalAddress()
                        || address.isLoopbackAddress() || address.isMulticastAddress()
                        || address.isLinkLocalAddress()) {
                    continue;
                }

                String value = address.getHostAddress();
                detected.putIfAbsent(value.toLowerCase(Locale.ROOT),
                        new NetworkInterfaceAddress(name, address));
            }
        }

        return new ArrayList<NetworkInterfaceAddress>(detected.values());
    }

    public static InetAddress resolveLocalAddress(String value) throws SocketException {
        String requested = value == null ? "" : value.trim();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("Choose a local IP address or use automatic routing.");
        }

        for (NetworkInterfaceAddress local : discover()) {
            if (local.address.equalsIgnoreCase(requested)) {
                return local.inetAddress;
            }
        }

        throw new IllegalArgumentException(
                "This IP is not assigned to an active local network interface. Refresh the list or use AUTO.");
    }
}
