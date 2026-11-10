package fr.chunker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chunker : zones protégées délimitées avec une baguette (blaze rod).
 * Dans une zone : pas de blocs cassés/posés, pas d'explosions, pas de PvP,
 * pas de perles, et les joueurs en combat (CombatTagPlugin) sont repoussés.
 */
public class ChunkerPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    record Point(String world, int x, int y, int z) {}

    record Claim(int id, String world, int minX, int minZ, int maxX, int maxZ) {
        boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Point> pos1 = new HashMap<>();
    private final Map<UUID, Point> pos2 = new HashMap<>();
    private int nextId = 1;
    private NamespacedKey wandKey;

    // connexion à CombatTagPlugin (réflexion : il n'a pas d'API publique)
    private Plugin combatPlugin;
    private Method combatMethod;

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "wand");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("chunker") != null) {
            getCommand("chunker").setExecutor(this);
            getCommand("chunker").setTabCompleter(this);
        }
        load();
        connectCombatTag();
        startPearlTask();
        getLogger().info(claims.size() + " zone(s) chargée(s).");
    }

    private static Component c(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    // ---------------------------------------------------------------- CombatTag

    private void connectCombatTag() {
        Plugin ct = getServer().getPluginManager().getPlugin("CombatTagPlugin");
        if (ct == null) {
            getLogger().warning("CombatTagPlugin introuvable : les joueurs en combat ne seront pas repoussés.");
            return;
        }
        try {
            Method m = ct.getClass().getDeclaredMethod("estEnCombat", Player.class);
            m.setAccessible(true);
            combatPlugin = ct;
            combatMethod = m;
            getLogger().info("Connecté à CombatTagPlugin.");
        } catch (Exception ex) {
            getLogger().warning("Connexion à CombatTagPlugin impossible : " + ex);
        }
    }

    private boolean inCombat(Player p) {
        if (combatMethod == null) return false;
        try {
            return (Boolean) combatMethod.invoke(combatPlugin, p);
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Zones

    private boolean inZone(Location l) {
        if (l == null || l.getWorld() == null || claims.isEmpty()) return false;
        String w = l.getWorld().getName();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        for (Claim cl : claims) {
            if (cl.contains(w, x, z)) return true;
        }
        return false;
    }

    private File file() {
        return new File(getDataFolder(), "claims.yml");
    }

    private void load() {
        claims.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file());
        ConfigurationSection root = cfg.getConfigurationSection("claims");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(key);
                claims.add(new Claim(id, s.getString("world", ""), s.getInt("minX"), s.getInt("minZ"),
                        s.getInt("maxX"), s.getInt("maxZ")));
                nextId = Math.max(nextId, id + 1);
            } catch (NumberFormatException ignored) {
                // entrée invalide ignorée
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Claim cl : claims) {
            String p = "claims." + cl.id() + ".";
            cfg.set(p + "world", cl.world());
            cfg.set(p + "minX", cl.minX());
            cfg.set(p + "minZ", cl.minZ());
            cfg.set(p + "maxX", cl.maxX());
            cfg.set(p + "maxZ", cl.maxZ());
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(file());
        } catch (IOException ex) {
            getLogger().warning("Sauvegarde impossible : " + ex);
        }
    }

    // ---------------------------------------------------------------- Baguette

    private ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(c("§6Chunker"));
        meta.lore(List.of(
                c("§7Clic gauche sur un bloc : position 1"),
                c("§7Clic droit sur un bloc : position 2"),
                c("§e/chunker set §7pour activer la zone")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, "wand");
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
        return "wand".equals(item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void onWandClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_BLOCK;
        boolean right = a == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) return;
        Player p = e.getPlayer();
        if (!isWand(p.getInventory().getItemInMainHand())) return;
        if (e.getClickedBlock() == null) return;
        e.setCancelled(true);
        if (!p.hasPermission("chunker.admin")) return;

        Point pt = new Point(e.getClickedBlock().getWorld().getName(),
                e.getClickedBlock().getX(), e.getClickedBlock().getY(), e.getClickedBlock().getZ());
        if (left) {
            pos1.put(p.getUniqueId(), pt);
            p.sendMessage("§6Position 1 §7: §f" + pt.x() + ", " + pt.y() + ", " + pt.z());
        } else {
            pos2.put(p.getUniqueId(), pt);
            p.sendMessage("§6Position 2 §7: §f" + pt.x() + ", " + pt.y() + ", " + pt.z());
        }
        if (pos1.containsKey(p.getUniqueId()) && pos2.containsKey(p.getUniqueId())) {
            p.sendMessage("§7Tape §e/chunker set §7pour activer la zone.");
        }
    }

    // ---------------------------------------------------------------- Blocs

    private boolean denied(Player p, Location l) {
        if (!inZone(l) || p.hasPermission("chunker.bypass")) return false;
        p.sendActionBar(c("§cZone protégée."));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (isWand(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            return;
        }
        if (denied(p, e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- Explosions

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (inZone(e.getLocation())) {
            e.setCancelled(true);
            return;
        }
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (inZone(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    /** Pas de dégâts d'explosion sur les joueurs/entités dans la zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (inZone(e.getEntity().getLocation())) e.setCancelled(true);
    }

    /** Tableaux / cadres protégés contre les explosions. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && inZone(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- PvP

    /**
     * Priorité HIGH : on annule avant le MONITOR de CombatTagPlugin, qui ignore les
     * événements annulés -> aucun joueur n'est mis "en combat" dans la zone.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker == victim) return;
        if (inZone(victim.getLocation()) || inZone(attacker.getLocation())) {
            e.setCancelled(true);
            attacker.sendActionBar(c("§cPvP interdit dans cette zone."));
        }
    }

    // ---------------------------------------------------------------- Joueurs en combat : entrée interdite

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (claims.isEmpty() || !e.hasChangedBlock()) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (!inZone(to) || inZone(from)) return;
        Player p = e.getPlayer();
        if (!inCombat(p)) return;

        e.setTo(from);
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        if (dx * dx + dz * dz < 1.0E-6) {
            Vector look = p.getLocation().getDirection();
            dx = -look.getX();
            dz = -look.getZ();
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-6) {
            dx = dx / len * 0.9;
            dz = dz / len * 0.9;
        }
        p.setVelocity(new Vector(dx, 0.35, dz));
        p.sendActionBar(c("§cTu es en combat : zone interdite !"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (!inZone(to)) return;
        Player p = e.getPlayer();
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            e.setCancelled(true);
            p.sendActionBar(c("§cLes perles sont désactivées dans cette zone."));
            return;
        }
        if (!inZone(e.getFrom()) && inCombat(p)) {
            e.setCancelled(true);
            p.sendActionBar(c("§cTu es en combat : zone interdite !"));
        }
    }

    // ---------------------------------------------------------------- Perles : despawn dans la zone

    private void startPearlTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (claims.isEmpty()) return;
                for (World w : Bukkit.getWorlds()) {
                    for (EnderPearl pearl : w.getEntitiesByClass(EnderPearl.class)) {
                        if (inZone(pearl.getLocation())) {
                            if (pearl.getShooter() instanceof Player shooter) {
                                shooter.sendActionBar(c("§cLes perles sont désactivées dans cette zone."));
                            }
                            pearl.remove();
                        }
                    }
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    // ---------------------------------------------------------------- Commande

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cCommande réservée aux joueurs.");
            return true;
        }
        if (!p.hasPermission("chunker.admin")) {
            p.sendMessage("§cCette commande est réservée aux opérateurs.");
            return true;
        }
        if (args.length == 0) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(createWand());
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            p.sendMessage("§aTu as reçu la baguette §6Chunker§a. Clic gauche = position 1, clic droit = position 2, puis §e/chunker set§a.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "set" -> createClaim(p);
            case "remove" -> {
                Location l = p.getLocation();
                String w = l.getWorld().getName();
                int before = claims.size();
                claims.removeIf(cl -> cl.contains(w, l.getBlockX(), l.getBlockZ()));
                int removed = before - claims.size();
                if (removed == 0) {
                    p.sendMessage("§cAucune zone à cet endroit.");
                } else {
                    save();
                    p.sendMessage("§aZone supprimée (" + removed + ").");
                }
            }
            case "list" -> {
                if (claims.isEmpty()) {
                    p.sendMessage("§7Aucune zone.");
                } else {
                    for (Claim cl : claims) {
                        p.sendMessage("§6#" + cl.id() + " §7" + cl.world() + " : §f" + cl.minX() + "," + cl.minZ()
                                + " §7→ §f" + cl.maxX() + "," + cl.maxZ());
                    }
                }
            }
            default -> p.sendMessage("§c/chunker §7| §c/chunker set §7| §c/chunker remove §7| §c/chunker list");
        }
        return true;
    }

    private void createClaim(Player p) {
        Point a = pos1.get(p.getUniqueId());
        Point b = pos2.get(p.getUniqueId());
        if (a == null || b == null) {
            p.sendMessage("§cDéfinis d'abord les 2 positions avec la baguette (clic gauche = pos 1, clic droit = pos 2).");
            return;
        }
        if (!a.world().equals(b.world())) {
            p.sendMessage("§cLes deux positions doivent être dans le même monde.");
            return;
        }
        Claim cl = new Claim(nextId++, a.world(),
                Math.min(a.x(), b.x()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.z(), b.z()));
        claims.add(cl);
        save();
        pos1.remove(p.getUniqueId());
        pos2.remove(p.getUniqueId());
        p.sendMessage("§aZone #" + cl.id() + " activée §7(" + (cl.maxX() - cl.minX() + 1) + " x "
                + (cl.maxZ() - cl.minZ() + 1) + " blocs, toute la hauteur).");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("chunker.admin") || args.length != 1) return out;
        for (String s : List.of("set", "remove", "list")) {
            if (s.startsWith(args[0].toLowerCase())) out.add(s);
        }
        return out;
    }
}
