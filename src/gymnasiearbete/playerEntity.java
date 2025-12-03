package gymnasiearbete;

import java.awt.Image;

public class playerEntity extends Entity {

    // Vertical state
    private double yVelocity = 0.0;
    private static final double GRAVITY = 1200.0;        // px/s^2
    private static final double JUMP_VELOCITY = -620.0; // initial jump impulse (px/s)
    private static final double MAX_FALL_SPEED = 2000.0; // terminal velocity (px/s)

    // Horizontal state (smooth movement)
    private double xVelocity = 0.0;
    private static final double GROUND_ACCEL = 4000.0;  // px/s^2
    private static final double AIR_ACCEL = 1800.0;     // px/s^2
    private static final double GROUND_FRICTION = 3000.0; // px/s^2

    // Collision box
    private final int width = 23;
    private final int height = 25;

    // Small tolerance to treat very small velocities as zero after landing
    private static final double REST_VELOCITY_THRESHOLD = 30.0;

    // Jump responsiveness helpers
    private static final double COYOTE_TIME = 0.08;      // seconds after leaving edge where jump still works
    private static final double JUMP_BUFFER_TIME = 0.12; // seconds before landing where a jump press is buffered

    private double coyoteTimer = 0.0;
    private double jumpBufferTimer = 0.0;
    @SuppressWarnings("unused")
	private boolean jumpPressed = false;

    private boolean onGround = false;

    public playerEntity(Image image, double xPos, double yPos, int speed) {
        super(image, xPos, yPos, speed);
    }

    /**
     * Called by input when jump key is pressed.
     * This buffers the jump and will perform it when possible (immediate if on ground).
     */
    public void jump() {
        jumpPressed = true;
        jumpBufferTimer = JUMP_BUFFER_TIME;
    }

    /**
     * Call this when the jump key is released to enable variable jump height.
     * If the player is moving upward, reduce upward velocity for a shorter jump.
     */
    public void releaseJump() {
        jumpPressed = false;
        if (yVelocity < 0) {
            yVelocity *= 0.45; // shorten upward velocity for variable jump height
        }
        jumpBufferTimer = 0.0;
    }

    @Override
    public void move(long deltaTime, Level level) {
        if (level == null) return;

        double dt = deltaTime / 1_000_000_000.0;
        if (dt <= 0) return;

        // Update timers
        if (onGround) {
            coyoteTimer = COYOTE_TIME;
        } else {
            coyoteTimer = Math.max(0.0, coyoteTimer - dt);
        }
        if (jumpBufferTimer > 0.0) {
            jumpBufferTimer = Math.max(0.0, jumpBufferTimer - dt);
        }

        // HORIZONTAL MOVEMENT (smooth acceleration + friction)
        double targetVelX = dx * speed; // dx is -1, 0, or 1 (inherited from Entity)
        double accel = onGround ? GROUND_ACCEL : AIR_ACCEL;

        if (Math.abs(targetVelX - xVelocity) > 1e-3) {
            double dv = accel * dt;
            if (targetVelX > xVelocity) {
                xVelocity = Math.min(targetVelX, xVelocity + dv);
            } else {
                xVelocity = Math.max(targetVelX, xVelocity - dv);
            }
        } else {
            xVelocity = targetVelX;
        }

        // apply ground friction when no input and on ground
        if (onGround && dx == 0 && Math.abs(xVelocity) > 1e-3) {
            double frictionStep = GROUND_FRICTION * dt;
            if (xVelocity > 0) {
                xVelocity = Math.max(0.0, xVelocity - frictionStep);
            } else {
                xVelocity = Math.min(0.0, xVelocity + frictionStep);
            }
        }

        // Horizontal collision: move horizontally first
        double nextX = xPos + xVelocity * dt;
        if (!level.collidesAt(nextX, yPos, width, height)) {
            xPos = nextX;
        } else {
            // Snap to tile edge depending on movement direction
            if (xVelocity > 0) {
                int tile = (int) Math.floor((xPos + width) / level.tileSize);
                xPos = tile * level.tileSize - width - 0.001;
            } else if (xVelocity < 0) {
                int tile = (int) Math.floor(xPos / level.tileSize);
                xPos = (tile + 1) * level.tileSize + 0.001;
            }
            xVelocity = 0.0;
        }

        // VERTICAL MOVEMENT (gravity + collisions)
        yVelocity += GRAVITY * dt;
        if (yVelocity > MAX_FALL_SPEED) yVelocity = MAX_FALL_SPEED;

        double nextY = yPos + yVelocity * dt;

        if (!level.collidesAt(xPos, nextY, width, height)) {
            yPos = nextY;
            onGround = false;
        } else {
            if (yVelocity > 0) {
                int footTileRow = (int) Math.floor((nextY + height) / level.tileSize);
                yPos = footTileRow * level.tileSize - height;
                yVelocity = 0.0;
                onGround = true;
            } else {
                int headTileRow = (int) Math.floor(nextY / level.tileSize);
                yPos = (headTileRow + 1) * level.tileSize + 0.001;
                yVelocity = 0.0;
                onGround = false;
            }
        }

        if (onGround) coyoteTimer = COYOTE_TIME;

        // JUMP HANDLING: buffered jump + coyote time
        if (jumpBufferTimer > 0.0 && (onGround || coyoteTimer > 0.0)) {
            yVelocity = JUMP_VELOCITY;
            onGround = false;
            jumpBufferTimer = 0.0;
            jumpPressed = false;
            coyoteTimer = 0.0;
        }

        // small clamp to avoid micro-bounces
        if (onGround && Math.abs(yVelocity) < REST_VELOCITY_THRESHOLD) {
            yVelocity = 0.0;
        }
    }

    // Convenience getters
    public int getX() { return (int) Math.round(xPos); }
    public int getY() { return (int) Math.round(yPos); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isOnGround() { return onGround; }
}
