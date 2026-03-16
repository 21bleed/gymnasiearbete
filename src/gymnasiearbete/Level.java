package gymnasiearbete;

import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Minimal tile-based level used for collision checks by entities.
 * This version supports expanding the level horizontally (appending columns)
 * so the game can generate effectively infinite levels.
 */
public class Level {

    // global seed (string form). If set, Level instances will use it to derive their RNG seed.
    private static String globalSeed = null;

    public static void setGlobalSeed(String s) { globalSeed = s; }
    public static String getGlobalSeed() { return globalSeed; }

    public final int tileSize;
    public int width;  // in pixels
    public int height; // in pixels

    private boolean[][] solid; // [rows][cols]
    private int[][] tiles = null; // optional tile id map [rows][cols]
    private int[][] colorTokens = null; // optional color/token map used for solidity rules
    private final List<Entity> obstacles = new ArrayList<>();
    private final Random rnd;

    public Level(int tilesX, int tilesY, int tileSize) {
        // derive seed value from globalSeed if available; allow numeric 10-digit string
        long seedVal = 12345L;
        if (globalSeed != null) {
            String gs = globalSeed.trim();
            try {
                // prefer numeric seed when given (up to 18 digits to fit long)
                if (gs.matches("^-?\\d{1,18}$")) seedVal = Long.parseLong(gs);
                else seedVal = gs.hashCode();
            } catch (Exception ignored) { seedVal = gs.hashCode(); }
        }
        this.rnd = new Random(seedVal);
        this.tileSize = tileSize;
        this.width = tilesX * tileSize;
        this.height = tilesY * tileSize;
        this.solid = new boolean[tilesY][tilesX];

        // Default: no solid tiles except bottom row = ground
        int groundRow = tilesY - 1;
        for (int c = 0; c < tilesX; c++) {
            solid[groundRow][c] = true;
        }

        // Add a few sample platforms
        if (tilesX > 6 && tilesY > 6) {
            int r = groundRow - 3;
            for (int c = 3; c < 7; c++) solid[r][c] = true;
            r = groundRow - 6;
            for (int c = 10; c < 14; c++) solid[r][c] = true;
        }
    }

    public void addObstacle(Entity e) {
        if (e != null) obstacles.add(e);
    }

    public List<Entity> getObstacles() { return obstacles; }

    public boolean collidesAt(double x, double y, int w, int h) {
        if (w <= 0 || h <= 0) return false;

        int left = (int) Math.floor(x / tileSize);
        int right = (int) Math.floor((x + w - 1) / tileSize);
        int top = (int) Math.floor(y / tileSize);
        int bottom = (int) Math.floor((y + h - 1) / tileSize);

        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(solid[0].length - 1, right);
        bottom = Math.min(solid.length - 1, bottom);

        for (int row = top; row <= bottom; row++) {
            for (int col = left; col <= right; col++) {
                if (solid[row][col]) return true;
            }
        }

        // Check obstacle entities with precise double AABB overlap (avoid rounding jitter)
        double ax1 = x, ay1 = y, ax2 = x + w, ay2 = y + h;
        for (Entity e : obstacles) {
            if (!e.getActive()) continue;
            double ex = e.xPos;
            double ey = e.yPos;
            // prefer entity's hitbox if provided
            double exHb = e.getHitboxX();
            double eyHb = e.getHitboxY();
            double ew = e.getHitboxWidth();
            double eh = e.getHitboxHeight();
            // if hitbox values are non-positive (shouldn't happen thanks to getters), fallback to visual
            if (ew <= 0) {
                if (e instanceof playerEntity) ew = ((playerEntity)e).getWidth();
                else if (e instanceof EnemyEntity) ew = ((EnemyEntity)e).getWidth();
                else if (e instanceof ObstacleEntity) ew = ((ObstacleEntity)e).getWidth();
                else ew = 32;
            }
            if (eh <= 0) {
                if (e instanceof playerEntity) eh = ((playerEntity)e).getHeight();
                else if (e instanceof EnemyEntity) eh = ((EnemyEntity)e).getHeight();
                else if (e instanceof ObstacleEntity) eh = ((ObstacleEntity)e).getHeight();
                else eh = 32;
            }
            double bx1 = exHb, by1 = eyHb, bx2 = exHb + ew, by2 = eyHb + eh;
            if (ax1 < bx2 && ax2 > bx1 && ay1 < by2 && ay2 > by1) return true;
        }

        return false;
    }

