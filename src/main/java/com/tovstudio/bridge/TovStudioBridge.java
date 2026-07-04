// TovStudio - https://builtbybit.com/creators/tovstudio.532511/
package com.tovstudio.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class TovStudioBridge extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private HttpClient http;

    private String base;
    private String token;
    private String serverId;
    private int reportInterval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        base = getConfig().getString("panel-url", "http://127.0.0.1:30001");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        token = getConfig().getString("server-token", "");
        serverId = getConfig().getString("server-id", "1");
        reportInterval = Math.max(2, getConfig().getInt("report-interval-seconds", 5));

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::report, 60L, reportInterval * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 40L, 30L);

        getLogger().info("TovStudioBridge enabled (HTTP). Panel: " + base);
    }

    private JsonObject baseMsg() {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        o.addProperty("server_id", serverId);
        o.addProperty("version", getServer().getVersion());
        o.addProperty("name", getServer().getName());
        return o;
    }

    private void postAsync(String path, JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(8))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).exceptionally(ex -> null);
    }

    private void poll() {
        JsonObject body = baseMsg();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(base + "/bridge/poll"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(8))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(HttpResponse::body)
            .thenAccept(this::handlePollResponse)
            .exceptionally(ex -> null);
    }

    private void handlePollResponse(String respBody) {
        if (respBody == null) return;
        try {
            JsonObject obj = JsonParser.parseString(respBody).getAsJsonObject();
            if (!obj.has("actions")) return;
            JsonArray actions = obj.getAsJsonArray("actions");
            for (JsonElement el : actions) {
                handleAction(el.getAsJsonObject());
            }
        } catch (Exception ignored) {}
    }

    private void report() {
        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) players.add(playerJson(p));
        JsonObject pmsg = baseMsg();
        pmsg.addProperty("t", "players");
        pmsg.add("players", players);
        postAsync("/bridge/push", pmsg);

        JsonObject stats = baseMsg();
        stats.addProperty("t", "stats");
        JsonObject sp = new JsonObject();
        try { sp.addProperty("tps", Math.min(20.0, Bukkit.getServer().getTPS()[0])); } catch (Throwable ignored) {}
        try { sp.addProperty("mspt", Bukkit.getServer().getAverageTickTime()); } catch (Throwable ignored) {}
        Runtime rt = Runtime.getRuntime();
        sp.addProperty("ram_used", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
        sp.addProperty("ram_max", rt.maxMemory() / 1048576L);
        sp.addProperty("online", Bukkit.getOnlinePlayers().size());
        sp.addProperty("plugins", Bukkit.getPluginManager().getPlugins().length);
        stats.add("payload", sp);
        postAsync("/bridge/push", stats);
    }

    private JsonObject playerJson(Player p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName());
        o.addProperty("uuid", p.getUniqueId().toString());
        try { o.addProperty("world", p.getWorld().getName()); } catch (Throwable ignored) {}
        try { o.addProperty("health", p.getHealth()); } catch (Throwable ignored) {}
        try { o.addProperty("ping", p.getPing()); } catch (Throwable ignored) {}
        return o;
    }

    private void event(String kind, JsonObject extra) {
        JsonObject msg = baseMsg();
        msg.addProperty("t", "event");
        extra.addProperty("kind", kind);
        msg.add("payload", extra);
        postAsync("/bridge/push", msg);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.getPlayer().getName());
        o.addProperty("uuid", e.getPlayer().getUniqueId().toString());
        event("join", o);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.getPlayer().getName());
        event("quit", o);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.getPlayer().getName());
        o.addProperty("message", e.getMessage());
        event("chat", o);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.getEntity().getName());
        o.addProperty("message", e.getDeathMessage() == null ? (e.getEntity().getName() + " died") : e.getDeathMessage());
        event("death", o);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.getPlayer().getName());
        o.addProperty("command", e.getMessage());
        event("command", o);
    }

    private void handleAction(JsonObject msg) {
        final String id = msg.has("id") ? msg.get("id").getAsString() : null;
        final String action = msg.has("action") ? msg.get("action").getAsString() : "";
        final JsonObject args = msg.has("args") && msg.get("args").isJsonObject() ? msg.getAsJsonObject("args") : new JsonObject();

        Bukkit.getScheduler().runTask(this, () -> {
            JsonObject result = baseMsg();
            if (id != null) result.addProperty("id", id);
            try {
                if (action.equals("players")) {
                    JsonArray arr = new JsonArray();
                    for (Player p : Bukkit.getOnlinePlayers()) arr.add(playerJson(p));
                    result.addProperty("ok", true);
                    result.add("data", arr);
                } else if (action.equals("command")) {
                    String c = args.has("command") ? args.get("command").getAsString() : "";
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                    result.addProperty("ok", ok);
                } else if (action.equals("give")) {
                    String player = args.get("player").getAsString();
                    String item = args.get("item").getAsString();
                    int amount = args.has("amount") ? args.get("amount").getAsInt() : 1;
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + player + " " + item + " " + amount);
                    result.addProperty("ok", ok);
                } else if (action.equals("broadcast")) {
                    String message = args.has("message") ? args.get("message").getAsString() : "";
                    Bukkit.broadcastMessage(message);
                    result.addProperty("ok", true);
                } else if (action.equals("plugins")) {
                    JsonArray arr = new JsonArray();
                    for (org.bukkit.plugin.Plugin pl : Bukkit.getPluginManager().getPlugins()) {
                        JsonObject po = new JsonObject();
                        po.addProperty("name", pl.getName());
                        po.addProperty("version", pl.getDescription().getVersion());
                        po.addProperty("enabled", pl.isEnabled());
                        arr.add(po);
                    }
                    result.addProperty("ok", true);
                    result.add("data", arr);
                } else {
                    result.addProperty("ok", false);
                    result.addProperty("error", "unknown action: " + action);
                }
            } catch (Exception ex) {
                result.addProperty("ok", false);
                result.addProperty("error", ex.getMessage());
            }
            postAsync("/bridge/result", result);
        });
    }
}
