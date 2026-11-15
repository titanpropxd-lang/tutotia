package es.xsinex.tutorial;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Zonas de recursos del tutorial (mina, bosque, huerta, taller). Se construyen solas,
 * dejan romper/plantar SOLO ahí, los drops van directos al inventario y todo se regenera.
 */
final class Recursos {
    static final class Zona {
        String id, tipo;
        int x, z, y, w, d;
        boolean dentro(Location l) {
            int bx = l.getBlockX(), bz = l.getBlockZ(), by = l.getBlockY();
            return bx >= x && bx < x + w && bz >= z && bz < z + d && by >= y - 1 && by <= y + 12;
        }
    }

    private final TutorialPlugin pl;
    private final Mundo mundo;
    private final List<Zona> zonas = new ArrayList<>();
    private final Map<Location, BlockData> pendientes = new HashMap<>();   // bloque -> estado original
    private final Map<Location, String> estaciones = new HashMap<>();      // bloque del taller -> estación de Forja
    private final Random rnd = new Random(7);

    Recursos(TutorialPlugin pl, Mundo mundo) { this.pl = pl; this.mundo = mundo; }

    boolean activo() { return pl.getConfig().getBoolean("recursos.activo", true); }

    void cargar() {
        zonas.clear();
        ConfigurationSection cs = pl.getConfig().getConfigurationSection("recursos.zonas");
        if (cs == null) return;
        for (String k : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(k);
            if (c == null) continue;
            Zona z = new Zona();
            z.id = k;
            z.tipo = c.getString("tipo", k).toLowerCase(Locale.ROOT);
            z.x = c.getInt("x"); z.z = c.getInt("z"); z.y = c.getInt("y", 74);
            z.w = c.getInt("w", 10); z.d = c.getInt("d", 8);
            zonas.add(z);
        }
    }

    Zona zonaDe(Location l) {
        for (Zona z : zonas) if (z.dentro(l)) return z;
        return null;
    }

    Location centro(String id) {
        World w = mundo.mundo();
        if (w == null) return null;
        for (Zona z : zonas) if (z.id.equalsIgnoreCase(id) || z.tipo.equalsIgnoreCase(id)) return new Location(w, z.x + z.w / 2.0, z.y + 1, z.z + z.d / 2.0);
        return null;
    }

    String estacionEn(Location l) { return estaciones.get(l.getBlock().getLocation()); }

    // ------------------------------------------------------------------ construcción
    void construir() {
        if (!activo()) return;
        World w = mundo.mundo();
        if (w == null) return;
        cargar();
        estaciones.clear();
        restaurarTodo();
        for (Zona z : zonas) {
            try {
                for (int cx = (z.x >> 4) - 1; cx <= ((z.x + z.w) >> 4) + 1; cx++)
                    for (int cz = (z.z >> 4) - 1; cz <= ((z.z + z.d) >> 4) + 1; cz++) w.getChunkAt(cx, cz).load(true);
                base(w, z);
                switch (z.tipo) {
                    case "mina": mina(w, z); break;
                    case "bosque": bosque(w, z); break;
                    case "huerta": huerta(w, z); break;
                    case "taller": taller(w, z); break;
                    default: break;
                }
            } catch (Throwable t) { pl.getLogger().warning("zona " + z.id + ": " + t); }
        }
        pl.getLogger().info("Zonas de recursos listas: " + zonas.size());
    }

    private void base(World w, Zona z) {
        for (int x = z.x - 1; x <= z.x + z.w; x++)
            for (int zz = z.z - 1; zz <= z.z + z.d; zz++) {
                for (int y = z.y - 4; y < z.y; y++) w.getBlockAt(x, y, zz).setType(Material.DIRT, false);
                w.getBlockAt(x, z.y, zz).setType(Material.GRASS_BLOCK, false);
                for (int y = z.y + 1; y <= z.y + 12; y++) w.getBlockAt(x, y, zz).setType(Material.AIR, false);
            }
    }

    private void mina(World w, Zona z) {
        for (int x = z.x + 1; x < z.x + z.w - 1; x++)
            for (int zz = z.z + 1; zz < z.z + z.d - 1; zz++) {
                int alto = 1 + (int) Math.round(2 * Math.sin((x - z.x) * 0.55) * Math.cos((zz - z.z) * 0.7) + 1.5);
                for (int h = 1; h <= alto; h++) {
                    double r = rnd.nextDouble();
                    Material m = r < 0.18 ? Material.COAL_ORE : r < 0.28 ? Material.IRON_ORE : r < 0.36 ? Material.COPPER_ORE : Material.STONE;
                    w.getBlockAt(x, z.y + h, zz).setType(m, false);
                }
            }
    }