    private static boolean rectsOverlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    public void setTileSolid(int tileX, int tileY, boolean solidTile) {
        if (tileY < 0 || tileY >= this.solid.length) return;
        if (tileX < 0 || tileX >= this.solid[0].length) return;
        this.solid[tileY][tileX] = solidTile;
    }

    public boolean isTileSolid(int row, int col) {
        if (row < 0 || row >= solid.length) return false;
        if (col < 0 || col >= solid[0].length) return false;
        return solid[row][col];
    }

    /**
     * Ensure the level has at least the given pixel width. If not, append columns
     * to the right and generate simple platforms/holes/obstacles.
     */
    public void ensureWidthPixels(int minPixels) {
        int minCols = (int) Math.ceil((double)minPixels / tileSize);
        int currentCols = solid[0].length;
        if (minCols <= currentCols) return;
        int addCols = minCols - currentCols;
        appendColumns(addCols);
    }

    /** Append columns to the right and procedurally generate simple features. */
    private void appendColumns(int colsToAdd) {
        int rows = solid.length;
        int cols = solid[0].length;
        int newCols = cols + colsToAdd;
        boolean[][] nsolid = new boolean[rows][newCols];
        int[][] ntiles = null;
        int[][] ntokens = null;
        if (tiles != null) {
            ntiles = new int[rows][newCols];
            for (int r = 0; r < rows; r++) for (int c = 0; c < newCols; c++) ntiles[r][c] = -1;
        }
        if (colorTokens != null) {
            ntokens = new int[rows][newCols];
            for (int r = 0; r < rows; r++) for (int c = 0; c < newCols; c++) ntokens[r][c] = -1;
        }
        // copy existing
        for (int r = 0; r < rows; r++) {
            System.arraycopy(solid[r], 0, nsolid[r], 0, cols);
            if (ntiles != null) System.arraycopy(tiles[r], 0, ntiles[r], 0, cols);
            if (ntokens != null) System.arraycopy(colorTokens[r], 0, ntokens[r], 0, cols);
        }
        // generate new columns
        int groundRow = rows - 1;
        for (int c = cols; c < newCols; c++) {
            // default: empty
            for (int r = 0; r < rows; r++) nsolid[r][c] = false;
            if (ntiles != null) for (int r = 0; r < rows; r++) ntiles[r][c] = -1;
            if (ntokens != null) for (int r = 0; r < rows; r++) ntokens[r][c] = -1;
            // ground with occasional holes
            boolean hole = rnd.nextDouble() < 0.06; // 6% hole
            if (!hole) {
                nsolid[groundRow][c] = true;
                if (ntiles != null) ntiles[groundRow][c] = 0; // ground tile id
            }
            // occasional floating platforms
            if (rnd.nextDouble() < 0.10) {
                int platRow = groundRow - 2 - rnd.nextInt(Math.max(1, rows - 4));
                if (platRow >= 0 && platRow < groundRow) nsolid[platRow][c] = true;
                if (ntiles != null) ntiles[platRow][c] = 1; // platform tile id
            }
            // occasionally add cluster platforms
            if (rnd.nextDouble() < 0.02 && c + 4 < newCols) {
                int pr = groundRow - 3 - rnd.nextInt(2);
                for (int cc = c; cc < c + 3 && cc < newCols; cc++) nsolid[pr][cc] = true;
            }
            // occasionally place obstacle entity on new ground column
            if (!nsolid[groundRow][c] && rnd.nextDouble() < 0.02) {
                // skip obstacle if hole
            } else if (nsolid[groundRow][c] && rnd.nextDouble() < 0.03) {
                int ox = c * tileSize;
                int oy = groundRow * tileSize - 16;
                ObstacleEntity box = new ObstacleEntity(null, ox, oy, 64, 16);
                obstacles.add(box);
            }
            // no random flags here; flags are placed deterministically from map files
        }
        solid = nsolid;
        if (ntiles != null) tiles = ntiles;
        if (ntokens != null) colorTokens = ntokens;
        this.width = newCols * tileSize;
    }

    public int getTileId(int row, int col) {
        if (tiles == null) return -1;
        if (row < 0 || row >= tiles.length) return -1;
        if (col < 0 || col >= tiles[0].length) return -1;
        return tiles[row][col];
    }

