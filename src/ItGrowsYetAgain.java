// Compile: javac ItGrowsYetAgain.java
// Run:     java ItGrowsYetAgain
// Java 11+ recommended

import javax.swing.*;
import javax.swing.Timer;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Main entry: a tiny, modular farm sim with two drone types.
 * - SeederDrone: plants on empty field cells; refills & rests at SEEDER_REST.
 * - HarvesterDrone: harvests ripe plants; unloads & rests at STORAGE.
 */
public class ItGrowsYetAgain extends JPanel implements ActionListener {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	// =========================
    //         CONFIG
    // =========================
    public static final class GameConfig {
        // --- Grid / world ---
        public static int TILE_SIZE = 32;
        public static int GRID_COLS = 20;    // configurable
        public static int GRID_ROWS = 12;    // configurable

        // Designate special tiles (within bounds). You can change these.
        public static Point SEEDER_REST_POS = new Point(1, 1);
        public static Point STORAGE_POS     = new Point(18, 10);

        // --- Game loop / render ---
        public static int TARGET_FPS = 60;
        public static boolean SHOW_DEBUG = true;
        public static boolean DRAW_GRID_LINES = true;

        // --- Plant / growth ---
        // Number of growth stages: 0..MAX_STAGE-1
        public static int PLANT_GROWTH_STAGES = 4; // seed(0), growing(1), mature(2), ripe(3)
        // Stage durations (seconds) OR leave null to use randomized per-plant within min/max below.
        public static double[] DEFAULT_STAGE_SECONDS = null;
        // Random growth per stage range (seconds):
        public static double STAGE_SECONDS_MIN = 2.5;
        public static double STAGE_SECONDS_MAX = 6.0;

        // --- Seeder drone ---
        public static int SEEDER_COUNT = 5;
        public static int SEEDER_CAPACITY = 5;
        public static double SEEDER_SPEED_TILES_PER_SEC = 4.0;

        // --- Harvester drone ---
        public static int HARVESTER_COUNT = 3;
        public static int HARVEST_CAPACITY = 5; // items before returning to storage to unload
        public static double HARVESTER_SPEED_TILES_PER_SEC = 4.2;

        // --- Misc balancing ---
        public static boolean ALLOW_DIAGONALS = false; // movement is grid-step (4-way)
        public static int RANDOM_SEED = 42;            // set to -1 to use true random

        // --- Assets ---
        public static String ASSETS_DIR = "assets";
        public static String PLANTS_DIR = ASSETS_DIR + File.separator + "plants";
        public static String DRONES_DIR = ASSETS_DIR + File.separator + "drones";
        public static String TILES_DIR  = ASSETS_DIR + File.separator + "tiles";

        public static String[] PLANT_STAGE_FILES = {
                "plant_stage_0.png", // seed
                "plant_stage_1.png", // growing
                "plant_stage_2.png", // mature
                "plant_stage_3.png"  // ripe
        };
        public static String SEEDER_FILE = "seeder.png";
        public static String HARVESTER_FILE = "harvester.png";
        public static String FIELD_FILE = "field.png";
        public static String SEEDER_REST_FILE = "seeder_rest.png";
        public static String STORAGE_FILE = "storage.png";

        // Colors used when an expected asset is missing:
        public static Color COLOR_FIELD         = new Color(120, 90, 60);
        public static Color COLOR_SEEDER_REST   = new Color(70, 130, 180);
        public static Color COLOR_STORAGE       = new Color(100, 100, 160);
        public static Color COLOR_SEEDER        = new Color(50, 220, 80);
        public static Color COLOR_HARVESTER     = new Color(220, 70, 70);
        public static Color[] COLOR_PLANT_STAGE = new Color[] {
            new Color(200, 180, 120),
            new Color(90, 170, 90),
            new Color(50, 120, 50),
            new Color(60, 200, 60)
        };

        // UI scaling & font
        public static Font HUD_FONT = new Font("Consolas", Font.PLAIN, 13);

