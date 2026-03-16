package gymnasiearbete;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@SuppressWarnings("serial")
public class GamePanel extends JPanel {

    private static final int BASE_VIEW_W = 640;
    private static final int BASE_VIEW_H = 384;
    private volatile boolean running = false;
    private Thread gameThread;

    private Level level;
    private playerEntity player;

    // scaling and fullscreen
    private boolean fullscreen = false;
    private double scale = 1.0; // scale factor applied when fullscreen or resized
    private BufferedImage backBuffer = null; // offscreen buffer at base resolution

    // performance mode
    private boolean uncapped = false; // if true, remove 60Hz cap

    // simple input state
    private boolean leftPressed = false;
    private boolean rightPressed = false;
    private boolean jumpPressed = false;
    private boolean shootPressed = false;

    private BufferedImage playerImage = null;
    private BufferedImage missileImage = null;
    private BufferedImage enemyImage = null;
    private BufferedImage[] tileImages = null; // preloaded tile images by id
    private Image[] playerFrames = null; // player animation frames loaded from sprite sheet
    private Image backgroundImage = null; // parallax background (optional)
    private boolean debugHitboxes = false;

    // gameplay lists
    private final List<EnemyEntity> enemies = new ArrayList<>();
    private final List<MissileEntity> missiles = new ArrayList<>();
    // lock to protect concurrent access between game thread and EDT painting
    private final Object listsLock = new Object();

    // spawn / cooldown state
    private double enemySpawnTimer = 0.0; // seconds
    private double playerShootCooldown = 0.0; // seconds
    // player damage / invulnerability
    private double playerInvulTimer = 0.0; // seconds of invulnerability after taking damage
    private static final double PLAYER_INVUL_DURATION = 1.0; // 1 second

    // mouse world position (updated by mouse events)
    private volatile int mouseWorldX = 0;
    private volatile int mouseWorldY = 0;

    // game over state
    private boolean gameOver = false;