    /**
     * Load a level tile map from a whitespace-separated text file with format:
     * first line: cols
     * second line: rows
     * then rows lines with cols tokens (numbers or non-numeric tokens treated as -1)
     * Tries classpath first, then file path.
     */
    public static Level loadFromMapFile(String resourcePath, int tileSize) {
        try {
            BufferedReader br = null;
            InputStream is = Level.class.getResourceAsStream(resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath);
            if (is != null) br = new BufferedReader(new InputStreamReader(is));
            else {
                File f = new File(resourcePath);
                if (f.exists()) br = new BufferedReader(new FileReader(f));
            }
            if (br == null) return null;
            String line = br.readLine();
            if (line == null) { br.close(); return null; }
            line = line.trim();
            int cols = Integer.parseInt(line);
            line = br.readLine(); if (line == null) { br.close(); return null; }
            int rows = Integer.parseInt(line.trim());
            Level lvl = new Level(cols, rows, tileSize);
            lvl.tiles = new int[rows][cols];
            // initialize tiles to -1
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) lvl.tiles[r][c] = -1;
            int r = 0;
            while ((line = br.readLine()) != null && r < rows) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] toks = line.split("\\s+");
                for (int c = 0; c < Math.min(toks.length, cols); c++) {
                    String t = toks[c];
                    int tid = -1;
                    // Support deterministic flags using token 'F' (case-insensitive)
                    if (t.equalsIgnoreCase("F")) {
                        // create a flag entity at this tile location (float above tile)
                        try {
                            Image img = null;
                            java.io.InputStream isf = Level.class.getResourceAsStream("/flag.png");
                            if (isf != null) img = javax.imageio.ImageIO.read(isf);
                            else {
                                java.io.File ff = new java.io.File("resources/flag.png");
                                if (ff.exists()) img = javax.imageio.ImageIO.read(ff);
                            }
                            int fx = c * tileSize;
                            int fy = r * tileSize - 32; // float slightly above the tile row
                         //   FlagEntity flag = new FlagEntity(img, fx, fy, 16, 32);
                         //   lvl.addObstacle(flag);
                         //   System.out.println("Placed flag at tile (r=" + r + ", c=" + c + ") world=(" + (c * tileSize) + "," + (r * tileSize) + ")");
                        } catch (Exception ignored) { }
                        tid = -1; // treat as empty tile visually
                    } else {
                     try { tid = Integer.parseInt(t); }
                     catch (NumberFormatException ex) {
                         // try single char letter mapping: A->10, B->11, etc
                         if (t.length() == 1 && t.charAt(0) >= 'A' && t.charAt(0) <= 'Z') tid = 10 + (t.charAt(0) - 'A');
                         else tid = -1;
                     }
                    }
                     lvl.tiles[r][c] = tid;
                     // Do not automatically mark solidity from tile id here.
                     // Tile visual IDs are kept in tiles[][] and collision is
                     // governed by the level's `solid` array (default bottom row
                     // and procedural generation). This avoids the entire map
                     // becoming solid when tile IDs represent visual-only tiles.
                }
                r++;
            }
            br.close();
            lvl.width = cols * tileSize;
            lvl.height = rows * tileSize;
            return lvl;
        } catch (Exception ex) {
            // on error, return null so caller can fallback
            return null;
        }
    }

    /**
     * Load both an image id map and a color/token map and apply solidity rules.
     * Tries to read mapImagesPath and mapColorsPath from classpath or file system.
     * Returns null on parse failure.
     */
    public static Level loadFromMapAndColorFiles(String mapImagesPath, String mapColorsPath, int tileSize) {
        try {
            Level images = loadFromMapFile(mapImagesPath, tileSize);
            if (images == null) return null;

            // read color/token map similarly
            BufferedReader br = null;
            InputStream is = Level.class.getResourceAsStream(mapColorsPath.startsWith("/") ? mapColorsPath : "/" + mapColorsPath);
            if (is != null) br = new BufferedReader(new InputStreamReader(is));
            else {
                File f = new File(mapColorsPath);
                if (f.exists()) br = new BufferedReader(new FileReader(f));
            }
            if (br == null) return images; // no color map, return images-only level

            String line = br.readLine();
            if (line == null) { br.close(); return images; }
            int cols = Integer.parseInt(line.trim());
            line = br.readLine(); if (line == null) { br.close(); return images; }
            int rows = Integer.parseInt(line.trim());

            // ensure dimensions match
            if (rows != images.tiles.length || cols != images.tiles[0].length) {
                // incompatible, but we can proceed by resizing tokens array to match images
            }

            images.colorTokens = new int[rows][cols];
            int r = 0;
            while ((line = br.readLine()) != null && r < rows) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] toks = line.split("\\s+");
                for (int c = 0; c < Math.min(toks.length, cols); c++) {
                    String t = toks[c];
                    int val = -1;
                    try { val = Integer.parseInt(t); }
                    catch (NumberFormatException ex) {
                        if (t.length() == 1 && t.charAt(0) >= 'A' && t.charAt(0) <= 'Z') val = 10 + (t.charAt(0) - 'A');
                    }
                    images.colorTokens[r][c] = val;
                }
                r++;
            }
            br.close();

            // Apply a mapping: tokens indicating ground (e.g., values >=2 or specific codes) set solid true.
            // We'll use a conservative default mapping: token 0/1 = background (non-solid), token >=2 = solid.
            for (int y = 0; y < images.colorTokens.length; y++) {
                for (int x = 0; x < images.colorTokens[0].length; x++) {
                    int tok = images.colorTokens[y][x];
                    if (tok >= 2) images.solid[y][x] = true;
                }
            }

            // After loading color tokens, re-parse the color file as text and place FlagEntity wherever the raw token is 'F' (case-insensitive).
            // This supports deterministic flags placed in either map-images or map-colors.
            try {
                BufferedReader br2 = null;
                InputStream is2 = Level.class.getResourceAsStream(mapColorsPath.startsWith("/") ? mapColorsPath : "/" + mapColorsPath);
                if (is2 != null) br2 = new BufferedReader(new InputStreamReader(is2));
                else {
                    java.io.File f = new java.io.File(mapColorsPath);
                    if (f.exists()) br2 = new BufferedReader(new java.io.FileReader(f));
                }
                String line2;
                int row = 0;
                if (br2 != null) {
                    // skip header lines (cols and rows counts)
                    try { br2.readLine(); br2.readLine(); } catch (Exception ignored) {}
                    while ((line2 = br2.readLine()) != null && row < rows) {
                     line2 = line2.trim();
                     if (line2.isEmpty()) {
                         row++;
                         continue;
                     }
                     String[] toks = line2.split("\\s+");
                     for (int c = 0; c < Math.min(toks.length, cols); c++) {
                         String t = toks[c];
                         // Support deterministic flags using token 'F' (case-insensitive)
                         if (t.equalsIgnoreCase("F")) {
                             // create a flag entity at this tile location (float above tile)
                             try {
                                 Image img = null;
                                 java.io.InputStream isf = Level.class.getResourceAsStream("/flag.png");
                                 if (isf != null) img = javax.imageio.ImageIO.read(isf);
                                 else {
                                     java.io.File ff = new java.io.File("resources/flag.png");
                                     if (ff.exists()) img = javax.imageio.ImageIO.read(ff);
                                 }
                                 int fx = c * tileSize;
                                 int fy = row * tileSize - 32; // float slightly above the tile row
                                 FlagEntity flag = new FlagEntity(img, fx, fy, 16, 32);
                                 images.addObstacle(flag);
                                 System.out.println("Placed flag at tile (r=" + row + ", c=" + c + ") world=(" + (c * tileSize) + "," + (row * tileSize) + ")");
                             } catch (Exception ignored) { }
                         }
                     }
                     row++;
                    }
                    try { br2.close(); } catch (Exception ignored) {}
                }
             } catch (Exception ignored) { }

            return images;
        } catch (Exception ex) {
            return null;
        }
    }

    public static Level sampleLevel() {
        Level lvl = new Level(30, 12, 32);
        ObstacleEntity box1 = new ObstacleEntity(null, 200, lvl.height - 64 - 16, 64, 16);
        ObstacleEntity box2 = new ObstacleEntity(null, 420, lvl.height - 96 - 16, 64, 16);
        lvl.addObstacle(box1);
        lvl.addObstacle(box2);
        return lvl;
    }
}
