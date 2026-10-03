package com.teolo.magixbridge.voice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The few calls MagixBridge makes to the site's voice server (LiveKit, service magix-voce on the
 * same VPS): who is in a room, and a data packet for one participant.
 *
 * The API listens on 127.0.0.1 only and wants a token signed with the voice server's secret. The
 * secret is never in a config file of ours: it is read from the voice server's own key file
 * (/etc/magix-voce/keys.yaml, one line "key: secret"), which predisponi-voce.yml makes readable
 * for the user the Minecraft servers run as.
 */
public final class VoiceApi {

    /** A participant of a room, as far as proximity cares: who, and whether the microphone is live. */
    public record Participant(String identity, boolean speaking) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String baseUrl;
    private final String apiKey;
    private final byte[] apiSecret;
    private final String room;

    private String token = "";
    private long tokenExpiresAt;

    private VoiceApi(String baseUrl, String apiKey, String apiSecret, String room) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.apiSecret = apiSecret.getBytes(StandardCharsets.UTF_8);
        this.room = room;
    }

    /**
     * Reads the key file and builds the client for one room. Throws with a readable reason when the
     * file is missing, unreadable or empty: the caller logs it and leaves proximity off.
     */
    public static VoiceApi open(String baseUrl, Path keysFile, String room) throws IOException {
        for (String line : Files.readAllLines(keysFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("#") || colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            String secret = t.substring(colon + 1).trim().replaceAll("^\"|\"$", "");
            if (!key.isEmpty() && !secret.isEmpty()) {
                return new VoiceApi(baseUrl, key, secret, room);
            }
        }
        throw new IOException("nessuna riga 'chiave: segreto' in " + keysFile);
    }

    public String room() {
        return room;
    }

    /** Who is in the room now. An absent room (nobody ever joined, or it closed) is an empty list. */
    public CompletableFuture<List<Participant>> listParticipants() {
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        return call("ListParticipants", body).thenApply(json -> {
            List<Participant> out = new ArrayList<>();
            if (json == null || !json.has("participants")) {
                return out;
            }
            for (JsonElement e : json.getAsJsonArray("participants")) {
                JsonObject p = e.getAsJsonObject();
                boolean speaking = false;
                if (p.has("tracks")) {
                    for (JsonElement t : p.getAsJsonArray("tracks")) {
                        JsonObject track = t.getAsJsonObject();
                        boolean audio = "AUDIO".equals(str(track, "type"));
                        boolean muted = track.has("muted") && track.get("muted").getAsBoolean();
                        if (audio && !muted) {
                            speaking = true;
                        }
                    }
                }
                out.add(new Participant(str(p, "identity"), speaking));
            }
            return out;
        });
    }

    /** A lossy data packet (topic "proximity") for one participant only. */
    public CompletableFuture<JsonObject> sendTo(String identity, String payload) {
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        body.addProperty("data", Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        body.addProperty("kind", "LOSSY");
        body.addProperty("topic", "proximity");
        JsonArray to = new JsonArray();
        to.add(identity);
        body.add("destination_identities", to);
        return call("SendData", body);
    }

    /** One RoomService call. null = the server said no (e.g. the room does not exist yet). */
    private CompletableFuture<JsonObject> call(String method, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/twirp/livekit.RoomService/" + method))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) {
                return null;
            }
            JsonElement json = JsonParser.parseString(r.body());
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        });
    }

    /** Admin token for this room only, renewed a minute before it runs out. */
    private synchronized String token() {
        long now = System.currentTimeMillis() / 1000L;
        if (now < tokenExpiresAt - 60) {
            return token;
        }
        JsonObject video = new JsonObject();
        video.addProperty("roomAdmin", true);
        video.addProperty("room", room);
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", apiKey);
        claims.addProperty("sub", "magixbridge");
        claims.addProperty("nbf", now - 10);
        claims.addProperty("exp", now + 600);
        claims.add("video", video);
        String unsigned = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret, "HmacSHA256"));
            token = unsigned + "." + b64(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 non disponibile", e);
        }
        tokenExpiresAt = now + 600;
        return token;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
