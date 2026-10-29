package local.portal.autologin;

/** Foreground authentication result. Portal availability is not proof of internet connectivity. */
final class PortalLoginTask {
    enum Outcome { CHECK_INTERNET, REJECTED }
    static final class Result {
        final Outcome outcome;
        final Exception failure;
        Result(Outcome outcome,Exception failure) {this.outcome=outcome;this.failure=failure;}
    }
    static Result run(PortalHttpClient client,String username,String password) {
        try {
            client.login(username,password);
            // A successful response is only a hint; the selected Wi-Fi must still be checked.
            return new Result(Outcome.CHECK_INTERNET,null);
        } catch(Exception failure) {
            String reason=failure.getMessage();
            boolean rejected="login_form_returned".equals(reason) || "authentication_rejected".equals(reason);
            // Already-authorized gateways may close the login endpoint before even returning HTTP
            // headers. This also applies to forced attempts, without trusting a cached VALIDATED bit.
            return new Result(rejected?Outcome.REJECTED:Outcome.CHECK_INTERNET,failure);
        }
    }
}
