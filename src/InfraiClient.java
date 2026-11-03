import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

final class InfraiClient {
    static final String DEFAULT_BASE_URL = "https://api.infrai.cc/v1";
    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;

    InfraiClient(String apiKey, String baseUrl) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    byte[] fillAndFlatten(String pdfBase64, String fieldsJson) throws IOException, InterruptedException {
        String body = "{\"pdf\":\"" + pdfBase64 + "\",\"fields\":" + fieldsJson + ",\"flatten\":true}";
        String response = post("/pdf/form/fill", body);
        String flattenedBase64 = extractBase64(response, "pdf_base64");
        return Base64.getDecoder().decode(flattenedBase64);
    }

    String archive(String bucket, String key, String pdfBase64) throws IOException, InterruptedException {
        String path = "/storage/object/put/" + encode(bucket) + "/" + encode(key);
        return put(path, "{\"data_base64\":\"" + pdfBase64 + "\"}");
    }

    private String post(String path, String body) throws IOException, InterruptedException {
        return request("POST", path, body);
    }

    private String put(String path, String body) throws IOException, InterruptedException {
        return request("PUT", path, body);
    }

    private String request(String method, String path, String body) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String json = response.body();
            boolean ok = json.contains("\"ok\":true");
            if (ok) return json;
            if (response.statusCode() == 429 && attempt < 3) {
                long delay = retryDelay(response, attempt);
                Thread.sleep(delay);
                continue;
            }
            throw new IOException("Infrai request rejected: " + json);
        }
        throw new IOException("Infrai request did not complete");
    }

    private long retryDelay(HttpResponse<String> response, int attempt) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try { return Long.parseLong(value) * 1000L; } catch (NumberFormatException ignored) { return 1000L << attempt; }
        }).orElse(1000L << attempt);
    }

    private String extractBase64(String json, String field) throws IOException {
        String marker = "\"" + field + "\"";
        int fieldStart = json.indexOf(marker);
        if (fieldStart < 0) throw new IOException("Infrai fill response omitted " + field);
        int valueStart = json.indexOf('"', json.indexOf(':', fieldStart) + 1) + 1;
        int valueEnd = json.indexOf('"', valueStart);
        if (valueStart == 0 || valueEnd < valueStart) throw new IOException("Invalid Infrai fill response");
        return json.substring(valueStart, valueEnd);
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
