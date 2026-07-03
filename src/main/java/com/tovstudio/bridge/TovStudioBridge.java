// TovStudio - https://builtbybit.com/creators/tovstudio.532511/
package com.tovstudio.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class TovStudioBridge extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private HttpClient http;
    private volatile WebSocket ws;
    private volatile boolean shuttingDown = false;
    private volatile boolean welcomed = false;
    private CompletableFuture<WebSocket> sendChain;

    private String panelUrl;
    private String token;
    private String serverId;
    private int reportInterval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        panelUrl = getConfig().getString("panel-url", "ws://127.0.0.1:30002/bridge");
        token = getConfig().getString("server-token", "");
        serverId = getConfig().getString("server-id", "1");
        reportInterval = Math.max(2, getConfig().getInt("report-interval-seconds", 5));

        http = HttpClient.newHttpClient();
        Bukkit.getPluginManager().registerEvents(this, this);
        connect();

        long ticks = reportInterval * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::report, ticks, ticks);

        getLogger().info("TovStudioBridge enabled. Panel: " + panelUrl);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        WebSocket w = ws;
        if (w != null) {
            try { w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
        }
    }

    private void connect() {
        if (shuttingDown) return;
        welcomed = false;
        try {
            http.newWebSocketBuilder()
                .buildAsync(URI.create(panelUrl), new Handler())
                .whenComplete((sock, err) -> {
                    if (err != null || sock == null) {
                        getLogger().warning("Bridge connect failed: " + (err != null ? err.getMessage() : "null"));
                        scheduleReconnect();
                        return;
                    }
                    ws = sock;
                    sendChain = CompletableFuture.completedFuture(sock);
                    JsonObject hello = new JsonObject();
                    hello.addProperty("t", "hello");
                    hello.addProperty("token", token);
                    hello.addProperty("server_id", serverId);
                    hello.addProperty("version", getServer().getVersion());
                    hello.addProperty("name", getServer().getName());
                    send(hello);
                });
        } catch (Exception e) {
            getLogger().warning("Bridge error: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        ws = null;
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, this::connect, 100L);
    }

    private synchronized void send(JsonObject obj) {
        WebSocket w = ws;
        if (w == null || sendChain == null) return;
        final String text = gson.toJson(obj);
        sendChain = sendChain.thenCompose(sock -> sock.sendText(text, true));
        sendChain.exceptionally(ex -> null);
    }

    private void report() {
        if (ws == null || !welcomed) return;

        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            players.add(playerJson(p));
        }
        JsonObject pmsg = new JsonObject();
        pmsg.addProperty("t", "players");
        pmsg.add("players", players);
        send(pmsg);

        JsonObject stats = new JsonObject();
        stats.addProperty("t", "stats");
        JsonObject sp = new JsonObject();
        try { sp.addProperty("tps", Math.min(20.0, Bukkit.getServer().getTPS()[0])); } catch (Throwable ignored) {}
        try { sp.addProperty("mspt", Bukkit.getServer().getAverageTickTime()); } catch (Throwable ignored) {}
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long max = rt.maxMemory() / 1048576L;
        sp.addProperty("ram_used", used);
        sp.addProperty("ram_max", max);
        sp.addProperty("online", Bukkit.getOnlinePlayers().size());
        stats.add("payload", sp);
        send(stats);
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
        JsonObject msg = new JsonObject();
        msg.addProperty("t", "event");
        extra.addProperty("kind", kind);
        msg.add("payload", extra);
        send(msg);
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

    private void handleAction(JsonObject msg) {
        final String id = msg.has("id") ? msg.get("id").getAsString() : null;
        final String action = msg.has("action") ? msg.get("action").getAsString() : "";
        final JsonObject args = msg.has("args") && msg.get("args").isJsonObject() ? msg.getAsJsonObject("args") : new JsonObject();

        Bukkit.getScheduler().runTask(this, () -> {
            JsonObject result = new JsonObject();
            result.addProperty("t", "result");
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
                } else {
                    result.addProperty("ok", false);
                    result.addProperty("error", "unknown action: " + action);
                }
            } catch (Exception ex) {
                result.addProperty("ok", false);
                result.addProperty("error", ex.getMessage());
            }
            send(result);
        });
    }

    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            webSocket.request(1);
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                try {
                    JsonObject msg = JsonParser.parseString(full).getAsJsonObject();
                    String t = msg.has("t") ? msg.get("t").getAsString() : "";
                    if (t.equals("welcome")) welcomed = true;
                    else if (t.equals("action")) handleAction(msg);
                } catch (Exception ignored) {}
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            getLogger().warning("Bridge closed (" + statusCode + "): " + reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            getLogger().warning("Bridge socket error: " + error.getMessage());
            scheduleReconnect();
        }
    }
}
