package me.simpleauction;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public class SimpleAuction extends JavaPlugin implements TabExecutor {

    private Economy economy;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private File dataFile;
    private long activeMs;
    private long expiredMs;
    private AuctionGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        dataFile = new File(getDataFolder(), "auctions.yml");
        load();

        gui = new AuctionGui(this);
        getServer().getPluginManager().registerEvents(gui, this);

        PluginCommand cmd = getCommand("ah");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // раз в минуту удаляем лоты, которые пролежали в «Истёкших» дольше срока
        getServer().getScheduler().runTaskTimer(this, this::cleanup, 20L * 60, 20L * 60);
        cleanup();
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------------------------------------------------------------- config

    private void readConfig() {
        activeMs = (long) (getConfig().getDouble("active-hours", 24) * 3_600_000L);
        expiredMs = (long) (getConfig().getDouble("expired-hours", 24) * 3_600_000L);
    }

    public String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&cmissing: " + key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // --------------------------------------------------------------- economy

    /** Vault-экономика ищется лениво, т.к. плагин экономики может включиться позже нас. */
    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp =
                    getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    // ------------------------------------------------------------------ data

    private void load() {
        listings.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("listings");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            ItemStack item = c.getItemStack("item");
            String seller = c.getString("seller");
            if (item == null || seller == null) {
                getLogger().warning("Не удалось загрузить лот " + id + " (предмет или продавец не читаются)");
                continue;
            }
            listings.put(id, new Listing(id, UUID.fromString(seller),
                    c.getString("seller-name", "?"), item, c.getDouble("price"), c.getLong("created")));
        }
    }

    /** Сохранение синхронное и атомарное: лот снимается с аукциона и записывается ДО выдачи предмета. */
    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "listings." + l.id;
            y.set(p + ".seller", l.seller.toString());
            y.set(p + ".seller-name", l.sellerName);
            y.set(p + ".item", l.item);
            y.set(p + ".price", l.price);
            y.set(p + ".created", l.createdAt);
        }
        try {
            getDataFolder().mkdirs();
            File tmp = new File(getDataFolder(), "auctions.yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить auctions.yml: " + e.getMessage());
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        boolean changed = listings.values().removeIf(l -> now >= l.createdAt + activeMs + expiredMs);
        if (changed) save();
    }

    // --------------------------------------------------------------- queries

    public Listing get(String id) {
        return listings.get(id);
    }

    public void add(Listing l) {
        listings.put(l.id, l);
    }

    public void remove(String id) {
        listings.remove(id);
    }

    public boolean isActive(Listing l) {
        return System.currentTimeMillis() < l.createdAt + activeMs;
    }

    public boolean isExpired(Listing l) {
        long now = System.currentTimeMillis();
        return now >= l.createdAt + activeMs && now < l.createdAt + activeMs + expiredMs;
    }

    public long activeLeft(Listing l) {
        return l.createdAt + activeMs - System.currentTimeMillis();
    }

    public long expiredLeft(Listing l) {
        return l.createdAt + activeMs + expiredMs - System.currentTimeMillis();
    }

    public List<Listing> activeListings() {
        return listings.values().stream()
                .filter(this::isActive)
                .sorted(Comparator.comparingLong((Listing l) -> l.createdAt).reversed())
                .collect(Collectors.toList());
    }

    public List<Listing> expiredOf(UUID owner) {
        return listings.values().stream()
                .filter(l -> l.seller.equals(owner) && isExpired(l))
                .sorted(Comparator.comparingLong((Listing l) -> l.createdAt).reversed())
                .collect(Collectors.toList());
    }

    public long countActiveOf(UUID owner) {
        return listings.values().stream().filter(l -> l.seller.equals(owner) && isActive(l)).count();
    }

    // ----------------------------------------------------------------- utils

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String fmtTime(long ms) {
        long m = Math.max(0, ms) / 60_000L;
        long h = m / 60;
        m %= 60;
        if (h == 0 && m == 0) return "<1м";
        return h > 0 ? h + "ч " + m + "м" : m + "м";
    }

    public static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name;
        if (meta != null && meta.hasDisplayName()) {
            name = meta.getDisplayName();
        } else {
            name = item.getType().name().toLowerCase().replace('_', ' ');
        }
        return name + ChatColor.RESET + " x" + item.getAmount();
    }

    public static boolean hasSpace(Player p, ItemStack it) {
        int need = it.getAmount();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(it)) {
                need -= (s.getMaxStackSize() - s.getAmount());
                if (need <= 0) return true;
            }
        }
        return false;
    }

    public static void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("auction.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            readConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("only-player"));
            return true;
        }
        if (!p.hasPermission("auction.use")) {
            p.sendMessage(msg("no-permission"));
            return true;
        }

        if (a.length == 0) {
            gui.open(p, AuctionGui.Type.MAIN, 0);
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "sell" -> sell(p, a);
            case "expired" -> gui.open(p, AuctionGui.Type.EXPIRED, 0);
            default -> p.sendMessage(msg("usage"));
        }
        return true;
    }

    private void sell(Player p, String[] a) {
        if (!p.hasPermission("auction.sell")) {
            p.sendMessage(msg("no-permission"));
            return;
        }
        if (a.length < 2) {
            p.sendMessage(msg("usage-sell"));
            return;
        }

        double price;
        try {
            price = Double.parseDouble(a[1].replace(',', '.'));
        } catch (NumberFormatException e) {
            p.sendMessage(msg("bad-price"));
            return;
        }
        if (Double.isNaN(price) || Double.isInfinite(price)) {
            p.sendMessage(msg("bad-price"));
            return;
        }
        price = round2(price);

        double min = getConfig().getDouble("min-price", 1);
        double max = getConfig().getDouble("max-price", 1_000_000_000);
        if (price < min || price > max) {
            p.sendMessage(msg("price-limits", "min", String.valueOf(min), "max", String.valueOf(max)));
            return;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(msg("no-item"));
            return;
        }

        int maxListings = getConfig().getInt("max-listings", 5);
        if (countActiveOf(p.getUniqueId()) >= maxListings) {
            p.sendMessage(msg("limit-reached", "max", String.valueOf(maxListings)));
            return;
        }

        // Сначала забираем предмет из руки, затем создаём и сохраняем лот
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);

        Listing l = new Listing(UUID.randomUUID().toString(), p.getUniqueId(), p.getName(),
                item, price, System.currentTimeMillis());
        add(l);
        save();

        Economy eco = economy();
        String priceStr = eco != null ? eco.format(price) : String.valueOf(price);
        p.sendMessage(msg("listed", "item", itemName(item), "price", priceStr));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        if (a.length == 1) {
            List<String> opts = new ArrayList<>(List.of("sell", "expired"));
            if (sender.hasPermission("auction.admin")) opts.add("reload");
            return opts.stream()
                    .filter(s -> s.startsWith(a[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
