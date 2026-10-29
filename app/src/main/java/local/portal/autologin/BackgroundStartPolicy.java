package local.portal.autologin;

/** App-required prerequisites for the opt-in background feature. */
final class BackgroundStartPolicy {
    enum Status { READY, MODULE_ACTIVE, DISABLED, MISSING_ACCOUNT, INVALID_FILTER,
        LOCATION_PERMISSION_REQUIRED, LOCATION_DISABLED, NOTIFICATION_REQUIRED }

    static Status evaluate(boolean moduleActive, boolean enabled, boolean hasAccount,
                           String filter, boolean fineLocation, boolean locationEnabled,
                           boolean notificationsVisible) {
        if (moduleActive) return Status.MODULE_ACTIVE;
        if (!enabled) return Status.DISABLED;
        if (!hasAccount) return Status.MISSING_ACCOUNT;
        if (!WifiNameMatcher.validFilter(filter)) return Status.INVALID_FILTER;
        if (!notificationsVisible) return Status.NOTIFICATION_REQUIRED;
        if (filter != null && !filter.trim().isEmpty() && !WifiNameMatcher.normalize(filter).isEmpty()) {
            if (!fineLocation) return Status.LOCATION_PERMISSION_REQUIRED;
            if (!locationEnabled) return Status.LOCATION_DISABLED;
        }
        return Status.READY;
    }

    static String label(Status status, boolean running) {
        switch (status) {
            case MISSING_ACCOUNT: case INVALID_FILTER: return "未配置";
            case LOCATION_PERMISSION_REQUIRED: case LOCATION_DISABLED:
            case NOTIFICATION_REQUIRED: return "未授权";
            case READY: return running ? "运行中" : "未运行";
            default: return "未运行";
        }
    }
}
