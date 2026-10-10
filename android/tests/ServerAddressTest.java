import app.zhinong.mobile.ServerAddress;

public class ServerAddressTest {
    public static void main(String[] args) {
        eq("https://farm.example.com", ServerAddress.normalize(" https://farm.example.com/mobile/index.html "));
        eq("http://127.0.0.1:9175", ServerAddress.normalize("http://127.0.0.1:9175/"));
        for (String invalid : new String[]{"javascript:alert(1)", "file:///tmp/app", "https://user:pass@farm.example.com", "https://farm.example.com/?token=x", "https://farm.example.com/#x", "https://farm.example.com/api", "https://farm.example.com:99999", "https://farm.example.com:0", "farm.example.com"}) {
            try { ServerAddress.normalize(invalid); throw new AssertionError("Accepted invalid address"); } catch (IllegalArgumentException expected) {}
        }
        if (!ServerAddress.sameOrigin("https://farm.example.com", "https://farm.example.com:443/mobile/index.html")) throw new AssertionError("Default port");
        for (String other : new String[]{"https://farm.example.com.evil.invalid/", "https://farm.example.com@evil.invalid/", "http://farm.example.com/", "https://farm.example.com:444/", "file:///app"}) {
            if (ServerAddress.sameOrigin("https://farm.example.com", other)) throw new AssertionError("Cross-origin navigation allowed");
        }
        System.out.println("Android server address validation passed");
    }
    static void eq(String expected, String actual) { if (!expected.equals(actual)) throw new AssertionError(actual); }
}
