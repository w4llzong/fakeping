package dev.fakeping;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class FakePing extends JavaPlugin implements Listener {

    private record Settings(boolean enabled, int min, int max, long changeMs,
                            double longChance, long longMs, Map<String, int[]> overrides) {}

    private static final class State {
        int value;
        long nextChange;
        int min = Integer.MIN_VALUE;
        int max = Integer.MIN_VALUE;
    }

    private volatile Settings settings;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final List<PlaceholderExpansion> expansions = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        for (String id : new String[] {"ping", "fakeping"}) {
            Expansion exp = new Expansion(this, id);
            if (exp.register()) {
                expansions.add(exp);
                getLogger().info("Registered %" + id + "_...% placeholders.");
            } else {
                getLogger().warning("Could not register the '" + id + "' placeholder identifier "
                        + "(another expansion is using it). Use %fakeping_ping% instead, or run "
                        + "/papi unregister ping and restart.");
            }
        }
    }

    @Override
    public void onDisable() {
        for (PlaceholderExpansion exp : expansions) {
            try {
                exp.unregister();
            } catch (Throwable ignored) {
            }
        }
        expansions.clear();
        states.clear();
    }

    private void loadSettings() {
        reloadConfig();
        int min = Math.max(0, getConfig().getInt("ping.min", 40));
        int max = Math.max(0, getConfig().getInt("ping.max", 90));
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        Map<String, int[]> overrides = new HashMap<>();
        ConfigurationSection sec = getConfig().getConfigurationSection("players");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                int pmin = Math.max(0, sec.getInt(name + ".min", min));
                int pmax = Math.max(0, sec.getInt(name + ".max", max));
                if (pmin > pmax) {
                    int t = pmin;
                    pmin = pmax;
                    pmax = t;
                }
                overrides.put(name.toLowerCase(Locale.ROOT), new int[] {pmin, pmax});
            }
        }
        settings = new Settings(
                getConfig().getBoolean("enabled", true),
                min, max,
                Math.max(50L, getConfig().getLong("change-interval-ms", 1500L)),
                Math.min(1.0, Math.max(0.0, getConfig().getDouble("long-hold.chance", 0.15))),
                Math.max(50L, getConfig().getLong("long-hold.duration-ms", 5000L)),
                overrides);
        states.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    String realPing(OfflinePlayer offline) {
        Player p = offline == null ? null : offline.getPlayer();
        return p == null ? "0" : String.valueOf(p.getPing());
    }

    String fakePing(OfflinePlayer offline) {
        Settings s = settings;
        if (offline == null) {
            return "0";
        }
        if (!s.enabled()) {
            return realPing(offline);
        }

        int min = s.min();
        int max = s.max();
        String name = offline.getName();
        if (name != null) {
            int[] o = s.overrides().get(name.toLowerCase(Locale.ROOT));
            if (o != null) {
                min = o[0];
                max = o[1];
            }
        }

        State st = states.computeIfAbsent(offline.getUniqueId(), id -> new State());
        synchronized (st) {
            long now = System.currentTimeMillis();
            boolean rangeChanged = st.min != min || st.max != max;
            if (rangeChanged || now >= st.nextChange) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                int old = st.value;
                int next = min == max ? min : r.nextInt(min, max + 1);
                if (!rangeChanged && min != max && next == old) {
                    next = r.nextInt(min, max + 1); // one extra try so it visibly jumps
                }
                st.value = next;
                st.min = min;
                st.max = max;
                st.nextChange = now + (r.nextDouble() < s.longChance() ? s.longMs() : s.changeMs());
            }
            return String.valueOf(st.value);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/fakeping <reload|status|set <player> <min> <max>|clear <player>>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage("FakePing reloaded.");
            }
            case "status" -> {
                Settings s = settings;
                sender.sendMessage("enabled=" + s.enabled() + " range=" + s.min() + "-" + s.max()
                        + " interval=" + s.changeMs() + "ms long-hold=" + s.longChance() + "@" + s.longMs()
                        + "ms overrides=" + s.overrides().size());
            }
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage("/fakeping set <player> <min> <max>");
                    return true;
                }
                try {
                    int a = Math.max(0, Integer.parseInt(args[2]));
                    int b = Math.max(0, Integer.parseInt(args[3]));
                    getConfig().set("players." + args[1] + ".min", Math.min(a, b));
                    getConfig().set("players." + args[1] + ".max", Math.max(a, b));
                    saveConfig();
                    loadSettings();
                    sender.sendMessage(args[1] + " now shows " + Math.min(a, b) + "-" + Math.max(a, b) + " ping.");
                } catch (NumberFormatException e) {
                    sender.sendMessage("min and max must be whole numbers.");
                }
            }
            case "clear" -> {
                if (args.length < 2) {
                    sender.sendMessage("/fakeping clear <player>");
                    return true;
                }
                getConfig().set("players." + args[1], null);
                saveConfig();
                loadSettings();
                sender.sendMessage(args[1] + " is back on the default range.");
            }
            default -> sender.sendMessage("/fakeping <reload|status|set|clear>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[] {"reload", "status", "set", "clear"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("clear"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    private static final class Expansion extends PlaceholderExpansion {
        private final FakePing plugin;
        private final String id;

        Expansion(FakePing plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        @Override
        public String getIdentifier() {
            return id;
        }

        @Override
        public String getAuthor() {
            return "FakePing";
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            String p = params.toLowerCase(Locale.ROOT);
            if (id.equals("ping")) {
                if (p.equals("fake")) {
                    return plugin.fakePing(player);
                }
                if (p.equals("real")) {
                    return plugin.realPing(player);
                }
                return null;
            }
            return plugin.fakePing(player);
        }
    }
}