    private void bosque(World w, Zona z) {
        int[][] p = { {2, 2}, {7, 2}, {2, 5}, {7, 5} };
        for (int[] t : p) {
            int bx = z.x + t[0], bz = z.z + t[1];
            for (int h = 1; h <= 5; h++) w.getBlockAt(bx, z.y + h, bz).setType(Material.OAK_LOG, false);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++) {
                if (Math.abs(dx) + Math.abs(dz) + (dy > 5 ? 2 : 0) > 3) continue;
                Block b = w.getBlockAt(bx + dx, z.y + dy, bz + dz);
                if (b.getType() == Material.AIR) {
                    b.setType(Material.OAK_LEAVES, false);
                    BlockData bd = b.getBlockData();
                    if (bd instanceof org.bukkit.block.data.type.Leaves) { ((org.bukkit.block.data.type.Leaves) bd).setPersistent(true); b.setBlockData(bd, false); }
                }
            }
        }
    }

    private void huerta(World w, Zona z) {
        for (int x = z.x + 1; x < z.x + z.w - 1; x++)
            for (int zz = z.z + 1; zz < z.z + z.d - 1; zz++) {
                int cz = zz - z.z;
                Block b = w.getBlockAt(x, z.y, zz);
                if (cz == 3 || cz == 4) { b.setType(Material.WATER, false); continue; }   // acequia central
                b.setType(Material.FARMLAND, false);
                BlockData bd = b.getBlockData();
                if (bd instanceof org.bukkit.block.data.type.Farmland) { ((org.bukkit.block.data.type.Farmland) bd).setMoisture(7); b.setBlockData(bd, false); }
                if ((x - z.x) % 2 == 0 && (cz == 2 || cz == 5)) {   // algunas matas ya crecidas para cosechar
                    Block c = w.getBlockAt(x, z.y + 1, zz);
                    c.setType(cz == 2 ? Material.WHEAT : Material.CARROTS, false);
                    BlockData cd = c.getBlockData();
                    if (cd instanceof Ageable) { ((Ageable) cd).setAge(((Ageable) cd).getMaximumAge()); c.setBlockData(cd, false); }
                }
            }
    }

    private void taller(World w, Zona z) {
        for (int x = z.x + 1; x < z.x + z.w - 1; x++) for (int zz = z.z + 1; zz < z.z + z.d - 1; zz++) w.getBlockAt(x, z.y, zz).setType(Material.STONE_BRICKS, false);
        String[][] est = { {"anvil", "yunque"}, {"smithing_table", "armeria"}, {"blast_furnace", "forja"}, {"crafting_table", "mesa"}, {"grindstone", "afilado"}, {"smoker", "cocina"} };
        List<String> cfg = pl.getConfig().getStringList("recursos.estaciones");   // "material:estacion"
        List<String[]> lista = new ArrayList<>();
        if (cfg.isEmpty()) for (String[] e : est) lista.add(e);
        else for (String s : cfg) { String[] q = s.split(":"); if (q.length == 2) lista.add(q); }
        int i = 0;
        for (String[] e : lista) {
            Material m = Material.matchMaterial(e[0].toUpperCase(Locale.ROOT));
            if (m == null) continue;
            int bx = z.x + 1 + (i % 4) * 2, bz = z.z + 2 + (i / 4) * 3;
            Block b = w.getBlockAt(bx, z.y + 1, bz);
            b.setType(m, false);
            estaciones.put(b.getLocation(), e[1].toLowerCase(Locale.ROOT));
            i++;
        }
    }

    // ------------------------------------------------------------------ eventos
    private boolean romperOk(Zona z, Material m) {
        String n = m.name();
        switch (z.tipo) {
            case "mina": return n.equals("STONE") || n.endsWith("_ORE");
            case "bosque": return n.equals("OAK_LOG") || n.equals("OAK_LEAVES");
            case "huerta": return n.equals("WHEAT") || n.equals("CARROTS") || n.equals("POTATOES") || n.equals("BEETROOTS");
            default: return false;
        }
    }

    /** Devuelve true si el evento queda gestionado (permitido). */
    boolean romper(BlockBreakEvent ev) {
        if (!activo()) return false;
        Block b = ev.getBlock();
        Zona z = zonaDe(b.getLocation());
        if (z == null || !romperOk(z, b.getType())) return false;
        Player p = ev.getPlayer();
        Location key = b.getLocation();
        if (!pendientes.containsKey(key)) pendientes.put(key, b.getBlockData());
        BlockData orig = pendientes.get(key);
        ev.setDropItems(false);
        ev.setExpToDrop(0);
        ItemStack herr = new ItemStack(Material.IRON_PICKAXE);
        java.util.Collection<ItemStack> drops = b.getDrops(z.tipo.equals("mina") ? herr : p.getInventory().getItemInMainHand());
        for (ItemStack it : drops) {
            Map<Integer, ItemStack> sobra = p.getInventory().addItem(it);
            for (ItemStack s : sobra.values()) p.getWorld().dropItemNaturally(p.getLocation(), s);
        }
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.2f);
        programar(key, orig, pl.getConfig().getInt("recursos.regeneracion", 20));
        return true;
    }

    boolean colocar(BlockPlaceEvent ev) {
        if (!activo()) return false;
        Block b = ev.getBlockPlaced();
        Zona z = zonaDe(b.getLocation());
        if (z == null || !z.tipo.equals("huerta")) return false;
        String n = b.getType().name();
        if (!(n.equals("WHEAT") || n.equals("CARROTS") || n.equals("POTATOES") || n.equals("BEETROOTS"))) return false;
        Location key = b.getLocation();
        pendientes.put(key, org.bukkit.Bukkit.createBlockData(Material.AIR));
        programar(key, Bukkit.createBlockData(Material.AIR), pl.getConfig().getInt("recursos.regeneracion-cultivos", 60));
        return true;
    }

    private void programar(Location key, BlockData orig, int seg) {
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            Block b = key.getBlock();
            if (pendientes.remove(key) == null) return;
            b.setBlockData(orig, false);
        }, Math.max(20L, seg * 20L));
    }

    void restaurarTodo() {
        for (Map.Entry<Location, BlockData> e : new ArrayList<>(pendientes.entrySet())) e.getKey().getBlock().setBlockData(e.getValue(), false);
        pendientes.clear();
    }
}
