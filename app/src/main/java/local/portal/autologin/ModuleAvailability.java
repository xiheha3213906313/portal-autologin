package local.portal.autologin;

/** Probe before the first private open: Android's cached preferences may later bypass mode checks. */
final class ModuleAvailability {
    private Boolean available;

    synchronized boolean detect(Runnable openWorldReadable) {
        if (available == null) {
            try {
                openWorldReadable.run();
                available = true;
            } catch (SecurityException denied) {
                available = false;
            }
        }
        return available;
    }
}
