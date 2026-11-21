package com.scripted.maces;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class Main extends JavaPlugin implements Listener, TabCompleter {

    private NamespacedKey maceTypeKey;

    // Tracks players recently hit by the Hell Mace: Victim UUID -> Expiration timestamp
    private final Map<UUID, Long> hellMarkedPlayers = new HashMap<>();
    // Tracks players actively vanishing so they stay hidden until respawn
    private final Set<UUID> vanishedVictims = new HashSet<>();
    // Tracks active Heaven Mace lightning strikes to protect the attacker
    private final Set<UUID> lightningProtected = new HashSet<>();

    @Override
    public void onEnable() {
        maceTypeKey = new NamespacedKey(this, "scripted_mace_type");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("scriptmace") != null) {
            getCommand("scriptmace").setTabCompleter(this);
        }
        startParticleTask();
    }

    // Creates strictly Material.MACE items
    public ItemStack createMace(String type) {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta == null) return mace;

        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(maceTypeKey, PersistentDataType.STRING, type.toLowerCase());

        if (type.equalsIgnoreCase("heaven")) {
            meta.displayName(Component.text("Heaven Mace", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("A holy mace obtained by proving", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("your worth in Heaven.", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Power: ", NamedTextColor.GRAY)
                            .append(Component.text("2x Damage + 3x Lightning Strike", NamedTextColor.AQUA))
                            .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.displayName(Component.text("Hell Mace", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("The deadly mace of Hell, forged", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("from the Devil's remains.", NamedTextColor.RED)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Power: ", NamedTextColor.GRAY)
                            .append(Component.text("3x Damage + Totem Pop Soul Claim", NamedTextColor.DARK_PURPLE))
                            .decoration(TextDecoration.ITALIC, false)
            ));
        }

        mace.setItemMeta(meta);
        return mace;
    }

    private String getMaceType(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(maceTypeKey, PersistentDataType.STRING);
    }

    // 1. Handle 2x / 3x Damage + Heaven Lightning + Hell Mark
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        String type = getMaceType(weapon);
        if (type == null) return;

        if (type.equals("heaven")) {
            // 2x normal mace damage
            event.setDamage(event.getDamage() * 2.0);

            // Protect attacker briefly from lightning self-damage
            UUID attackerId = attacker.getUniqueId();
            lightningProtected.add(attackerId);
            Bukkit.getScheduler().runTaskLater(this, () -> lightningProtected.remove(attackerId), 25L);

            // Spawn 3 lightning bolts spaced 3 ticks apart
            Location strikeLoc = victim.getLocation();
            for (int i = 0; i < 3; i++) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (victim.getWorld() != null) {
                        victim.getWorld().strikeLightning(victim.isValid() ? victim.getLocation() : strikeLoc);
                    }
                }, i * 3L);
            }
        } else if (type.equals("hell")) {
            // 3x normal mace damage
            event.setDamage(event.getDamage() * 3.0);

            // Mark victim for 5 seconds
            if (victim instanceof Player targetPlayer) {
                hellMarkedPlayers.put(targetPlayer.getUniqueId(), System.currentTimeMillis() + 5000L);
            }
        }
    }

    // Prevent Heaven Mace wielder from taking damage or catching fire from their own lightning
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLightningDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (lightningProtected.contains(player.getUniqueId())) {
            if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING
                    || event.getCause() == EntityDamageEvent.DamageCause.FIRE
                    || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK) {
                event.setCancelled(true);
                player.setFireTicks(0);
            }
        }
    }

    // 2. Detect Totem Pop on a Hell-Mace-marked player
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPop(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Long expiry = hellMarkedPlayers.get(victim.getUniqueId());
        if (expiry == null || System.currentTimeMillis() > expiry) return;

        hellMarkedPlayers.remove(victim.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> vanishAndKill(victim), 1L);
    }

    private void vanishAndKill(Player victim) {
        if (!victim.isOnline()) return;

        World world = victim.getWorld();
        Location loc = victim.getLocation().add(0, 1.0, 0);

        // Strip any backup totems so they cannot survive the execution
        for (int i = 0; i < victim.getInventory().getSize(); i++) {
            ItemStack item = victim.getInventory().getItem(i);
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                victim.getInventory().setItem(i, null);
            }
        }
        if (victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
            victim.getInventory().setItemInOffHand(null);
        }

        // Summon dark particles & eerie sound where they popped
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 35, 0.5, 0.8, 0.5, 0.05);
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 25, 0.5, 0.8, 0.5, 0.03);
        world.spawnParticle(Particle.REVERSE_PORTAL, loc, 40, 0.6, 0.9, 0.6, 0.08);
        world.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.2f, 0.5f);
        world.playSound(loc, Sound.PARTICLE_SOUL_ESCAPE, 2.0f, 0.7f);

        // Make the player disappear completely for everyone on the server
        vanishedVictims.add(victim.getUniqueId());
        victim.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 1, false, false, false));
        victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 1, false, false, false));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(victim)) {
                online.hidePlayer(this, victim);
            }
        }

        // Kill them 10 ticks (0.5s) later after they vanish into the particles
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (victim.isOnline() && !victim.isDead()) {
                victim.setHealth(0.0);
            }
            // Restore visibility right after death so they show normally on respawn
            Bukkit.getScheduler().runTaskLater(this, () -> restoreVisibility(victim), 5L);
        }, 10L);
    }

    private void restoreVisibility(Player victim) {
        if (vanishedVictims.remove(victim.getUniqueId())) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(victim)) {
                    online.showPlayer(this, victim);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hellMarkedPlayers.remove(id);
        restoreVisibility(event.getPlayer());
    }

    // Ambient particles while holding either mace
    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String type = getMaceType(p.getInventory().getItemInMainHand());
                    if ("heaven".equals(type)) {
                        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.0, 0), 3, 0.3, 0.4, 0.3, 0.01);
                    } else if ("hell".equals(type)) {
                        p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1.0, 0), 4, 0.3, 0.4, 0.3, 0.01);
                    }
                }
            }
        }.runTaskTimer(this, 10L, 10L);
    }

    // Admin command: /scriptmace give <heaven|hell> [player]
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("scriptedmaces.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /scriptmace give <heaven|hell> [player]", NamedTextColor.YELLOW));
            return true;
        }

        String type = args[1].toLowerCase();
        if (!type.equals("heaven") && !type.equals("hell")) {
            sender.sendMessage(Component.text("Mace type must be 'heaven' or 'hell'.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Specify a player from console.", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createMace(type));
        sender.sendMessage(Component.text("Gave " + type + " mace to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return List.of("heaven", "hell");
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return null;
        return Collections.emptyList();
    }
}
