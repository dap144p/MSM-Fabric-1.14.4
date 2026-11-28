package dev.cylox.msm.config;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lists and validates IP addresses currently assigned to this device's interfaces. */
public final class LocalAddressCatalog {
    private LocalAddressCatalog() {
    }

    public static List<String> getAvailableAddresses() throws SocketException {
        Set<String> addresses = new LinkedHashSet<String>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return Collections.emptyList();

        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

            Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
            while (interfaceAddresses.hasMoreElements()) {
                InetAddress address = interfaceAddresses.nextElement();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                    continue;
                }
                addresses.add(address.getHostAddress());
            }
        }

        List<String> result = new ArrayList<String>(addresses);
        Collections.sort(result, (left, right) -> {
            boolean leftV4 = left.indexOf(':') < 0;
            boolean rightV4 = right.indexOf(':') < 0;
            if (leftV4 != rightV4) return leftV4 ? -1 : 1;
            return left.compareToIgnoreCase(right);
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the address object from the current interface table.
     * A stale or non-local value fails closed instead of silently using another interface.
     */
    public static InetAddress resolveAvailableAddress(String selectedAddress)
            throws SocketException {
        String normalized = selectedAddress == null ? "" : selectedAddress.trim();
        if (normalized.isEmpty()) return null;

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
                while (interfaceAddresses.hasMoreElements()) {
                    InetAddress address = interfaceAddresses.nextElement();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    if (address.getHostAddress().equalsIgnoreCase(normalized)) return address;
                }
            }
        }
        throw new IllegalArgumentException(
                "Selected local IP is no longer assigned to an active network interface: "
                        + normalized);
    }
}
