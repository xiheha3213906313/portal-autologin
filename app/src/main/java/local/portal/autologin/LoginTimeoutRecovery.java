package local.portal.autologin;

/** One final verification window for a submitted request, never permission for another POST. */
final class LoginTimeoutRecovery {
    private boolean used;
    boolean begin(boolean submitted,boolean connected) {
        if(used || !submitted || !connected)return false;
        used=true;return true;
    }
}
