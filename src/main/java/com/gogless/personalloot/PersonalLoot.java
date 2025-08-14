package com.gogless.personalloot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PersonalLoot extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<Material> supportedContainers = Set.of(Material.CHEST, Material.BARREL);
    private final Map<Location, LootTable> managedContainers = new ConcurrentHashMap<>();
    private final Map<Location, Map<UUID, Inventory>> personalInventories = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Location>> breakWarnings = new ConcurrentHashMap<>();
    private final Set<Location> currentlyProcessing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();
    private BukkitTask autoSaveTask;

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("personalchests").setExecutor(this);
        setupDataFile();
        loadData();

        autoSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveData, 12000L, 12000L);

        getLogger().info("PersonalLoot has been enabled with auto-saving!");
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveData();
        getLogger().info("PersonalLoot has been disabled and data has been saved.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("refill")) {
            if (!sender.hasPermission("personalloot.refill")) {
                sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
                return true;
            }

            sender.sendMessage(Component.text("Starting container refill process...", NamedTextColor.YELLOW));

            Map<Location, LootTable> containersToRefill = new HashMap<>(managedContainers);

            managedContainers.clear();
            personalInventories.clear();
            breakWarnings.clear();

            new BukkitRunnable() {
                private final Iterator<Map.Entry<Location, LootTable>> iterator = containersToRefill.entrySet().iterator();
                private int processedCount = 0;

                @Override
                public void run() {
                    int processedThisTick = 0;
                    while (iterator.hasNext() && processedThisTick < 200) {
                        Map.Entry<Location, LootTable> entry = iterator.next();
                        Location loc = entry.getKey();
                        LootTable table = entry.getValue();

                        if (loc.isWorldLoaded() && loc.getChunk().isLoaded()) {
                            BlockState state = loc.getBlock().getState();
                            if (state instanceof Lootable lootable && state instanceof Container container) {
                                lootable.setLootTable(table);
                                container.update();
                                processedThisTick++;
                                processedCount++;
                            }
                        }
                    }

                    if (!iterator.hasNext()) {
                        sender.sendMessage(Component.text("Container refill complete. Processed " + processedCount + " containers.", NamedTextColor.GREEN));
                        this.cancel();
                    }
                }
            }.runTaskTimer(this, 0L, 1L);

            return true;
        }
        return false;
    }

    @EventHandler
    public void onContainerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!supportedContainers.contains(block.getType())) {
            return;
        }

        Location location = block.getLocation();

        boolean isPristineLootContainer = block.getState() instanceof Lootable lootable && lootable.getLootTable() != null;
        boolean isManagedLootContainer = managedContainers.containsKey(location);

        if (!isPristineLootContainer && !isManagedLootContainer) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        if (breakWarnings.getOrDefault(playerUuid, new HashSet<>()).contains(location)) {
            breakWarnings.computeIfPresent(playerUuid, (k, v) -> { v.remove(location); return v; });

            if (isManagedLootContainer) {
                managedContainers.remove(location);
                personalInventories.remove(location);
            }
            return;
        }

        event.setCancelled(true);
        breakWarnings.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(location);
        player.sendMessage(Component.text(
                "Warning: This container holds loot for other players. Breaking it will destroy it permanently.",
                NamedTextColor.RED,
                TextDecoration.BOLD
        ));
    }


    @EventHandler
    public void onContainerOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        Block block = event.getClickedBlock();
        if (!supportedContainers.contains(block.getType()) || !(block.getState() instanceof Container container)) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Location location = container.getLocation();

        long now = System.currentTimeMillis();
        if (now - playerCooldowns.getOrDefault(playerUuid, 0L) < 500) {
            event.setCancelled(true);
            return;
        }

        if (currentlyProcessing.contains(location)) {
            event.setCancelled(true);
            return;
        }

        boolean isPristine = container instanceof Lootable lootable && lootable.getLootTable() != null;
        boolean isManaged = managedContainers.containsKey(location);

        if (isPristine || isManaged) {
            event.setCancelled(true);
            playerCooldowns.put(playerUuid, now);
            currentlyProcessing.add(location);

            if (isPristine) {
                Lootable lootable = (Lootable) container;
                LootTable originalLootTable = lootable.getLootTable();
                managedContainers.put(location, originalLootTable);
                getServer().getScheduler().runTask(this, () -> {
                    lootable.setLootTable(null);
                    container.update();
                });
                generateAndOpenPersonalLoot(player, location, originalLootTable);
            } else { // isManaged
                Map<UUID, Inventory> playerInventories = personalInventories.computeIfAbsent(location, k -> new ConcurrentHashMap<>());
                if (playerInventories.containsKey(player.getUniqueId())) {
                    player.openInventory(playerInventories.get(player.getUniqueId()));
                } else {
                    LootTable originalLootTable = managedContainers.get(location);
                    generateAndOpenPersonalLoot(player, location, originalLootTable);
                }
            }
            getServer().getScheduler().runTask(this, () -> currentlyProcessing.remove(location));
        }
    }

    @EventHandler
    public void onHopperMove(InventoryMoveItemEvent event) {
        Location sourceLocation = event.getSource().getLocation();
        if (sourceLocation == null) {
            return;
        }
        if (managedContainers.containsKey(sourceLocation)) {
            event.setCancelled(true);
        }
    }

    private void generateAndOpenPersonalLoot(Player player, Location location, LootTable lootTable) {
        if (lootTable == null) return;

        if (!(location.getBlock().getState() instanceof Container container)) {
            return;
        }

        Inventory personalInventory = getServer().createInventory(null, container.getInventory().getSize(), "Loot");

        LootContext.Builder builder = new LootContext.Builder(location);
        builder.lootedEntity(player);
        LootContext lootContext = builder.build();

        lootTable.fillInventory(personalInventory, null, lootContext);

        personalInventories.computeIfAbsent(location, k -> new ConcurrentHashMap<>()).put(player.getUniqueId(), personalInventory);

        player.openInventory(personalInventory);
    }

    private void setupDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml!");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private synchronized void saveData() {
        FileConfiguration newConfig = new YamlConfiguration();

        managedContainers.forEach((location, lootTable) -> {
            String path = "managed-containers." + locationToPath(location);
            newConfig.set(path, lootTable.getKey().toString());
        });

        personalInventories.forEach((location, playerInvs) -> {
            playerInvs.forEach((uuid, inventory) -> {
                String path = "personal-inventories." + locationToPath(location) + "." + uuid.toString();
                newConfig.set(path, inventory.getContents());
            });
        });

        try {
            newConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data to data.yml! " + e.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection managedSection = dataConfig.getConfigurationSection("managed-containers");
        if (managedSection != null) {
            for (String key : managedSection.getKeys(false)) {
                Location loc = pathTolocation(key);
                if (loc == null) continue;
                String lootTableKeyStr = managedSection.getString(key);
                if (lootTableKeyStr == null) continue;
                LootTable lootTable = Bukkit.getLootTable(NamespacedKey.fromString(lootTableKeyStr));
                if (lootTable != null) {
                    managedContainers.put(loc, lootTable);
                }
            }
        }

        ConfigurationSection personalInvSection = dataConfig.getConfigurationSection("personal-inventories");
        if (personalInvSection != null) {
            for (String locKey : personalInvSection.getKeys(false)) {
                Location loc = pathTolocation(locKey);
                if (loc == null || !(loc.getBlock().getState() instanceof Container)) continue;

                ConfigurationSection playerSection = personalInvSection.getConfigurationSection(locKey);
                if (playerSection == null) continue;

                Map<UUID, Inventory> playerInvs = new ConcurrentHashMap<>();
                for (String uuidKey : playerSection.getKeys(false)) {
                    UUID uuid = UUID.fromString(uuidKey);
                    List<?> items = playerSection.getList(uuidKey);
                    if (items == null) continue;

                    Inventory inv = Bukkit.createInventory(null, ((Container) loc.getBlock().getState()).getInventory().getSize(), "Loot");
                    inv.setContents(items.toArray(new ItemStack[0]));
                    playerInvs.put(uuid, inv);
                }
                personalInventories.put(loc, playerInvs);
            }
        }
    }

    private String locationToPath(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private Location pathTolocation(String path) {
        String[] parts = path.split(";");
        if (parts.length != 4) return null;
        return new Location(Bukkit.getWorld(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }
}