        private GameConfig() {}
    }

    // =========================
    //       ASSET MANAGER
    // =========================
    public static final class AssetManager {
        private final Map<String, Image> cache = new HashMap<>();

        private Image loadOrSolid(String path, Color fallback) {
            if (cache.containsKey(path)) return cache.get(path);
            Image img = null;
            File f = new File(path);
            if (f.exists()) {
                try {
                    img = ImageIO.read(f);
                } catch (IOException e) {
                    img = solid(fallback);
                }
            } else {
                img = solid(fallback);
            }
            cache.put(path, img);
            return img;
        }

        private Image solid(Color c) {
            int s = GameConfig.TILE_SIZE;
            BufferedImage bi = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(c);
            g.fillRect(0, 0, s, s);
            g.setColor(new Color(0, 0, 0, 40));
            g.drawRect(0, 0, s - 1, s - 1);
            g.dispose();
            return bi;
        }

        public Image tileField() {
            return loadOrSolid(GameConfig.TILES_DIR + File.separator + GameConfig.FIELD_FILE, GameConfig.COLOR_FIELD);
        }
        public Image tileSeederRest() {
            return loadOrSolid(GameConfig.TILES_DIR + File.separator + GameConfig.SEEDER_REST_FILE, GameConfig.COLOR_SEEDER_REST);
        }
        public Image tileStorage() {
            return loadOrSolid(GameConfig.TILES_DIR + File.separator + GameConfig.STORAGE_FILE, GameConfig.COLOR_STORAGE);
        }

        public Image plantStage(int stage) {
            stage = Math.max(0, Math.min(stage, GameConfig.PLANT_GROWTH_STAGES - 1));
            String fname = stage < GameConfig.PLANT_STAGE_FILES.length
                    ? GameConfig.PLANT_STAGE_FILES[stage]
                    : "plant_stage_" + stage + ".png";
            String path = GameConfig.PLANTS_DIR + File.separator + fname;
            Color color = stage < GameConfig.COLOR_PLANT_STAGE.length
                    ? GameConfig.COLOR_PLANT_STAGE[stage]
                    : new Color(80 + 10 * stage % 175, 160, 80);
            return loadOrSolid(path, color);
        }

        public Image seeder() {
            return loadOrSolid(GameConfig.DRONES_DIR + File.separator + GameConfig.SEEDER_FILE, GameConfig.COLOR_SEEDER);
        }
        public Image harvester() {
            return loadOrSolid(GameConfig.DRONES_DIR + File.separator + GameConfig.HARVESTER_FILE, GameConfig.COLOR_HARVESTER);
        }
    }

    // =========================
    //         WORLD
    // =========================
    enum TileType { FIELD, SEEDER_REST, STORAGE }

    static final class Cell {
        final int cx, cy;
        TileType type = TileType.FIELD;
        Plant plant = null;
        Cell(int cx, int cy) { this.cx = cx; this.cy = cy; }

        boolean isEmptyField() { return type == TileType.FIELD && plant == null; }
        boolean hasRipePlant() { return type == TileType.FIELD && plant != null && plant.isRipe(); }
    }

    static final class World {
        final int cols, rows;
        final Cell[][] cells;

