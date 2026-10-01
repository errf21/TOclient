package hev.htproxy;

/**
 * JNI contract for the independently pinned Hev SOCKS5 Tunnel source build.
 * Native registration requires this exact package and class name.
 */
public final class TProxyService {
    private TProxyService() {
    }

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int tunFileDescriptor);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}