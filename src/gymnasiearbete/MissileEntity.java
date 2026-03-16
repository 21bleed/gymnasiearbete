package gymnasiearbete;

import java.awt.Image;

public class MissileEntity extends Entity {

	private static final int OFFSCREEN_MARGIN = 64; 


	private double vx;
	private double vy;
	private long lifeNs; // remaining life in nanoseconds
	private int spellId = 0; // type of missile/spell
    private int owner = 0; // 0 = none, 1 = player, 2 = enemy

	// gravity used for lobbed spells
	private static final double MISSILE_GRAVITY = 900.0; // px/s^2 for lobbed projectiles

	public MissileEntity(Image image, double xPos, double yPos, int speed) {
		this(image, xPos, yPos, speed, 1, 2000_000_000L, 0);
	}

	public MissileEntity(Image image, double xPos, double yPos, int speed, int direction) {
		this(image, xPos, yPos, speed, direction, 2000_000_000L, 0);
	}

	public MissileEntity(Image image, double xPos, double yPos, int speed, int direction, long lifeNs, int spellId) {
		super(image, xPos, yPos, speed);
		int dir = (direction >= 0) ? 1 : -1; 
		this.vx = dir * Math.abs(speed);
		this.vy = 0.0;
		this.dx = dir; 
		this.lifeNs = lifeNs;
		this.spellId = spellId;
		setActive(true);
		// small default hitbox centered on the 8x8 visual box
		this.setHitbox(0,0,8,8);
	}

	/** Reset an existing missile instance for reuse (pooling). */
	public void reset(Image image, double xPos, double yPos, int speed, int direction, long lifeNs, int spellId) {
		this.image = image;
		this.xPos = xPos;
		this.yPos = yPos;
		int dir = (direction >= 0) ? 1 : -1;
		this.dx = dir;
		this.lifeNs = lifeNs;
		this.spellId = spellId;
		// initialize velocity depending on spell
		switch (spellId) {
		case 1: // lobbed arc
			this.vx = dir * Math.abs(speed) * 0.8;
			this.vy = -Math.abs(speed) * 0.6; // initial upward impulse
			break;
		case 0: // default straight fast bullet
		default:
			this.vx = dir * Math.abs(speed);
			this.vy = 0.0;
			break;
		}
		// reset hitbox to default 8x8
		this.setHitbox(0,0,8,8);
		setActive(true);
	}

    public void setOwner(int owner) { this.owner = owner; }
    public int getOwner() { return owner; }

    public void setVelocity(double vx, double vy) { this.vx = vx; this.vy = vy; }

    public double getVX() { return vx; }
    public double getVY() { return vy; }

	@Override
	public void move(long deltaTime, Level level) {
		if (!getActive()) return;

		double dt = deltaTime / 1_000_000_000.0;
		xPos += vx * dt;
		yPos += vy * dt;
		lifeNs -= deltaTime;

		// update behavior: lobbed spells accelerate downward
		switch (spellId) {
		case 1:
			vy += MISSILE_GRAVITY * dt;
			break;
		default:
			// straight bullets unaffected
			break;
		}

		// Deactivate when life ended or outside bounds
		double leftBound = -OFFSCREEN_MARGIN;
		double rightBound = (level != null ? level.width : 0) + OFFSCREEN_MARGIN;
		double topBound = -OFFSCREEN_MARGIN;
		double bottomBound = (level != null ? level.height : 0) + OFFSCREEN_MARGIN;

		if (lifeNs <= 0 || xPos < leftBound || xPos > rightBound || yPos < topBound || yPos > bottomBound) {
			setActive(false);
			return;
		}

		// Simple collision with solid tiles: if missile hits a solid tile, deactivate
		if (level != null) {
			// use a small bounding box for missiles
			if (level.collidesAt(xPos, yPos, getHitboxWidth(), getHitboxHeight())) {
				setActive(false);
				return;
			}
		}
	}

	// Helper to make this type obviously present for incremental compilers/IDEs
	@SuppressWarnings("unused")
	public static void ensureLoaded() {
		// no-op
	}
}