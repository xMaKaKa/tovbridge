// TovStudio - https://builtbybit.com/creators/tovstudio.532511/
package com.tovstudio.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

public class TovStudioBridge extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();
    private HttpClient http;

    private String base;
    private String token;
    private String serverId;
    private String bridgeUid;
    private int reportInterval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        base = getConfig().getString("panel-url", "http://127.0.0.1:30001");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        token = getConfig().getString("server-token", "");
        serverId = getConfig().getString("server-id", "1");
        reportInterval = Math.max(2, getConfig().getInt("report-interval-seconds", 5));
        bridgeUid = loadOrCreateUid();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::report, 60L, reportInterval * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 40L, 30L);

        getLogger().info("TovStudioBridge enabled (HTTP). Panel: " + base + " uid: " + bridgeUid);
    }

    private String loadOrCreateUid() {
        File f = new File(getDataFolder(), "bridge-id");
        try {
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (IOException ignored) {}
        String uid = UUID.randomUUID().toString();
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            Files.write(f.toPath(), uid.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
        return uid;
    }

    private JsonObject baseMsg() {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        o.addProperty("server_id", serverId);
        o.addProperty("bridge_uid", bridgeUid);
        o.addProperty("version", getServer().getVersion());
        o.addProperty("name", getServer().getName());
        try { o.addProperty("game_port", getServer().getPort()); } catch (Throwable ignored) {}
        try { o.addProperty("game_ip", getServer().getIp()); } catch (Throwable ignored) {}
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
        try { o.addProperty("gamemode", p.getGameMode().name()); } catch (Throwable ignored) {}
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

    private Player find(JsonObject args) {
        if (!args.has("player")) return null;
        String name = args.get("player").getAsString();
        if (name == null || name.isEmpty()) return null;
        return Bukkit.getPlayerExact(name);
    }

    private String argStr(JsonObject args, String key, String def) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : def;
    }

    private int argInt(JsonObject args, String key, int def) {
        try { return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : def; }
        catch (Exception e) { return def; }
    }

    private boolean argBool(JsonObject args, String key) {
        try { return args.has(key) && args.get(key).getAsBoolean(); }
        catch (Exception e) { return false; }
    }

    private JsonObject itemJson(ItemStack is, int slot) {
        if (is == null || is.getType() == Material.AIR) return null;
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("type", is.getType().name());
        o.addProperty("amount", is.getAmount());
        try {
            if (is.hasItemMeta()) {
                ItemMeta m = is.getItemMeta();
                if (m != null && m.hasDisplayName()) o.addProperty("name", m.getDisplayName());
                if (m != null && m.hasEnchants()) o.addProperty("enchanted", true);
                if (m instanceof Damageable) {
                    Damageable dm = (Damageable) m;
                    if (dm.hasDamage()) o.addProperty("damage", dm.getDamage());
                }
            }
        } catch (Throwable ignored) {}
        try {
            short md = is.getType().getMaxDurability();
            if (md > 0) o.addProperty("max_durability", (int) md);
        } catch (Throwable ignored) {}
        return o;
    }

    private JsonArray serializeSlots(ItemStack[] items) {
        JsonArray arr = new JsonArray();
        if (items == null) return arr;
        for (int i = 0; i < items.length; i++) {
            JsonObject o = itemJson(items[i], i);
            if (o != null) arr.add(o);
        }
        return arr;
    }

    private JsonObject invData(Player t) {
        JsonObject data = new JsonObject();
        data.addProperty("name", t.getName());
        data.addProperty("uuid", t.getUniqueId().toString());
        PlayerInventory pi = t.getInventory();
        data.add("storage", serializeSlots(pi.getStorageContents()));
        data.add("armor", serializeSlots(pi.getArmorContents()));
        JsonArray off = new JsonArray();
        JsonObject oj = itemJson(pi.getItemInOffHand(), 0);
        if (oj != null) off.add(oj);
        data.add("offhand", off);
        try { data.add("ender", serializeSlots(t.getEnderChest().getContents())); } catch (Throwable ignored) {}
        try { data.addProperty("gamemode", t.getGameMode().name()); } catch (Throwable ignored) {}
        try { data.addProperty("level", t.getLevel()); } catch (Throwable ignored) {}
        try { data.addProperty("health", t.getHealth()); } catch (Throwable ignored) {}
        try { data.addProperty("food", t.getFoodLevel()); } catch (Throwable ignored) {}
        try { data.addProperty("world", t.getWorld().getName()); } catch (Throwable ignored) {}
        return data;
    }

    private ItemStack buildItem(String type, int amount) {
        if (type == null || type.isEmpty()) return null;
        Material mat = Material.matchMaterial(type);
        if (mat == null) mat = Material.valueOf(type.toUpperCase());
        if (amount < 1) amount = 1;
        if (amount > 6400) amount = 6400;
        return new ItemStack(mat, amount);
    }

    private void setArmorSlot(PlayerInventory pi, int slot, ItemStack is) {
        switch (slot) {
            case 0: pi.setBoots(is); break;
            case 1: pi.setLeggings(is); break;
            case 2: pi.setChestplate(is); break;
            case 3: pi.setHelmet(is); break;
            default: break;
        }
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
                    String c = argStr(args, "command", "");
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                    result.addProperty("ok", ok);
                } else if (action.equals("give")) {
                    String player = args.get("player").getAsString();
                    String item = args.get("item").getAsString();
                    int amount = argInt(args, "amount", 1);
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + player + " " + item + " " + amount);
                    result.addProperty("ok", ok);
                } else if (action.equals("broadcast")) {
                    String message = argStr(args, "message", "");
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
                } else if (action.equals("inv_get")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else { result.addProperty("ok", true); result.add("data", invData(t)); }
                } else if (action.equals("inv_give")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        ItemStack is = buildItem(argStr(args, "type", ""), argInt(args, "amount", 1));
                        if (is == null) { result.addProperty("ok", false); result.addProperty("error", "bad item"); }
                        else { t.getInventory().addItem(is); result.addProperty("ok", true); result.add("data", invData(t)); }
                    }
                } else if (action.equals("inv_set")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        String section = argStr(args, "section", "storage");
                        int slot = argInt(args, "slot", -1);
                        String type = argStr(args, "type", "");
                        ItemStack is = (type == null || type.isEmpty()) ? null : buildItem(type, argInt(args, "amount", 1));
                        PlayerInventory pi = t.getInventory();
                        if (section.equals("storage")) { if (slot >= 0 && slot < pi.getStorageContents().length) pi.setItem(slot, is); }
                        else if (section.equals("armor")) { setArmorSlot(pi, slot, is); }
                        else if (section.equals("offhand")) { pi.setItemInOffHand(is); }
                        else if (section.equals("ender")) { Inventory ec = t.getEnderChest(); if (slot >= 0 && slot < ec.getSize()) ec.setItem(slot, is); }
                        result.addProperty("ok", true); result.add("data", invData(t));
                    }
                } else if (action.equals("inv_clear")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        String section = argStr(args, "section", "");
                        PlayerInventory pi = t.getInventory();
                        if (section.equals("storage")) { for (int i = 0; i < pi.getStorageContents().length; i++) pi.setItem(i, null); }
                        else if (section.equals("armor")) { pi.setArmorContents(new ItemStack[4]); }
                        else if (section.equals("offhand")) { pi.setItemInOffHand(null); }
                        else if (section.equals("ender")) { t.getEnderChest().clear(); }
                        else { pi.clear(); }
                        result.addProperty("ok", true); result.add("data", invData(t));
                    }
                } else if (action.equals("kick")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else { t.kickPlayer(argStr(args, "reason", "Kicked by an operator")); result.addProperty("ok", true); }
                } else if (action.equals("gamemode")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        GameMode gm = GameMode.valueOf(argStr(args, "mode", "SURVIVAL").toUpperCase());
                        t.setGameMode(gm);
                        result.addProperty("ok", true);
                    }
                } else if (action.equals("heal")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        double max = 20.0;
                        try { max = t.getMaxHealth(); } catch (Throwable ignored) {}
                        try { t.setHealth(max); } catch (Throwable ignored) {}
                        try { t.setFoodLevel(20); t.setSaturation(20f); t.setFireTicks(0); } catch (Throwable ignored) {}
                        result.addProperty("ok", true);
                    }
                } else if (action.equals("feed")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else { t.setFoodLevel(20); t.setSaturation(20f); result.addProperty("ok", true); }
                } else if (action.equals("fly")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        boolean en = argBool(args, "enable");
                        t.setAllowFlight(en);
                        if (!en) t.setFlying(false);
                        result.addProperty("ok", true);
                    }
                } else if (action.equals("god")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else { t.setInvulnerable(argBool(args, "enable")); result.addProperty("ok", true); }
                } else if (action.equals("tp")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else {
                        Player target = Bukkit.getPlayerExact(argStr(args, "target", ""));
                        if (target == null) { result.addProperty("ok", false); result.addProperty("error", "target offline"); }
                        else { t.teleport(target.getLocation()); result.addProperty("ok", true); }
                    }
                } else if (action.equals("op")) {
                    Player t = find(args);
                    if (t == null) { result.addProperty("ok", false); result.addProperty("error", "player offline"); }
                    else { t.setOp(argBool(args, "enable")); result.addProperty("ok", true); }
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
