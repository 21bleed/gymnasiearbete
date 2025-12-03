package gymnasiearbete;

import java.awt.Image;

public class MissileEntity extends Entity {

	private static final int OFFSCREEN_MARGIN = 64; 


	private final double vx;
	private final double vy;

	public MissileEntity(Image image, double xPos, double yPos, int speed) {
		this(image, xPos, yPos, speed, 1);
	}

	public MissileEntity(Image image, double xPos, double yPos, int speed, int direction) {
		super(image, xPos, yPos, speed);
		int dir = (direction >= 0) ? 1 : -1; 
		this.vx = dir * Math.abs(speed);
		this.vy = 0.0;
		this.dx = dir; 
		setActive(true);
	}

	@Override
	public void move(long deltaTime, Level level) {
		// If the missile is already inactive, skip work
		if (!getActive()) return;

		// deltaTime is in nanoseconds; convert to seconds
		double dt = deltaTime / 1_000_000_000.0;

		// Integrate position using velocity (vx, vy)
		xPos += vx * dt;
		yPos += vy * dt;

		// Deactivate when far outside the level bounds (use OFFSCREEN_MARGIN)
		double leftBound = -OFFSCREEN_MARGIN;
		double rightBound = (level != null ? level.width : 0) + OFFSCREEN_MARGIN;
		double topBound = -OFFSCREEN_MARGIN;
		double bottomBound = (level != null ? level.height : 0) + OFFSCREEN_MARGIN;

		if (xPos < leftBound || xPos > rightBound || yPos < topBound || yPos > bottomBound) {
			setActive(false);
			return;
		}

	}
}
