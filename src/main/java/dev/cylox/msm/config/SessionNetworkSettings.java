package dev.cylox.msm.config;

/** Per-account outgoing socket binding preferences. */
public final class SessionNetworkSettings {
    private final String outgoingAddress;
    private final String proxyProfileId;

    public SessionNetworkSettings(String outgoingAddress) {
        this(outgoingAddress, "");
    }

    public SessionNetworkSettings(String outgoingAddress, String proxyProfileId) {
        String normalized = outgoingAddress == null ? "" : outgoingAddress.trim();
        String proxyId = proxyProfileId == null ? "" : proxyProfileId.trim();
        if (normalized.length() > 64 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Outgoing IP address is invalid.");
        }
        if (proxyId.length() > 64 || proxyId.indexOf('\n') >= 0 || proxyId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Proxy profile selection is invalid.");
        }
        this.outgoingAddress = normalized;
        this.proxyProfileId = proxyId;
    }

    /** Empty means that the operating system selects the source address. */
    public String getOutgoingAddress() {
        return this.outgoingAddress;
    }

    public boolean isAutomatic() {
        return this.outgoingAddress.isEmpty();
    }

    /** Empty selects Direct Connection. */
    public String getProxyProfileId() { return this.proxyProfileId; }
    public boolean usesProxy() { return !this.proxyProfileId.isEmpty(); }
}
