package gymnasiearbete;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.net.URL;
import java.text.DecimalFormat;

@SuppressWarnings("serial")
public class GamePanel extends Canvas implements Runnable {

	// Threading / lifecycle
	private Thread thread;
	private volatile boolean running = false;

	// Game objects
	private playerEntity player;
	private MissileEntity missile;
	private Level level;

	// GPU-backed caches
	private VolatileImage backgroundBuffer;   // plain background area
	private VolatileImage staticMapBuffer;    // cached level rendering (tiles, obstacles)
	private boolean staticMapDirty = true;

	// Cached converted images
	private BufferedImage cachedMissileImage;

	// Input state
	private boolean left, right, jump;
	private boolean prevJump = false;


	// Timing
	private static final int TARGET_FPS = 120;
	private static final double FRAME_TIME_NANO = 1_000_000_000.0 / TARGET_FPS;
	private static final long FRAME_TIME_NANO_LONG = (long) FRAME_TIME_NANO;
	private static final long SLEEP_THRESHOLD_MS = 2;

	// Diagnostics
	private int fps = 0;
	private int framesThisSecond = 0;
	private long fpsTimer = 0;
	@SuppressWarnings("unused")
	private long lastFrameTimeNs = 0;
	private double lastFrameMs = 0;

	// Reused formatting / buffers to avoid allocations
	private final DecimalFormat df = new DecimalFormat("0.00");
	private final StringBuilder sb = new StringBuilder(64);

	public GamePanel() {
		// Try to enable accelerated pipelines early (best set before creating the window)
		System.setProperty("sun.java2d.opengl", "true");
		System.setProperty("sun.java2d.translaccel", "true");
		System.setProperty("sun.java2d.ddforcevram", "true");

		setPreferredSize(new Dimension(1150, 550));
		setBackground(Color.CYAN);
		setIgnoreRepaint(true);
		addKeyListener(new InputHandler(this));
		setFocusable(true);
		setFocusTraversalKeysEnabled(false);
	}

	public synchronized void start() {
		if (running) return;
		running = true;
		thread = new Thread(this, "GameLoopThread");
		thread.start();

		// Ensure keyboard focus once the component is visible
		SwingUtilities.invokeLater(() -> {
			requestFocusInWindow();
			setFocusTraversalKeysEnabled(false);
		});
	}

	public synchronized void stop() {
		running = false;
		try { if (thread != null) thread.join(); } catch (InterruptedException ignored) {}
	}

	@Override
	public void run() {
		// Wait until the component is displayable and showing before creating buffer strategy
		while (!isDisplayable() || !isShowing()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException ignored) {}
			if (!running) return;
		}

		// Create triple buffering to reduce stalls
		try {
			createBufferStrategy(3);
		} catch (IllegalStateException ise) {
			try { Thread.sleep(10); } catch (InterruptedException ignored) {}
			createBufferStrategy(3);
		}
		BufferStrategy bs = getBufferStrategy();

		// Initialize game (loads images, creates level, etc.)
		initGame();

		// Prepare volatile images (background + static map)
		ensureBuffers();

		long previous = System.nanoTime();
		double accumulator = 0.0;

		fpsTimer = previous;
		lastFrameTimeNs = previous;

