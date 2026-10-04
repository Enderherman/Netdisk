import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/** Local HTTP liveness probe; dependency health is checked separately by Compose. */
public final class Healthcheck {
    private static final Pattern SUCCESS = Pattern.compile("\"code\"\\s*:\\s*200\\b");

    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("NETDISK_PORT", "7090"));
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/accountCapabilities"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.exit(response.statusCode() == 200 && SUCCESS.matcher(response.body()).find() ? 0 : 1);
        } catch (Exception failure) {
            System.exit(1);
        }
    }
}
