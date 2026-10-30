package local.portal.autologin;

import android.net.Network;
import java.net.HttpURLConnection;
import java.net.URL;

/** Same Wi-Fi and proxy policy, but the single POST cannot reuse a GET's pooled socket. */
final class NetworkPortalTransport implements PortalHttpClient.Transport {
    private final Network network;

    NetworkPortalTransport(Network network) { this.network=network; }

    @Override public HttpURLConnection open(URL url) throws Exception {
        return (HttpURLConnection)network.openConnection(url);
    }

    @Override public HttpURLConnection openForSubmission(URL url) throws Exception {
        // AOSP keeps the URL connection factory/pool per Network object. Reconstructing the same
        // handle retains its routing and DNS flags without using that object's existing pool.
        Network isolated=Network.fromNetworkHandle(network.getNetworkHandle());
        DiagnosticLog.event("submit.fresh_network_transport",1);
        return (HttpURLConnection)isolated.openConnection(url);
    }
}
