package com.teolo.magixguard.alert;

import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.analyze.LinkScorer;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.model.PlayerRef;
import com.teolo.magixguard.sanctions.Text;
import com.teolo.magixguard.util.Help;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Segnalazioni allo staff.
 *
 * Regola non negoziabile della Fase 1: il giocatore profilato non riceve nulla. Nessun messaggio,
 * nessun kick, nessun effetto visibile. Se capisse quale segnale lo ha tradito, la volta dopo
 * cambierebbe proprio quello.
 */
public final class AlertService {

    private final Plugin plugin;
    private final GuardConfig config;
    private final GuardDao dao;

    public AlertService(Plugin plugin, GuardConfig config, GuardDao dao) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
    }

    /** Registra la segnalazione e avvisa chi ha il permesso magixguard.alerts. Gira sul thread DB. */
    public void raise(long aId, long bId, LinkScorer.Result result, long now) throws SQLException {
        Optional<PlayerRef> a = dao.findPlayer(aId);
        Optional<PlayerRef> b = dao.findPlayer(bId);
        if (a.isEmpty() || b.isEmpty()) return;

        String nameA = a.get().name();
        String nameB = b.get().name();
        double score = result.score();

        dao.insertAlert(aId, bId, score, now);
        dao.appendAudit("MagixGuard", "ALERT", nameA + " <-> " + nameB,
                "punteggio=" + Math.round(score) + "; prove=" + summary(result), now);

        String riepilogo = summary(result);

        if (config.alertConsole) {
            plugin.getLogger().info("[alert] " + nameA + " <-> " + nameB
                    + " punteggio " + Math.round(score) + " (" + riepilogo + ")");
        }

        if (config.alertInGame) {
            // La chat va toccata solo dal thread principale.
            Bukkit.getScheduler().runTask(plugin, () -> broadcastToStaff(nameA, nameB, score, result));
        }

        if (config.discordWebhook != null && !config.discordWebhook.isBlank()) {
            sendDiscord(nameA, nameB, score, riepilogo);
        }
    }

    private void broadcastToStaff(String nameA, String nameB, double score, LinkScorer.Result result) {
        Component header = Text.msg("&7possibile multi-account: &f" + nameA + " &7<-> &f" + nameB)
                .append(Component.text("  " + Math.round(score) + "/100", scoreColor(score))
                        .decorate(TextDecoration.BOLD));

        StringBuilder hover = new StringBuilder();
        for (LinkScorer.Line line : result.lines()) {
            hover.append(line.appliedWeight() >= 0 ? "+" : "")
                    .append(Math.round(line.appliedWeight())).append("  ")
                    .append(line.type().description()).append('\n');
        }
        hover.append("\nClicca per aprire il dossier completo.");

        Component message = header
                .hoverEvent(HoverEvent.showText(Component.text(hover.toString().trim(), Help.GREY)))
                .clickEvent(ClickEvent.runCommand("/mg alts " + nameA));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("magixguard.alerts")) p.sendMessage(message);
        }
    }

    private static NamedTextColor scoreColor(double score) {
        if (score >= 100) return NamedTextColor.RED;
        if (score >= 85) return NamedTextColor.GOLD;
        return NamedTextColor.YELLOW;
    }

    private static String summary(LinkScorer.Result result) {
        StringBuilder sb = new StringBuilder();
        for (LinkScorer.Line line : result.lines()) {
            if (line.appliedWeight() == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(line.type().name()).append('(')
                    .append(line.appliedWeight() >= 0 ? "+" : "")
                    .append(Math.round(line.appliedWeight())).append(')');
        }
        return sb.length() == 0 ? "nessuna prova pesante" : sb.toString();
    }

    /** Invio al canale staff su Discord. Fatto in un thread a parte: nessuna attesa di rete sul server. */
    private void sendDiscord(String nameA, String nameB, double score, String riepilogo) {
        String content = "**MagixGuard** - possibile multi-account\\n`" + escape(nameA) + "` <-> `"
                + escape(nameB) + "` - punteggio **" + Math.round(score) + "/100**\\n" + escape(riepilogo);
        String body = "{\"content\":\"" + content + "\"}";
        Thread.ofVirtual().name("MagixGuard-discord").start(() -> {
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.discordWebhook))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    plugin.getLogger().warning("Webhook Discord: risposta " + response.statusCode());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Webhook Discord non raggiungibile: " + e.getMessage());
            }
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
