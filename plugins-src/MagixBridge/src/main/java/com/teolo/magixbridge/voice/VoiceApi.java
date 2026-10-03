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
 * same VPS): which rooms exist, who is in a room, a data packet for one participant, and the two
 * moderation moves (take the microphone away or give it back, put someone out of a room).
 *
 * The API listens on 127.0.0.1 only and wants a token signed with the voice server's secret. The
 * secret is never in a config file of ours: it is read from the voice server's own key file
 * (/etc/magix-voce/keys.yaml, one line "key: secret"), which predisponi-voce.yml makes readable
 * for the user the Minecraft servers run as.
 */
public final class VoiceApi {

    /**
     * A participant of a room: who, whether the microphone is live (published and not muted), and
     * whether the voice server lets them publish at all (false = listen only).
     */
    public record Participant(String identity, boolean speaking, boolean canPublish) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String baseUrl;
    private final String apiKey;
    private final byte[] apiSecret;
    /** Admin token per room ("" = the room list), and when it runs out. */
    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();
    private final java.util.Map<String, Long> tokensExpireAt = new java.util.HashMap<>();

    private VoiceApi(String baseUrl, String apiKey, String apiSecret) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.apiSecret = apiSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads the key file and builds the client for one room. Throws with a readable reason when the
     * file is missing, unreadable or empty: the caller logs it and leaves proximity off.
     */
    public static VoiceApi open(String baseUrl, Path keysFile) throws IOException {
        for (String line : Files.readAllLines(keysFile, StandardCharsets.UTF_8)) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("#") || colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            String secret = t.substring(colon + 1).trim().replaceAll("^\"|\"$", "");
            if (!key.isEmpty() && !secret.isEmpty()) {
                return new VoiceApi(baseUrl, key, secret);
            }
        }
        throw new IOException("nessuna riga 'chiave: segreto' in " + keysFile);
    }

    /** The names of the rooms that exist now (a room closes a little after the last one leaves). */
    public CompletableFuture<List<String>> listRooms() {
        return call("", "ListRooms", new JsonObject()).thenApply(json -> {
            List<String> out = new ArrayList<>();
            if (json != null && json.has("rooms")) {
                for (JsonElement e : json.getAsJsonArray("rooms")) {
                    out.add(str(e.getAsJsonObject(), "name"));
                }
            }
            return out;
        });
    }

    /** Who is in the room now. An absent room (nobody ever joined, or it closed) is an empty list. */
    public CompletableFuture<List<Participant>> listParticipants(String room) {
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        return call(room, "ListParticipants", body).thenApply(json -> {
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
                boolean canPublish = true;
                if (p.has("permission") && p.get("permission").isJsonObject()) {
                    JsonObject perm = p.getAsJsonObject("permission");
                    canPublish = perm.has("can_publish") && perm.get("can_publish").getAsBoolean();
                }
                out.add(new Participant(str(p, "identity"), speaking, canPublish));
            }
            return out;
        });
    }

    /** A lossy data packet (topic "proximity") for one participant only. */
    public CompletableFuture<JsonObject> sendTo(String room, String identity, String payload) {
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        body.addProperty("data", Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        body.addProperty("kind", "LOSSY");
        body.addProperty("topic", "proximity");
        JsonArray to = new JsonArray();
        to.add(identity);
        body.add("destination_identities", to);
        return call(room, "SendData", body);
    }

    /**
     * Lets a participant publish their microphone, or not (listen only). Taking it away also takes
     * the microphone already published off the air at once.
     */
    public CompletableFuture<JsonObject> setCanPublish(String room, String identity, boolean canPublish) {
        JsonObject perm = new JsonObject();
        perm.addProperty("can_subscribe", true);
        perm.addProperty("can_publish", canPublish);
        perm.addProperty("can_publish_data", false);
        perm.addProperty("can_update_metadata", false);
        JsonArray sources = new JsonArray();
        if (canPublish) {
            sources.add("MICROPHONE");
        }
        perm.add("can_publish_sources", sources);
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        body.addProperty("identity", identity);
        body.add("permission", perm);
        return call(room, "UpdateParticipant", body);
    }

    /** Puts a participant out of a room (they can come back only with a new token from the site). */
    public CompletableFuture<JsonObject> remove(String room, String identity) {
        JsonObject body = new JsonObject();
        body.addProperty("room", room);
        body.addProperty("identity", identity);
        return call(room, "RemoveParticipant", body);
    }

    /** One RoomService call. null = the server said no (e.g. the room does not exist yet). */
    private CompletableFuture<JsonObject> call(String room, String method, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/twirp/livekit.RoomService/" + method))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token(room))
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

    /**
     * Admin token for ONE room (the voice server wants the room named in the token), or for the room
     * list when room is "". Renewed a minute before it runs out.
     */
    private synchronized String token(String room) {
        long now = System.currentTimeMillis() / 1000L;
        if (now < tokensExpireAt.getOrDefault(room, 0L) - 60) {
            return tokens.get(room);
        }
        JsonObject video = new JsonObject();
        if (room.isEmpty()) {
            video.addProperty("roomList", true);
        } else {
            video.addProperty("roomAdmin", true);
            video.addProperty("room", room);
        }
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
            tokens.put(room, unsigned + "." + b64(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 non disponibile", e);
        }
        tokensExpireAt.put(room, now + 600);
        if (tokens.size() > 200) {   // rooms come and go: do not keep tokens for closed ones forever
            tokens.keySet().retainAll(java.util.Set.of(room));
            tokensExpireAt.keySet().retainAll(java.util.Set.of(room));
        }
        return tokens.get(room);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