        World(int cols, int rows) {
            this.cols = cols; this.rows = rows;
            cells = new Cell[rows][cols];
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    cells[y][x] = new Cell(x, y);
                }
            }
            get(GameConfig.SEEDER_REST_POS).type = TileType.SEEDER_REST;
            get(GameConfig.STORAGE_POS).type = TileType.STORAGE;
        }

        boolean inBounds(int x, int y) { return x >= 0 && x < cols && y >= 0 && y < rows; }
        Cell get(Point p) { return get(p.x, p.y); }
        Cell get(int x, int y) { return inBounds(x, y) ? cells[y][x] : null; }

        List<Cell> allCells() {
            List<Cell> out = new ArrayList<>();
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) out.add(cells[y][x]);
            return out;
        }

        Cell nearest(Cell from, java.util.function.Predicate<Cell> pred) {
            boolean[][] vis = new boolean[rows][cols];
            ArrayDeque<Point> q = new ArrayDeque<>();
            q.add(new Point(from.cx, from.cy));
            vis[from.cy][from.cx] = true;
            while (!q.isEmpty()) {
                Point p = q.removeFirst();
                Cell c = get(p.x, p.y);
                if (pred.test(c)) return c;
                int[][] dirs = GameConfig.ALLOW_DIAGONALS
                        ? new int[][] {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}
                        : new int[][] {{1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    int nx = p.x + d[0], ny = p.y + d[1];
                    if (inBounds(nx, ny) && !vis[ny][nx]) {
                        vis[ny][nx] = true;
                        q.add(new Point(nx, ny));
                    }
                }
            }
            return null;
        }
    }

    // =========================
    //         PLANTS
    // =========================
    static final class PlantType {
        final String id;
        final int stages;
        final double[] stageSeconds;
        PlantType(String id, int stages, double[] stageSeconds) {
            this.id = id;
            this.stages = stages;
            this.stageSeconds = stageSeconds;
        }
    }

    static final class Plant {
        final PlantType type;
        int stage;
        double timer;
        double[] stageSeconds;
        Plant(PlantType type, double[] stageSeconds) {
            this.type = type;
            this.stage = 0;
            this.timer = 0.0;
            this.stageSeconds = stageSeconds;
        }

        boolean isRipe() { return stage >= type.stages - 1; }

        void update(double dt) {
            if (isRipe()) return;
            timer += dt;
            if (timer >= stageSeconds[stage]) {
                timer -= stageSeconds[stage];
                stage = Math.min(stage + 1, type.stages - 1);
            }
        }
    }

    // =========================
    //         ENTITIES
    // =========================
    interface Updatable { void update(double dt); }
    interface Renderable { void render(Graphics2D g); }

    static abstract class Entity implements Updatable, Renderable {
        double x, y;
        abstract String debug();
    }

    static abstract class Drone extends Entity {
        final World world;
        final AssetManager assets;
        double speedPxPerSec;
        int targetCx = -1, targetCy = -1;
        boolean busy = false;

        Drone(World world, AssetManager assets, double speedTilesPerSec) {
            this.world = world;
            this.assets = assets;
            this.speedPxPerSec = speedTilesPerSec * GameConfig.TILE_SIZE;
        }

        Cell cell() { return world.get((int)(x / GameConfig.TILE_SIZE), (int)(y / GameConfig.TILE_SIZE)); }

        void setToCellCenter(int cx, int cy) {
            this.x = cx * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
            this.y = cy * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
        }

        boolean atCellCenter(int cx, int cy) {
            double tx = cx * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
            double ty = cy * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
            double dx = tx - x, dy = ty - y;
            double dist2 = dx*dx + dy*dy;
            return dist2 < 1.0;
        }

        void moveTowardsCell(int cx, int cy, double dt) {
            double tx = cx * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
            double ty = cy * GameConfig.TILE_SIZE + GameConfig.TILE_SIZE / 2.0;
            double dx = tx - x, dy = ty - y;
            double len = Math.sqrt(dx*dx + dy*dy);
            if (len < 1e-6) return;
            double step = speedPxPerSec * dt;
            if (step >= len) {
                x = tx; y = ty;
            } else {
                x += dx / len * step;
                y += dy / len * step;
            }
        }

        @Override public void render(Graphics2D g) { /* subclasses */ }
        @Override public void update(double dt) { /* subclasses */ }

        abstract void think();
    }

    static final class SeederDrone extends Drone {
        int seeds;
        final int capacity;
        Image sprite;

        SeederDrone(World w, AssetManager a, int capacity, double speedTilesPerSec) {
            super(w, a, speedTilesPerSec);
            this.capacity = capacity;
            this.seeds = capacity;
            this.sprite = a.seeder();
        }

        @Override
        void think() {
            Cell myCell = cell();
            // 1) If out of seeds -> go to SEEDER_REST to refill
            if (seeds <= 0) {
                targetCx = GameConfig.SEEDER_REST_POS.x;
                targetCy = GameConfig.SEEDER_REST_POS.y;
                return;
            }
            // 2) Find nearest empty field to plant
            Cell target = world.nearest(myCell, Cell::isEmptyField);
            if (target != null) {
                targetCx = target.cx; targetCy = target.cy;
            } else {
                // 3) No empty fields, go rest at SEEDER_REST
                targetCx = GameConfig.SEEDER_REST_POS.x;
                targetCy = GameConfig.SEEDER_REST_POS.y;
            }
        }

        @Override
        public void update(double dt) {
            if (targetCx < 0) think();

            moveTowardsCell(targetCx, targetCy, dt);

            if (atCellCenter(targetCx, targetCy)) {
                Cell at = world.get(targetCx, targetCy);
                if (at.type == TileType.SEEDER_REST) {
                    // 1.Refill and wait for next instruction
                    seeds = capacity;
                    // 2.re-think to possibly leave rest if there are empty fields
                    think();
                } else if (at.isEmptyField()) {
                    // 3.Plant seed here
                    if (seeds > 0) {
                        at.plant = new Plant(defaultPlantType(), randomStageDurations());
                        seeds--;
                    }
                    // 4.think next target
                    think();
                } else {
                    // 5.If reached a non-empty field (race condition), retarget
                    think();
                }
            }
        }

        @Override
        public void render(Graphics2D g) {
            int s = GameConfig.TILE_SIZE;
            g.drawImage(sprite, (int)(x - s/2), (int)(y - s/2), s, s, null);
        }

        @Override
        String debug() {
            return "Seeder seeds=" + seeds + "/" + capacity + " target=(" + targetCx + "," + targetCy + ")";
        }
    }

    static final class HarvesterDrone extends Drone {
        int cargo = 0;
        final int capacity;
        Image sprite;

        HarvesterDrone(World w, AssetManager a, int capacity, double speedTilesPerSec) {
            super(w, a, speedTilesPerSec);
            this.capacity = capacity;
            this.sprite = a.harvester();
        }

        @Override
        void think() {
            Cell myCell = cell();
            // 1.If cargo full -> go to STORAGE to unload
            if (cargo >= capacity) {
                targetCx = GameConfig.STORAGE_POS.x;
                targetCy = GameConfig.STORAGE_POS.y;
                return;
            }
            // 2.Find nearest ripe plant
            Cell target = world.nearest(myCell, Cell::hasRipePlant);
            if (target != null) {
                targetCx = target.cx; targetCy = target.cy;
            } else {
                // 3.No ripe plants -> return to storage (rest)
                targetCx = GameConfig.STORAGE_POS.x;
                targetCy = GameConfig.STORAGE_POS.y;
            }
        }

        @Override
        public void update(double dt) {
            if (targetCx < 0) think();

            moveTowardsCell(targetCx, targetCy, dt);

            if (atCellCenter(targetCx, targetCy)) {
                Cell at = world.get(targetCx, targetCy);
                if (at.type == TileType.STORAGE) {
                    // 1.Unload cargo
                    if (cargo > 0) {
                        // 2.Here you could increase a global inventory; for demo we just drop it.
                        cargo = 0;
                    }
                    think();
                } else if (at.hasRipePlant()) {
                    // 3.Harvest plant (one unit per plant)
                    at.plant = null;
                    cargo++;
                    think();
                } else {
                    think();
                }
            }
        }

        @Override
        public void render(Graphics2D g) {
            int s = GameConfig.TILE_SIZE;
            g.drawImage(sprite, (int)(x - s/2), (int)(y - s/2), s, s, null);
        }

        @Override
        String debug() {
            return "Harvester cargo=" + cargo + "/" + capacity + " target=(" + targetCx + "," + targetCy + ")";
        }
    }

    // =========================
    //       GAME STATE
    // =========================
    private final World world;
    private final AssetManager assets;
    private final java.util.List<Drone> drones = new ArrayList<>();
    private final java.util.List<Plant> plants = new ArrayList<>();
    private static Random rng = new Random();

    private final Timer timer;
    private long lastNanos;

    private int totalHarvested = 0;

    public ItGrowsYetAgain() {
        setPreferredSize(new Dimension(GameConfig.GRID_COLS * GameConfig.TILE_SIZE, GameConfig.GRID_ROWS * GameConfig.TILE_SIZE));
        setBackground(Color.black);
        setDoubleBuffered(true);

        if (GameConfig.RANDOM_SEED >= 0) {
            rng = new Random(GameConfig.RANDOM_SEED);
        } else {
            rng = new Random();
        }

        assets = new AssetManager();
        world = new World(GameConfig.GRID_COLS, GameConfig.GRID_ROWS);

        // Spawn drones at their rest areas
        for (int i = 0; i < GameConfig.SEEDER_COUNT; i++) {
            SeederDrone sd = new SeederDrone(world, assets, GameConfig.SEEDER_CAPACITY, GameConfig.SEEDER_SPEED_TILES_PER_SEC);
            sd.setToCellCenter(GameConfig.SEEDER_REST_POS.x, GameConfig.SEEDER_REST_POS.y);
            drones.add(sd);
        }
        for (int i = 0; i < GameConfig.HARVESTER_COUNT; i++) {
            HarvesterDrone hd = new HarvesterDrone(world, assets, GameConfig.HARVEST_CAPACITY, GameConfig.HARVESTER_SPEED_TILES_PER_SEC);
            hd.setToCellCenter(GameConfig.STORAGE_POS.x, GameConfig.STORAGE_POS.y);
            drones.add(hd);
        }

        // Simple bookkeeping: count harvests
        addHarvestListener();

        // Input: R to randomize plant growth; G to toggle grid; D to toggle debug
        setupKeybinds();

        int delayMs = Math.max(5, 1000 / GameConfig.TARGET_FPS);
        timer = new Timer(delayMs, this);
    }

    private void setupKeybinds() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "random-seed");
        getActionMap().put("random-seed", new AbstractAction() {
            /**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			@Override public void actionPerformed(ActionEvent e) {
                // debug: randomly place some seeds right now
                sprinkleSeeds(10);
            }
        });

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "toggle-grid");
        getActionMap().put("toggle-grid", new AbstractAction() {
            /**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			@Override public void actionPerformed(ActionEvent e) {
                GameConfig.DRAW_GRID_LINES = !GameConfig.DRAW_GRID_LINES;
            }
        });

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), "toggle-debug");
        getActionMap().put("toggle-debug", new AbstractAction() {
            /**
			 * 
			 */
			private static final long serialVersionUID = 1L;

			@Override public void actionPerformed(ActionEvent e) {
                GameConfig.SHOW_DEBUG = !GameConfig.SHOW_DEBUG;
            }
        });
    }

    private void sprinkleSeeds(int count) {
        for (int i = 0; i < count; i++) {
            int tries = 200;
            while (tries-- > 0) {
                int x = rng.nextInt(world.cols);
                int y = rng.nextInt(world.rows);
                Cell c = world.get(x, y);
                if (c.type == TileType.FIELD && c.plant == null) {
                    c.plant = new Plant(defaultPlantType(), randomStageDurations());
                    break;
                }
            }
        }
    }

    private void addHarvestListener() {
        // Hook harvester events if desired; here we detect harvests in render loop by counting diff.
        // Simpler approach: intercept in HarvesterDrone; for demo we skip a full event bus to keep file single.
        // We'll count during update: when a ripe plant disappears, we bump totalHarvested via a scan.
    }

    public void start() {
        lastNanos = System.nanoTime();
        timer.start();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        // Update plants
        for (Cell c : world.allCells()) {
            if (c.plant != null) c.plant.update(dt);
        }

        // Count harvested by comparing ripe count drop? (Optional; here we just track storage unloads if implemented)
        // Update drones
        for (Drone d : drones) d.update(dt);

        repaint();
    }

    // =========================
    //        RENDERING
    // =========================
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // Draw tiles
        for (int y = 0; y < world.rows; y++) {
            for (int x = 0; x < world.cols; x++) {
                Cell c = world.get(x, y);
                Image tile;
                switch (c.type) {
                    case SEEDER_REST: tile = assets.tileSeederRest(); break;
                    case STORAGE:     tile = assets.tileStorage(); break;
                    default:          tile = assets.tileField();
                }
                g.drawImage(tile, x * GameConfig.TILE_SIZE, y * GameConfig.TILE_SIZE, GameConfig.TILE_SIZE, GameConfig.TILE_SIZE, null);

                if (c.plant != null) {
                    Image plantImg = assets.plantStage(c.plant.stage);
                    g.drawImage(plantImg, x * GameConfig.TILE_SIZE, y * GameConfig.TILE_SIZE, GameConfig.TILE_SIZE, GameConfig.TILE_SIZE, null);
                }
            }
        }

        // Grid lines
        if (GameConfig.DRAW_GRID_LINES) {
            g.setColor(new Color(255,255,255,30));
            for (int x = 0; x <= world.cols; x++) {
                int px = x * GameConfig.TILE_SIZE;
                g.drawLine(px, 0, px, world.rows * GameConfig.TILE_SIZE);
            }
            for (int y = 0; y <= world.rows; y++) {
                int py = y * GameConfig.TILE_SIZE;
                g.drawLine(0, py, world.cols * GameConfig.TILE_SIZE, py);
            }
        }

        // Drones on top
        for (Drone d : drones) d.render(g);

        // HUD
        g.setFont(GameConfig.HUD_FONT);
        g.setColor(new Color(255,255,255,220));
        int line = 1;
        int margin = 6;
        g.drawString("ItGrowsYetAgain — R: sprinkle seeds | G: grid | D: debug", margin, line++ * 16);
        g.drawString("Seeders=" + GameConfig.SEEDER_COUNT + "  Harvesters=" + GameConfig.HARVESTER_COUNT, margin, line++ * 16);

        if (GameConfig.SHOW_DEBUG) {
            for (Drone d : drones) {
                String s = d.debug();
                g.drawString(s, margin, line++ * 16);
            }
        }
    }

    // =========================
    //      HELPERS / FACTORY
    // =========================
    private static PlantType defaultPlantType() {
        int stages = GameConfig.PLANT_GROWTH_STAGES;
        double[] baseDur = GameConfig.DEFAULT_STAGE_SECONDS;
        if (baseDur != null && baseDur.length >= stages) {
            return new PlantType("basic", stages, baseDur);
        } else {
            // Use placeholder; instance will copy randomized durations
            double[] placeholder = new double[stages];
            Arrays.fill(placeholder, 1.0);
            return new PlantType("basic", stages, placeholder);
        }
    }

    private static double[] randomStageDurations() {
        int stages = GameConfig.PLANT_GROWTH_STAGES;
        double[] out;
        if (GameConfig.DEFAULT_STAGE_SECONDS != null && GameConfig.DEFAULT_STAGE_SECONDS.length >= stages) {
            out = Arrays.copyOf(GameConfig.DEFAULT_STAGE_SECONDS, stages);
        } else {
            out = new double[stages];
            for (int i = 0; i < stages; i++) {
                out[i] = lerp(GameConfig.STAGE_SECONDS_MIN, GameConfig.STAGE_SECONDS_MAX, rng.nextDouble());
            }
        }
        return out;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // =========================
    //           MAIN
    // =========================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("It Grows Yet Again");
            ItGrowsYetAgain panel = new ItGrowsYetAgain();
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            panel.start();
        });
    }
}