    public GamePanel() {
        setPreferredSize(new Dimension(BASE_VIEW_W, BASE_VIEW_H));
        setFocusable(true);

        // try loading combined map + colors; fallback to images-only or sample
        // read optional seed from resources/seed.txt (10-digit string or any text) and set global seed
        try {
            java.io.InputStream sis = getClass().getResourceAsStream("/seed.txt");
            java.io.File sf = new java.io.File("resources/seed.txt");
            java.io.BufferedReader sbr = null;
            if (sis != null) sbr = new java.io.BufferedReader(new java.io.InputStreamReader(sis));
            else if (sf.exists()) sbr = new java.io.BufferedReader(new java.io.FileReader(sf));
            if (sbr != null) {
                String s = sbr.readLine(); if (s != null) { s = s.trim(); if (!s.isEmpty()) Level.setGlobalSeed(s); }
                try { sbr.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        Level lvl = Level.loadFromMapAndColorFiles("/map-images.txt", "/map-colors.txt", 32);
        if (lvl == null) lvl = Level.loadFromMapFile("/map-images.txt", 32);
        if (lvl == null) lvl = Level.sampleLevel();
        level = lvl;

        System.out.println("Level loaded. Global seed='" + Level.getGlobalSeed() + "'");

        // backbuffer sized to base resolution
        backBuffer = new BufferedImage(BASE_VIEW_W, BASE_VIEW_H, BufferedImage.TYPE_INT_ARGB);

        // preload tile images
        tileImages = new BufferedImage[64];
        for (int i = 0; i < tileImages.length; i++) tileImages[i] = null;
        try {
            for (int i = 0; i <= 4; i++) {
                String name = "/tile-" + i + ".png";
                if (getClass().getResourceAsStream(name) != null) tileImages[i] = ImageIO.read(getClass().getResourceAsStream(name));
                else {
                    File f = new File("resources/tile-" + i + ".png");
                    if (f.exists()) tileImages[i] = ImageIO.read(f);
                }
            }
            if (getClass().getResourceAsStream("/tiles.png") != null) {
                BufferedImage tileset = ImageIO.read(getClass().getResourceAsStream("/tiles.png"));
                if (tileset != null) {
                    int cols = tileset.getWidth() / 32;
                    int rows = tileset.getHeight() / 32;
                    int idx = 0;
                    for (int ry = 0; ry < rows; ry++) {
                        for (int cx = 0; cx < cols; cx++) {
                            if (idx >= tileImages.length) break;
                            if (tileImages[idx] == null) tileImages[idx] = tileset.getSubimage(cx * 32, ry * 32, 32, 32);
                            idx++;
                        }
                    }
                }
            }
        } catch (IOException ignored) { }

        // load other images
        try { if (getClass().getResourceAsStream("/player.png") != null) playerImage = ImageIO.read(getClass().getResourceAsStream("/player.png"));
              else { File f = new File("resources/player.png"); if (f.exists()) playerImage = ImageIO.read(f); } } catch (IOException ignored) {}
        try { if (getClass().getResourceAsStream("/missile.png") != null) missileImage = ImageIO.read(getClass().getResourceAsStream("/missile.png"));
              else { File f = new File("resources/missile.png"); if (f.exists()) missileImage = ImageIO.read(f); } } catch (IOException ignored) {}
        try { if (getClass().getResourceAsStream("/tiles.png") != null) enemyImage = ImageIO.read(getClass().getResourceAsStream("/tiles.png"));
              else { File f = new File("resources/tile-0.png"); if (f.exists()) enemyImage = ImageIO.read(f); } } catch (IOException ignored) {}

        // create player (position Y uses player's height so it sits correctly on ground)
        player = new playerEntity(playerImage, 32, 0, 200);
        double playerStartY = level.height - 64 - player.getHeight();
        player.yPos = playerStartY; // set initial world Y directly
        // set a tighter hitbox for the player (inhale small transparent pixels)
        player.setHitbox(6, 4, player.getWidth() - 12, player.getHeight() - 6);
        // try load Cat_Ginger.png spritesheet from resources/Cat_85_Animations/
        try {
            java.io.InputStream is = getClass().getResourceAsStream("/Cat_85_Animations/Cat_Ginger.png");
            if (is == null) {
                java.io.File f = new java.io.File("resources/Cat_85_Animations/Cat_Ginger.png");
                if (f.exists()) is = new java.io.FileInputStream(f);
            }
            if (is != null) {
                java.awt.image.BufferedImage sheet = javax.imageio.ImageIO.read(is);
                if (sheet != null) {
                    int cols = sheet.getWidth() / 32;
                    int rows = sheet.getHeight() / 32;

                    // default mapping (row indices) for states
                    int idleRowDefault = rows > 0 ? 0 : 0;
                    int runRowDefault = rows > 1 ? 1 : 0;
                    int jumpRowDefault = rows > 2 ? 2 : 0;
                    int fallRowDefault = rows > 3 ? 3 : 0;

                    // attempt to read mapping file first so we know which rows correspond to which states
                    int idleRow = idleRowDefault;
                    int runRow = runRowDefault;
                    int jumpRow = jumpRowDefault;
                    int fallRow = fallRowDefault;
                    // per-state desired frame counts and FPS (optional in mapping file)
                    int idleFrames = -1, runFrames = -1, jumpFrames = -1, fallFrames = -1;
                    double idleFps = -1, runFps = -1, jumpFps = -1, fallFps = -1;
                    try {
                        java.io.InputStream mis = getClass().getResourceAsStream("/cat_ginger_anim_map.txt");
                        java.io.File mf = new java.io.File("resources/cat_ginger_anim_map.txt");
                        java.io.BufferedReader mbr = null;
                        if (mis != null) mbr = new java.io.BufferedReader(new java.io.InputStreamReader(mis));
                        else if (mf.exists()) mbr = new java.io.BufferedReader(new java.io.FileReader(mf));
                        if (mbr != null) {
                            String line;
                            while ((line = mbr.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                                // support formats:
                                // state=row
                                // state=row,frames=N,fps=F
                                String[] parts = line.split("=");
                                if (parts.length < 2) continue;
                                String state = parts[0].trim().toLowerCase();
                                String rhs = line.substring(line.indexOf('=') + 1).trim();
                                // split rhs by commas to allow extra settings
                                String[] tokens = rhs.split(",");
                                int rowIdx = -1;
                                for (String t : tokens) {
                                    t = t.trim();
                                    if (t.isEmpty()) continue;
                                    if (t.contains("frames" ) || t.contains("fps")) continue; // handle separately
                                    try { rowIdx = Integer.parseInt(t); } catch (Exception ex) { /*ignore*/ }
                                }
                                // now check for frames= and fps=
                                for (String t : tokens) {
                                    t = t.trim(); if (t.isEmpty()) continue;
                                    String tl = t.toLowerCase();
                                    if (tl.startsWith("frames")) {
                                        String[] kv = t.split("="); if (kv.length==2) {
                                            try { int v = Integer.parseInt(kv[1].trim());
                                                if (v > 0) {
                                                    if ("idle".equals(state)) idleFrames = v;
                                                    else if ("run".equals(state)) runFrames = v;
                                                    else if ("jump".equals(state)) jumpFrames = v;
                                                    else if ("fall".equals(state)) fallFrames = v;
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                    } else if (tl.startsWith("fps")) {
                                        String[] kv = t.split("="); if (kv.length==2) {
                                            try { double v = Double.parseDouble(kv[1].trim());
                                                if (v > 0) {
                                                    if ("idle".equals(state)) idleFps = v;
                                                    else if ("run".equals(state)) runFps = v;
                                                    else if ("jump".equals(state)) jumpFps = v;
                                                    else if ("fall".equals(state)) fallFps = v;
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                }
                                if (rowIdx >= 0) {
                                    if ("idle".equals(state)) idleRow = Math.max(0, Math.min(rowIdx, rows - 1));
                                    else if ("run".equals(state)) runRow = Math.max(0, Math.min(rowIdx, rows - 1));
                                    else if ("jump".equals(state)) jumpRow = Math.max(0, Math.min(rowIdx, rows - 1));
                                    else if ("fall".equals(state)) fallRow = Math.max(0, Math.min(rowIdx, rows - 1));
                                }
                            }
                            mbr.close();
                        }
                    } catch (Exception ignored) { }

                    // build final animations array but trim specific state rows to desired frame counts
                    Image[][] anims = new Image[rows][];
                    for (int ry = 0; ry < rows; ry++) {
                        int desiredLen = cols; // default: full row
                        // enforce desired frame counts for player states to avoid ghosting
                        if (ry == idleRow && idleFrames > 0) desiredLen = Math.min(idleFrames, cols);
                        else if (ry == runRow && runFrames > 0) desiredLen = Math.min(runFrames, cols);
                        else if (ry == jumpRow && jumpFrames > 0) desiredLen = Math.min(jumpFrames, cols);
                        else desiredLen = cols;
                        anims[ry] = new Image[desiredLen];
                        for (int cx = 0; cx < desiredLen; cx++) {
                            anims[ry][cx] = sheet.getSubimage(cx * 32, ry * 32, 32, 32);
                        }
                    }

                    player.setAnimations(anims, 10.0); // default fps
                    // apply mapping so playerEntity knows which row is idle/run/jump/fall
                    player.setAnimationMapping(idleRow, runRow, jumpRow, fallRow);
                    // apply per-state fps if specified in mapping file
                    player.setAnimationStateFPS(idleFps, runFps, jumpFps, fallFps);
                }
            }
        } catch (Exception ignored) {}

        // load optional background image for parallax
        try {
            java.io.InputStream isbg = getClass().getResourceAsStream("/background.png");
            if (isbg == null) {
                java.io.File f = new java.io.File("resources/background.png");
                if (f.exists()) isbg = new java.io.FileInputStream(f);
            }
            if (isbg != null) backgroundImage = javax.imageio.ImageIO.read(isbg);
        } catch (Exception ignored) {}

        // obstacles
        for (Entity e : level.getObstacles()) e.setActive(true);

        // key handling with extra toggles
        addKeyListener(new KeyAdapter(){
            @Override public void keyPressed(KeyEvent e) { handleKey(e, true); }
            @Override public void keyReleased(KeyEvent e) { handleKey(e, false); }
        });

        // mouse handling: track world position and clicks to fire towards mouse
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // if game over, clicking restarts the level
                if (gameOver) { restart(); return; }
                // otherwise attempt to shoot
                attemptPlayerShootAtScreen(e.getX(), e.getY());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                updateMouseWorld(e.getX(), e.getY());
            }
            @Override public void mouseDragged(MouseEvent e) {
                updateMouseWorld(e.getX(), e.getY());
            }
        });

        setDoubleBuffered(true);
    }

    // central key handler (restored) - moved out of constructor
    private void handleKey(KeyEvent e, boolean pressed) {
        int kc = e.getKeyCode();
        switch(kc) {
            case KeyEvent.VK_LEFT: case KeyEvent.VK_A: leftPressed = pressed; break;
            case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: rightPressed = pressed; break;
            case KeyEvent.VK_UP: case KeyEvent.VK_W: case KeyEvent.VK_SPACE:
                if (pressed) player.jump(); if (!pressed) player.releaseJump(); jumpPressed = pressed; break;
            case KeyEvent.VK_Z: case KeyEvent.VK_K:
                if (pressed) attemptPlayerShoot(); shootPressed = pressed; break;
            case KeyEvent.VK_R: if (pressed) restart(); break;
            case KeyEvent.VK_F11:
                if (pressed) toggleFullscreen(); break;
            case KeyEvent.VK_F2:
                if (pressed) uncapped = !uncapped; break;
            case KeyEvent.VK_F3:
                if (pressed) toggleDebugHitboxes(); break;
            case KeyEvent.VK_F4:
                if (pressed) {
                    String s = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(this), "Enter world seed (10-digit or any string):", Level.getGlobalSeed());
                    if (s != null) {
                        s = s.trim(); if (!s.isEmpty()) Level.setGlobalSeed(s);
                        System.out.println("Global seed set to: '" + Level.getGlobalSeed() + "'");
                        restart();
                    }
                }
                break;
            case KeyEvent.VK_F5:
                if (pressed) {
                    // debug: kill player to test game over overlay
                    System.out.println("F5 pressed: forcing player death for test.");
                    player.takeDamage(9999);
                }
                break;
         }
         updatePlayerDir();
    }

    private void toggleDebugHitboxes() { debugHitboxes = !debugHitboxes; }

    private void updateMouseWorld(int screenX, int screenY) {
        // map screen coordinates to world coordinates taking current panel size and aspect into account
        int w = getWidth(); int h = getHeight();
        double sx = (double) w / BASE_VIEW_W; double sy = (double) h / BASE_VIEW_H; double s = Math.min(sx, sy);
        int drawW = (int) Math.round(BASE_VIEW_W * s); int drawH = (int) Math.round(BASE_VIEW_H * s);
        int ox = (w - drawW)/2; int oy = (h - drawH)/2;
        int localX = screenX - ox; int localY = screenY - oy;
        if (localX < 0) localX = 0; if (localX > drawW) localX = drawW;
        if (localY < 0) localY = 0; if (localY > drawH) localY = drawH;
        double gx = localX / (double)drawW * BASE_VIEW_W;
        double gy = localY / (double)drawH * BASE_VIEW_H;
        // convert to world coords using camera center (same math as in rendering)
        int camX = player.getX() - BASE_VIEW_W/2;
        int camY = player.getY() - BASE_VIEW_H/2;
        camX = Math.max(0, Math.min(camX, Math.max(0, level.width - BASE_VIEW_W)));
        camY = Math.max(0, Math.min(camY, Math.max(0, level.height - BASE_VIEW_H)));
        mouseWorldX = (int)Math.round(gx + camX);
        mouseWorldY = (int)Math.round(gy + camY);
    }

    private void attemptPlayerShoot() {
        // legacy keyboard shoot (aim in facing direction)
        if (playerShootCooldown > 0.0 || !player.getActive()) return;
        int dir = (player.dx >= 0) ? 1 : -1;
        double sx = player.xPos + (dir > 0 ? player.getWidth() : -8);
        double sy = player.yPos + player.getHeight() / 2.0;
        MissileEntity m = new MissileEntity(missileImage, sx, sy, 420, dir, 2000000000L, 0);
        m.setOwner(1);
        synchronized (listsLock) { missiles.add(m); }
        playerShootCooldown = 0.25;
    }

    private void attemptPlayerShootAtScreen(int screenX, int screenY) {
        if (playerShootCooldown > 0.0 || !player.getActive()) return;
        // ensure mouseWorld updated for this click
        updateMouseWorld(screenX, screenY);
        double sx = player.xPos + player.getWidth()/2.0;
        double sy = player.yPos + player.getHeight()/2.0;
        double tx = mouseWorldX;
        double ty = mouseWorldY;
        double dx = tx - sx;
        double dy = ty - sy;
        double dist = Math.hypot(dx, dy);
        if (dist < 1.0) dist = 1.0;
        double speed = 420.0; // projectile speed in px/s
        double vx = dx / dist * speed;
        double vy = dy / dist * speed;
        MissileEntity m = new MissileEntity(missileImage, sx, sy, (int)speed, 1, 2000000000L, 0);
        m.setOwner(1);
        m.setVelocity(vx, vy);
        synchronized (listsLock) { missiles.add(m); }
        playerShootCooldown = 0.25;
    }

    private void updatePlayerDir() {
        int dx = 0; if (leftPressed && !rightPressed) dx = -1; else if (rightPressed && !leftPressed) dx = 1; else dx = 0; player.setDirectionX(dx);
    }

    public void start() {
        if (running) return;
        running = true;
        gameThread = new Thread(this::runLoop, "GameLoop");
        gameThread.start();
    }

    private void toggleFullscreen() {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (!(w instanceof JFrame)) return;
        JFrame f = (JFrame) w;
        fullscreen = !fullscreen;
        f.dispose();
        f.setUndecorated(fullscreen);
        f.setVisible(true);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        double sx = screen.getWidth() / BASE_VIEW_W;
        double sy = screen.getHeight() / BASE_VIEW_H;
        scale = Math.min(sx, sy);
        revalidate();
    }

    public void stop() { running = false; if (gameThread != null) { try { gameThread.join(2000); } catch (InterruptedException ignored) {} gameThread = null; } }

    private void restart() {
        // reload level from map files
        Level lvl = Level.loadFromMapAndColorFiles("/map-images.txt", "/map-colors.txt", 32);
        if (lvl == null) lvl = Level.loadFromMapFile("/map-images.txt", 32);
        if (lvl == null) lvl = Level.sampleLevel();
        this.level = lvl;
        // reset obstacles active state
        for (Entity e : level.getObstacles()) e.setActive(true);
        // reset player position and state
        double py = level.height - 64 - player.getHeight();
        player.reset(playerImage, 32, py, 200);
        player.setActive(true);
        // clear runtime lists and timers
        enemies.clear(); missiles.clear(); enemySpawnTimer = 0.0; playerShootCooldown = 0.0; playerInvulTimer = 0.0;
        gameOver = false;
    }

    private void runLoop() {
        long last = System.nanoTime();
        final double targetFPS = 60.0;
        while (running) {
            long now = System.nanoTime();
            long dt = now - last;
            last = now;
            double seconds = dt / 1_000_000_000.0;

            enemySpawnTimer -= seconds;
            playerShootCooldown = Math.max(0.0, playerShootCooldown - seconds);
            // invulnerability timer
            playerInvulTimer = Math.max(0.0, playerInvulTimer - seconds);

            if (enemySpawnTimer <= 0.0) { spawnEnemyThreadSafe(); enemySpawnTimer = 1.2 + Math.random() * 2.0; }

            updatePlayerDir();
            player.move(dt, level);
            // ensure level extends ahead of player for infinite feeling
            level.ensureWidthPixels(player.getX() + BASE_VIEW_W * 2);

            // move enemies and allow them to create missiles in a thread-safe way
            synchronized (listsLock) {
                for (EnemyEntity en : new ArrayList<>(enemies)) {
                    en.move(dt, level);
                    MissileEntity em = en.tryCreateMissile(level, player, missileImage);
                    if (em != null) missiles.add(em);
                }
            }

            // move missiles and handle collisions in a thread-safe way
            synchronized (listsLock) {
                Iterator<MissileEntity> mit = missiles.iterator();
                while (mit.hasNext()) {
                    MissileEntity ms = mit.next(); ms.move(dt, level);
                    if (!ms.getActive()) { mit.remove(); continue; }
                    if (ms.getOwner() == 1) {
                        for (EnemyEntity en : enemies) {
                            if (!en.getActive()) continue;
                            int mx = (int)Math.round(ms.xPos);
                            int my = (int)Math.round(ms.yPos);
                            int mw = 8; int mh = 8;
                            // if missile has hitbox configured, use that
                            if (ms.getHitboxWidth() > 0) { mx = (int)Math.round(ms.getHitboxX()); my = (int)Math.round(ms.getHitboxY()); mw = ms.getHitboxWidth(); mh = ms.getHitboxHeight(); }
                            int ex = en.getHitboxX() == 0 ? en.getX() : (int)Math.round(en.getHitboxX());
                            int ey = en.getHitboxY() == 0 ? en.getY() : (int)Math.round(en.getHitboxY());
                            int ew = en.getHitboxWidth(); int eh = en.getHitboxHeight();
                            if (rectsOverlap(mx, my, mw, mh, ex, ey, ew, eh)) { en.takeDamage(1); ms.setActive(false); break; }
                        }
                    } else if (ms.getOwner() == 2) {
                        int mx = (int)Math.round(ms.xPos);
                        int my = (int)Math.round(ms.yPos);
                        int mw = 8; int mh = 8;
                        if (ms.getHitboxWidth() > 0) { mx = (int)Math.round(ms.getHitboxX()); my = (int)Math.round(ms.getHitboxY()); mw = ms.getHitboxWidth(); mh = ms.getHitboxHeight(); }
                        int px = (int)Math.round(player.getHitboxX()); int py = (int)Math.round(player.getHitboxY()); int pw = player.getHitboxWidth(); int ph = player.getHitboxHeight();
                        if (player.getActive() && rectsOverlap(mx, my, mw, mh, px, py, pw, ph)) { 
                            // apply damage respecting temporary invulnerability
                            if (playerInvulTimer <= 0.0) {
                                player.takeDamage(1);
                                playerInvulTimer = PLAYER_INVUL_DURATION;
                            }
                            ms.setActive(false); }
                    }
                }
            }

            // If player's health reached zero, trigger game over (ensure it happens once)
            if (!gameOver && player.getHealth() <= 0) {
                gameOver = true;
                player.setActive(false);
                System.out.println("Player died: game over triggered.");
            }

            synchronized (listsLock) {
                Iterator<EnemyEntity> eit = enemies.iterator(); while (eit.hasNext()) { EnemyEntity en = eit.next(); if (!en.getActive()) eit.remove(); }
            }

            // camera follow: center on player but clamp to level bounds
            int camX = player.getX() - BASE_VIEW_W/2;
            int camY = player.getY() - BASE_VIEW_H/2;
            camX = Math.max(0, Math.min(camX, Math.max(0, level.width - BASE_VIEW_W)));
            camY = Math.max(0, Math.min(camY, Math.max(0, level.height - BASE_VIEW_H)));

            // schedule repaint
            repaint();

            if (!uncapped) {
                long elapsed = System.nanoTime() - now;
                long targetNanos = (long)(1_000_000_000.0 / targetFPS);
                long sleep = targetNanos - elapsed;
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000L, (int)(sleep % 1_000_000L)); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void spawnEnemy() { int spawnX = Math.max(player.getX(), 0) + BASE_VIEW_W + 80 + (int)(Math.random() * 160); int groundY = level.height - 64 - 32; EnemyEntity en = new EnemyEntity(enemyImage, spawnX, groundY, spawnX - 220, spawnX + 220); enemies.add(en); }

    private void spawnEnemyThreadSafe() {
        int spawnX = Math.max(player.getX(), 0) + BASE_VIEW_W + 80 + (int)(Math.random() * 160);
        int groundY = level.height - 64 - 32;
        EnemyEntity en = new EnemyEntity(enemyImage, spawnX, groundY, spawnX - 220, spawnX + 220);
        synchronized (listsLock) { enemies.add(en); }
    }

    @Override
    protected void paintComponent(Graphics g) {
        // render to backBuffer at base resolution, then scale to panel size for fullscreen/scaling
        if (backBuffer == null || backBuffer.getWidth() != BASE_VIEW_W || backBuffer.getHeight() != BASE_VIEW_H) {
            backBuffer = new BufferedImage(BASE_VIEW_W, BASE_VIEW_H, BufferedImage.TYPE_INT_ARGB);
        }
        Graphics2D gbb = backBuffer.createGraphics();

        // clear
        gbb.setColor(new Color(0x88CCFF)); gbb.fillRect(0, 0, BASE_VIEW_W, BASE_VIEW_H);

        // camera centered on player
        int camX = player.getX() - BASE_VIEW_W/2;
        int camY = player.getY() - BASE_VIEW_H/2;
        camX = Math.max(0, Math.min(camX, Math.max(0, level.width - BASE_VIEW_W)));
        camY = Math.max(0, Math.min(camY, Math.max(0, level.height - BASE_VIEW_H)));

        // draw parallax background (if available)
        if (backgroundImage != null) {
            // parallax factor (less than 1 so background moves slower)
            double parallax = 0.4;
            int bgW = backgroundImage.getWidth(null);
            int bgH = backgroundImage.getHeight(null);
            if (bgW > 0 && bgH > 0) {
                // compute background offset based on player center
                double centerX = player.getX();
                int bx = (int) Math.round(-((centerX * parallax) % bgW));
                for (int xx = bx - bgW; xx < BASE_VIEW_W; xx += bgW) {
                    gbb.drawImage(backgroundImage, xx, 0, bgW, BASE_VIEW_H, null);
                }
            }
        }

        // draw tiles
        int tile = level.tileSize;
        int cols = level.width / tile;
        int rows = level.height / tile;
        int startCol = Math.max(0, camX / tile - 1);
        int endCol = Math.min(cols - 1, (camX + BASE_VIEW_W) / tile + 1);
        int startRow = Math.max(0, camY / tile - 1);
        int endRow = Math.min(rows - 1, (camY + BASE_VIEW_H) / tile + 1);

        for (int r = startRow; r <= endRow; r++) {
            for (int c = startCol; c <= endCol; c++) {
                int x = c * tile - camX;
                int y = r * tile - camY;
                int tid = level.getTileId(r, c);
                if (tid >= 0) {
                    int idx = tid;
                    if (idx < 0) idx = 0;
                    if (idx >= tileImages.length) idx = idx % tileImages.length;
                    BufferedImage img = tileImages[idx];
                    if (img != null) { gbb.drawImage(img, x, y, tile, tile, null); continue; }
                }
                if (level.isTileSolid(r, c)) { gbb.setColor(new Color(0x8B5A2B)); gbb.fillRect(x, y, tile, tile); gbb.setColor(Color.DARK_GRAY); gbb.drawRect(x, y, tile, tile); }
            }
        }

        // draw obstacles (use snapshot to avoid concurrent modifications)
        java.util.List<Entity> obstaclesSnapshot = new java.util.ArrayList<>(level.getObstacles());
        for (Entity e : obstaclesSnapshot) { if (!e.getActive()) continue; gbb.translate(-camX, -camY); e.draw(gbb);
             // optionally draw debug hitboxes
             if (debugHitboxes) {
                 int hx = (int)Math.round(e.getHitboxX()); int hy = (int)Math.round(e.getHitboxY()); int hw = e.getHitboxWidth(); int hh = e.getHitboxHeight();
                 Color old = gbb.getColor(); gbb.setColor(new Color(255,0,0,128)); gbb.fillRect(hx, hy, hw, hh); gbb.setColor(old);
             }
             gbb.translate(camX, camY); }

        // draw enemies from a snapshot to avoid concurrent modifications
        java.util.List<EnemyEntity> enemySnapshot;
        synchronized (listsLock) { enemySnapshot = new ArrayList<>(enemies); }
        for (EnemyEntity en : enemySnapshot) {
            if (!en.getActive()) continue;
            gbb.translate(-camX, -camY); en.draw(gbb);
            int ex = en.getX(); int ey = en.getY() - 8; int ew = en.getWidth();
            gbb.setColor(Color.RED); int hpW = (int)((en.getHealth()/(double)en.getMaxHealth())*ew); gbb.fillRect(ex, ey, hpW, 4); gbb.setColor(Color.BLACK); gbb.drawRect(ex, ey, ew, 4);
            gbb.translate(camX, camY);
        }

        // missiles (use snapshot)
        java.util.List<MissileEntity> missileSnapshot;
        synchronized (listsLock) { missileSnapshot = new ArrayList<>(missiles); }
        for (MissileEntity ms : missileSnapshot) {
            if (!ms.getActive()) continue;
            gbb.translate(-camX, -camY);
            if (ms.image != null) gbb.drawImage(ms.image, (int)ms.xPos, (int)ms.yPos, 8, 8, null);
            else { Color old = gbb.getColor(); gbb.setColor(Color.YELLOW); gbb.fillRect((int)ms.xPos, (int)ms.yPos, 8, 8); gbb.setColor(old); }
            gbb.translate(camX, camY);
        }

        // player
        gbb.translate(-camX, -camY); player.draw(gbb);
        if (debugHitboxes) {
            int hx = (int)Math.round(player.getHitboxX()); int hy = (int)Math.round(player.getHitboxY()); int hw = player.getHitboxWidth(); int hh = player.getHitboxHeight();
            Color old = gbb.getColor(); gbb.setColor(new Color(0,0,255,128)); gbb.fillRect(hx, hy, hw, hh); gbb.setColor(old);
        }
        gbb.translate(camX, camY);

        // HUD: player position + hearts for health
        gbb.setColor(new Color(255,255,255,200)); gbb.fillRect(6, 6, 260, 44); gbb.setColor(Color.BLACK); gbb.drawRect(6, 6, 260, 44);
        gbb.drawString("Player X: " + player.getX(), 12, 22);
        // hearts
        int heartX = 12; int heartY = 32; int heartSize = 10; int maxHp = player.getMaxHealth(); int curHp = player.getHealth();
        for (int i=0;i<maxHp;i++) {
            if (i < curHp) gbb.setColor(Color.RED); else gbb.setColor(Color.LIGHT_GRAY);
            gbb.fillOval(heartX + i*(heartSize+4), heartY, heartSize, heartSize);
            gbb.setColor(Color.BLACK);
            gbb.drawOval(heartX + i*(heartSize+4), heartY, heartSize, heartSize);
        }
        // invulnerability indicator
        if (playerInvulTimer > 0.0) {
            gbb.setColor(new Color(255, 255, 0, 160));
            gbb.fillRect(150, 12, (int)(100 * (playerInvulTimer/PLAYER_INVUL_DURATION)), 10);
            gbb.setColor(Color.BLACK);
            gbb.drawRect(150, 12, 100, 10);
        }

        // Game over overlay
        if (gameOver) {
            Color old = gbb.getColor();
            gbb.setColor(new Color(0,0,0,180));
            gbb.fillRect(0,0,BASE_VIEW_W, BASE_VIEW_H);
            gbb.setColor(Color.WHITE);
            gbb.setFont(gbb.getFont().deriveFont(Font.BOLD, 28f));
            String msg = "GAME OVER";
            int mw = gbb.getFontMetrics().stringWidth(msg);
            gbb.drawString(msg, (BASE_VIEW_W - mw)/2, BASE_VIEW_H/2 - 8);
            gbb.setFont(gbb.getFont().deriveFont(Font.PLAIN, 16f));
            String sub = "Click anywhere to restart";
            int sw = gbb.getFontMetrics().stringWidth(sub);
            gbb.drawString(sub, (BASE_VIEW_W - sw)/2, BASE_VIEW_H/2 + 16);
            gbb.setColor(old);
        }

        gbb.dispose();

        // now draw scaled backBuffer to component size while preserving aspect
        Graphics2D g2 = (Graphics2D) g;
        int w = getWidth(); int h = getHeight();
        double sx = (double) w / BASE_VIEW_W; double sy = (double) h / BASE_VIEW_H; double s = Math.min(sx, sy);
        int drawW = (int) Math.round(BASE_VIEW_W * s); int drawH = (int) Math.round(BASE_VIEW_H * s);
        int ox = (w - drawW)/2; int oy = (h - drawH)/2;
        g2.setColor(Color.BLACK); g2.fillRect(0,0,w,h);
        g2.drawImage(backBuffer, ox, oy, drawW, drawH, null);
    }

    private static boolean rectsOverlap(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) { return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by; }
}