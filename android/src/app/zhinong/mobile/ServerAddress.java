package app.zhinong.mobile;

import java.net.URI;
import java.util.Locale;

/** Only a server origin is accepted. Business requests remain same-origin in the mobile page. */
public final class ServerAddress {
    private ServerAddress() {}
    public static String normalize(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1
                    || (path != null && !path.isEmpty() && !path.equals("/") && !path.equals("/mobile/") && !path.equals("/mobile/index.html"))) {
                throw new IllegalArgumentException();
            }
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), null, null, null).toASCIIString();
        } catch (Exception e) {
            throw new IllegalArgumentException("请输入完整 http(s) 服务器地址；不含账号、查询参数或额外路径。");
        }
    }
    public static boolean sameOrigin(String origin, String target) {
        try {
            URI a = new URI(origin), b = new URI(target);
            int ap = a.getPort() < 0 ? ("https".equals(a.getScheme()) ? 443 : 80) : a.getPort();
            int bp = b.getPort() < 0 ? ("https".equals(b.getScheme()) ? 443 : 80) : b.getPort();
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                    && ap == bp && b.getRawUserInfo() == null;
        } catch (Exception e) { return false; }
    }
}
