package dev.cylox.msm.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-account automation preferences. These settings contain no credentials.
 */
public final class SessionAutomationSettings {
    private boolean autoReconnect;
    private boolean desiredOnline;
    private int reconnectDelaySeconds = 10;
    private int maxReconnectAttempts = 10;
    private boolean retryBackoff = true;
    private int maxReconnectDelaySeconds = 60;

    private boolean autoChatEnabled;
    private int initialChatDelaySeconds = 5;
    private int globalChatIntervalSeconds = 3;
    private final List<AutoChatRule> rules = new ArrayList<AutoChatRule>();

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
        this.reconnectDelaySeconds = clamp(seconds, 1, 3600);
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public void setMaxReconnectAttempts(int attempts) {
        this.maxReconnectAttempts = clamp(attempts, 1, 100);
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
        this.maxReconnectDelaySeconds = clamp(seconds, 1, 3600);
    }

    public boolean isAutoChatEnabled() {
        return autoChatEnabled;
    }

    public void setAutoChatEnabled(boolean autoChatEnabled) {
        this.autoChatEnabled = autoChatEnabled;
    }

    public int getInitialChatDelaySeconds() {
        return initialChatDelaySeconds;
    }

    public void setInitialChatDelaySeconds(int seconds) {
        this.initialChatDelaySeconds = clamp(seconds, 0, 3600);
    }

    public int getGlobalChatIntervalSeconds() {
        return globalChatIntervalSeconds;
    }

    public void setGlobalChatIntervalSeconds(int seconds) {
        this.globalChatIntervalSeconds = clamp(seconds, 2, 3600);
    }

    public synchronized List<AutoChatRule> getRules() {
        return Collections.unmodifiableList(new ArrayList<AutoChatRule>(rules));
    }

    public synchronized void addRule(AutoChatRule rule) {
        if (rule == null) throw new IllegalArgumentException("AutoChat rule is required");
        if (rules.size() >= 100) throw new IllegalStateException("Maximum 100 AutoChat rules per account");
        rules.add(rule.copy());
    }

    public synchronized void updateRule(int index, AutoChatRule rule) {
        if (rule == null) throw new IllegalArgumentException("AutoChat rule is required");
        if (index < 0 || index >= rules.size()) throw new IndexOutOfBoundsException("Unknown AutoChat rule");
        rules.set(index, rule.copy());
    }

    public synchronized void removeRule(int index) {
        if (index < 0 || index >= rules.size()) throw new IndexOutOfBoundsException("Unknown AutoChat rule");
        rules.remove(index);
    }

    public synchronized void replaceRules(List<AutoChatRule> newRules) {
        if (newRules == null || newRules.size() > 100) {
            throw new IllegalArgumentException("AutoChat supports 0-100 rules per account");
        }
        rules.clear();
        for (AutoChatRule rule : newRules) {
            if (rule == null) throw new IllegalArgumentException("AutoChat rule cannot be null");
            rules.add(rule.copy());
        }
    }

    public SessionAutomationSettings copy() {
        SessionAutomationSettings copy = new SessionAutomationSettings();
        copy.setAutoReconnect(isAutoReconnect());
        copy.setDesiredOnline(isDesiredOnline());
        copy.setReconnectDelaySeconds(getReconnectDelaySeconds());
        copy.setMaxReconnectAttempts(getMaxReconnectAttempts());
        copy.setRetryBackoff(isRetryBackoff());
        copy.setMaxReconnectDelaySeconds(getMaxReconnectDelaySeconds());
        copy.setAutoChatEnabled(isAutoChatEnabled());
        copy.setInitialChatDelaySeconds(getInitialChatDelaySeconds());
        copy.setGlobalChatIntervalSeconds(getGlobalChatIntervalSeconds());
        copy.replaceRules(getRules());
        return copy;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class AutoChatRule {
        public enum Mode {
            ONCE,
            REPEAT
        }

        private final String message;
        private final Mode mode;
        private final int delaySeconds;
        private final boolean enabled;

        public AutoChatRule(String message, Mode mode, int delaySeconds, boolean enabled) {
            String normalized = message == null ? "" : message.trim();
            if (normalized.isEmpty() || normalized.length() > 256) {
                throw new IllegalArgumentException("AutoChat message must contain 1-256 characters");
            }
            if (mode == null) throw new IllegalArgumentException("AutoChat mode is required");
            int minimum = mode == Mode.REPEAT ? 5 : 0;
            this.message = normalized;
            this.mode = mode;
            this.delaySeconds = clamp(delaySeconds, minimum, 86400);
            this.enabled = enabled;
        }

        public String getMessage() {
            return message;
        }

        public Mode getMode() {
            return mode;
        }

        public int getDelaySeconds() {
            return delaySeconds;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public AutoChatRule copy() {
            return new AutoChatRule(message, mode, delaySeconds, enabled);
        }
    }
}
