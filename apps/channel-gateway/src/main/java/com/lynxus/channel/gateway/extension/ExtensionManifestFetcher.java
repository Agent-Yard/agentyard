package com.lynxus.channel.gateway.extension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

public interface ExtensionManifestFetcher {
    String fetch(String manifestUrl, Map<String, String> headers) throws IOException, InterruptedException;
}

@Component
final class JdkExtensionManifestFetcher implements ExtensionManifestFetcher {
    private static final Duration MANIFEST_FETCH_TIMEOUT = Duration.ofSeconds(5);
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(MANIFEST_FETCH_TIMEOUT)
        .build();

    @Override
    public String fetch(String manifestUrl, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(manifestUrl))
            .GET()
            .timeout(MANIFEST_FETCH_TIMEOUT);
        headers.forEach(request::header);

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new IOException("manifest fetch returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
