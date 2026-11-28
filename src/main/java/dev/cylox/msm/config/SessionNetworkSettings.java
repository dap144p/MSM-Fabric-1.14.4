package dev.cylox.msm.config;

/** Per-account outgoing socket binding preferences. */
public final class SessionNetworkSettings {
    private final String outgoingAddress;

    public SessionNetworkSettings(String outgoingAddress) {
        String normalized = outgoingAddress == null ? "" : outgoingAddress.trim();
        if (normalized.length() > 64 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Outgoing IP address is invalid.");
        }
        this.outgoingAddress = normalized;
    }

    /** Empty means that the operating system selects the source address. */
    public String getOutgoingAddress() {
        return this.outgoingAddress;
    }

    public boolean isAutomatic() {
        return this.outgoingAddress.isEmpty();
    }
}