		while (running) {
			long now = System.nanoTime();
			long elapsed = now - previous;
			previous = now;
			accumulator += elapsed;

			// Fixed-step updates for stable physics
			while (accumulator >= FRAME_TIME_NANO) {
				update(FRAME_TIME_NANO_LONG);
				accumulator -= FRAME_TIME_NANO;
			}

			// Render
			render(bs);

			// FPS diagnostics
			framesThisSecond++;
			if (now - fpsTimer >= 1_000_000_000L) {
				fps = framesThisSecond;
				framesThisSecond = 0;
				fpsTimer = now;
			}

			// Frame pacing: sleep + spin-wait
			long frameEnd = System.nanoTime();
			long frameDuration = frameEnd - now;
			lastFrameTimeNs = frameEnd;
			lastFrameMs = frameDuration / 1_000_000.0;

			long timeLeftNs = FRAME_TIME_NANO_LONG - frameDuration;
			if (timeLeftNs > 0) {
				long sleepMs = timeLeftNs / 1_000_000L;
				if (sleepMs >= SLEEP_THRESHOLD_MS) {
					try {
						Thread.sleep(Math.max(0, sleepMs - 1));
					} catch (InterruptedException ignored) {}
				}
				long spinUntil = System.nanoTime() + Math.max(0, timeLeftNs - (SLEEP_THRESHOLD_MS * 1_000_000L));
				while (System.nanoTime() < spinUntil) {
					Thread.onSpinWait();
				}
			}
		}
	}

	private void initGame() {
		// Load and convert images to device-compatible BufferedImages
		BufferedImage playerImage = toCompatibleImage(loadImage("/player.png"));
		BufferedImage tileImg = toCompatibleImage(loadImage("/tile-2.png"));
		BufferedImage missileImg = toCompatibleImage(loadImage("/missile.png"));
		BufferedImage obstacleImg = toCompatibleImage(loadImage("/obstacle.png"));
		BufferedImage sideTileImg0 = toCompatibleImage(loadImage("/tile-0.png")); //left side tile
		BufferedImage sideTileImg1 = toCompatibleImage(loadImage("/tile-1.png")); //right side tile

		// Cache missile image for reuse (avoid converting per-fire)
		cachedMissileImage = missileImg;

		// Create level
		level = new Level(37, 19);
		level.setTileImage(tileImg);
		level.setTileImage0(sideTileImg0);
		level.setTileImage1(sideTileImg1);
		level.setObstacleImage(obstacleImg);

		// Ground
		for (int c = 0; c < level.cols; c++) {
			level.setTile(c, level.rows - 2, 1);
		}
		
		// 1 is flat grass, 2 is flat grass with left side covered, 3 is right side covered grass
		
		// Sample platforms
		level.setTile(2, 13, 1);
		level.setTile(5, 13, 2);
		level.setTile(6, 13, 1);
		level.setTile(7, 13, 3);
		level.setTile(12, 10, 2);
		level.setTile(13, 10, 1);
		level.setTile(14, 10, 3);
		level.setTile(16, 12, 1);
		level.setTile(20, 13, 2);
		level.setTile(21, 13, 1);
		level.setTile(22, 13, 1);
		level.setTile(23, 13, 3);
		
		

		// Player (use converted image)
		player = new playerEntity(playerImage, 100, 100, 200);

		missile = null;

		// Mark static map for rebuild
		staticMapDirty = true;
	}

	/* ----------------------
       Image loading and conversion
       ---------------------- */
	private Image loadImage(String path) {
		try {
			URL url = getClass().getResource(path);
			if (url != null) {
				return new ImageIcon(url).getImage();
			}
		} catch (Exception ignored) {}
		// fallback magenta placeholder
		BufferedImage tmp = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = tmp.createGraphics();
		try {
			g.setColor(Color.MAGENTA);
			g.fillRect(0, 0, 32, 32);
		} finally {
			g.dispose();
		}
		return tmp;
	}

	/**
	 * Convert any Image into a device-compatible BufferedImage (best for GPU blits).
	 */
	private BufferedImage toCompatibleImage(Image img) {
		if (img == null) return null;
		int w = Math.max(1, img.getWidth(null));
		int h = Math.max(1, img.getHeight(null));

		GraphicsConfiguration gc = getBestGraphicsConfiguration();

		BufferedImage compatible = gc.createCompatibleImage(w, h, Transparency.TRANSLUCENT);
		Graphics2D g2 = compatible.createGraphics();
		try {
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(img, 0, 0, null);
		} finally {
			g2.dispose();
		}
		return compatible;
	}

	/* Buffer creation / caching (VolatileImage-safe) */

	private GraphicsConfiguration getBestGraphicsConfiguration() {
		GraphicsConfiguration gc = getGraphicsConfiguration();
		if (gc != null) return gc;
		return GraphicsEnvironment
				.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice()
				.getDefaultConfiguration();
	}

	/**
	 * Ensure backgroundBuffer and staticMapBuffer exist and contain up-to-date content.
	 * Recreate only on resize or when marked dirty.
	 */
	private void ensureBuffers() {
		int w = getWidth();
		int h = getHeight();
		if (w <= 0 || h <= 0) return;

		GraphicsConfiguration gc = getBestGraphicsConfiguration();

		// Background buffer (opaque)
		if (backgroundBuffer == null || backgroundBuffer.getWidth() != w || backgroundBuffer.getHeight() != h) {
			backgroundBuffer = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
			redrawBackgroundBuffer();
			staticMapDirty = true;
		}

		// Static map buffer (translucent)
		if (staticMapBuffer == null || staticMapBuffer.getWidth() != w || staticMapBuffer.getHeight() != h || staticMapDirty) {
			staticMapBuffer = gc.createCompatibleVolatileImage(w, h, Transparency.TRANSLUCENT);
			redrawStaticMapBuffer();
			staticMapDirty = false;
		}
	}

	/**
	 * Redraws the backgroundBuffer contents (opaque fill).
	 * Loops until contents are stable to handle transient GPU behavior.
	 */
	private void redrawBackgroundBuffer() {
		if (backgroundBuffer == null) return;
		do {
			Graphics2D g = backgroundBuffer.createGraphics();
			try {
				g.setColor(getBackground());
				g.fillRect(0, 0, backgroundBuffer.getWidth(), backgroundBuffer.getHeight());
			} finally {
				g.dispose();
			}
		} while (backgroundBuffer.contentsLost());
	}

	/**
	 * Redraws the static map into staticMapBuffer (tiles, obstacles).
	 */
	private void redrawStaticMapBuffer() {
		if (staticMapBuffer == null || level == null) return;
		do {
			Graphics2D g2 = staticMapBuffer.createGraphics();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
				g2.setColor(getBackground());
				g2.fillRect(0, 0, staticMapBuffer.getWidth(), staticMapBuffer.getHeight());
				level.draw(g2);
			} finally {
				g2.dispose();
			}
		} while (staticMapBuffer.contentsLost());
	}

	/* ----------------------
       Update & render
       ---------------------- */
	private void update(long deltaNano) {
		if (player == null) return;

		if (left && !right) player.setDirectionX(-1);
		else if (right && !left) player.setDirectionX(1);
		else player.setDirectionX(0);

		// handle jump press/release edges
		if (jump && !prevJump) {
			if (player != null) player.jump(); // rising edge
		} else if (!jump && prevJump) {
			if (player != null) player.releaseJump(); // falling edge
		}
		prevJump = jump;

		player.move(deltaNano, level);
		if (missile != null && missile.getActive()) missile.move(deltaNano, level);
	}

	private void render(BufferStrategy bs) {
		// Ensure buffers exist (cheap checks)
		ensureBuffers();

		// Typical BufferStrategy draw loop
		do {
			do {
				Graphics2D g = (Graphics2D) bs.getDrawGraphics();
				try {
					// Draw pre-rendered background (opaque) then static map layer
					if (backgroundBuffer != null) {
						g.drawImage(backgroundBuffer, 0, 0, null);
					} else {
						g.setColor(getBackground());
						g.fillRect(0, 0, getWidth(), getHeight());
					}

					if (staticMapBuffer != null) {
						g.drawImage(staticMapBuffer, 0, 0, null);
					} else {
						if (level != null) level.draw(g);
					}

					// Draw dynamic entities
					if (player != null) player.draw(g);
					if (missile != null && missile.getActive()) missile.draw(g);

					// Draw UI / diagnostics (avoid per-frame allocations)
					sb.setLength(0);
					sb.append("A/D or \u2190/\u2192 to move. Space to jump. F to fire.");
					g.setColor(Color.BLACK);
					g.drawString(sb.toString(), 10, 20);

					sb.setLength(0);
					sb.append("FPS: ").append(fps).append("  Frame: ").append(df.format(lastFrameMs)).append(" ms");
					g.drawString(sb.toString(), 10, 40);
				} finally {
					g.dispose();
				}
			} while (bs.contentsRestored());

			bs.show();
			Toolkit.getDefaultToolkit().sync(); // helps on some platforms (Linux) to flush the display pipeline

			// After showing, check if volatile images lost their contents and redraw if necessary.
			// This avoids validating every frame and only reacts when loss actually happens.
			if (backgroundBuffer != null && backgroundBuffer.contentsLost()) {
				redrawBackgroundBuffer();
			}
			if (staticMapBuffer != null && staticMapBuffer.contentsLost()) {
				redrawStaticMapBuffer();
			}

		} while (bs.contentsLost());
	}

	/* ----------------------
       Input setters
       ---------------------- */
	public void setLeft(boolean v) { left = v; }
	public void setRight(boolean v) { right = v; }
	public void setJump(boolean v) { jump = v; }

	public void fire() {
		if (missile == null || !missile.getActive()) {
			// Use cached converted missile image to avoid per-fire conversion
			BufferedImage mImg = cachedMissileImage;
			if (mImg == null) mImg = toCompatibleImage(loadImage("/missile.png"));
			missile = new MissileEntity(mImg, player.getX() + 12, player.getY(), 400);
			missile.setActive(true);
		}
	}
}