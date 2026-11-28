package dev.cylox.msm.automation;

public final class SessionAutomationSettings {
    private boolean autoReconnect;
    private boolean desiredOnline;
    private int reconnectDelaySeconds = 15;
    private int maxReconnectAttempts = 5;
    private boolean retryBackoff = true;
    private int maxReconnectDelaySeconds = 300;

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean enabled) {
        this.autoReconnect = enabled;
    }

    public boolean isDesiredOnline() {
        return desiredOnline;
    }

    public void setDesiredOnline(boolean desiredOnline) {
        this.desiredOnline = desiredOnline;
    }

    public int getReconnectDelaySeconds() {
        return reconnectDelaySeconds;
    }

    public void setReconnectDelaySeconds(int seconds) {
        this.reconnectDelaySeconds = clamp(seconds, 10, 300);
        this.maxReconnectDelaySeconds = Math.max(
                this.maxReconnectDelaySeconds, this.reconnectDelaySeconds);
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public void setMaxReconnectAttempts(int attempts) {
        this.maxReconnectAttempts = clamp(attempts, 1, 10);
    }

    public boolean isRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(boolean retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getMaxReconnectDelaySeconds() {
        return maxReconnectDelaySeconds;
    }

    public void setMaxReconnectDelaySeconds(int seconds) {
        this.maxReconnectDelaySeconds = Math.max(
                this.reconnectDelaySeconds, clamp(seconds, 30, 600));
    }

    public SessionAutomationSettings copy() {
        SessionAutomationSettings copy = new SessionAutomationSettings();
        copy.setAutoReconnect(isAutoReconnect());
        copy.setDesiredOnline(isDesiredOnline());
        copy.setReconnectDelaySeconds(getReconnectDelaySeconds());
        copy.setMaxReconnectAttempts(getMaxReconnectAttempts());
        copy.setRetryBackoff(isRetryBackoff());
        copy.setMaxReconnectDelaySeconds(getMaxReconnectDelaySeconds());
        return copy;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
