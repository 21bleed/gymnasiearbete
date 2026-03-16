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

    // Collision box: use 32x32 so tiles are consistent
    // Visual/player size increased so the player is larger than enemies
    private final int width = 48;
    private final int height = 48;

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
    private int health = 5;

    // which animation rows map to states (defaults kept as 0:idle,1:run,2:jump,3:fall)
    private int animRowIdle = 0;
    private int animRowRun = 1;
    private int animRowJump = 2;
    private int animRowFall = 3;

    // per-state FPS values (configurable via mapping file)
    private double fpsIdle = 6.0;
    private double fpsRun = 14.0;
    private double fpsJump = 1.0;
    private double fpsFall = 10.0;

    // facing: 1 = right, -1 = left
    private int facing = 1;

    public playerEntity(Image image, double xPos, double yPos, int speed) {
        super(image, xPos, yPos, speed);
    }

    // allow external mapping of animation rows
    public void setAnimationMapping(int idleRow, int runRow, int jumpRow, int fallRow) {
        this.animRowIdle = idleRow;
        this.animRowRun = runRow;
        this.animRowJump = jumpRow;
        this.animRowFall = fallRow;
    }

    // allow external code to set per-state FPS (e.g. from mapping file)
    public void setAnimationStateFPS(double idleFps, double runFps, double jumpFps, double fallFps) {
        if (idleFps > 0) this.fpsIdle = idleFps;
        if (runFps > 0) this.fpsRun = runFps;
        if (jumpFps > 0) this.fpsJump = jumpFps;
        if (fallFps > 0) this.fpsFall = fallFps;
    }

    @Override
    public void setDirectionX(int dx) {
        super.setDirectionX(dx);
        if (dx < 0) facing = -1;
        else if (dx > 0) facing = 1;
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

        // choose animation row based on state BEFORE advancing animation to avoid frame jitter
        int animRow = animRowIdle;
        // if a jump is buffered and will happen, show jump immediately
        if (jumpBufferTimer > 0.0 && (onGround || coyoteTimer > 0.0)) {
            animRow = animRowJump;
        } else if (!onGround) {
            if (yVelocity < 0) animRow = animRowJump; else animRow = animRowFall;
        } else {
            if (Math.abs(xVelocity) > 5.0) animRow = animRowRun; else animRow = animRowIdle;
        }
        // choose FPS per state to make run feel smoother and avoid ghosting when switching
        double animFps = 10.0;
        if (animRow == animRowRun) animFps = fpsRun; // faster frame rate for running
        else if (animRow == animRowIdle) animFps = fpsIdle; // idle animation fps
        else if (animRow == animRowJump) animFps = fpsJump; // jump fps (usually single-frame)
        else if (animRow == animRowFall) animFps = fpsFall;
        setCurrentAnimation(animRow, animFps);
        // advance animation timing
        updateAnimation(dt);

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

        // Horizontal collision: move horizontally first using small sub-steps to avoid tunneling
        double remainingX = xVelocity * dt;
        double maxStep = Math.max(1.0, level.tileSize * 0.5); // step size roughly half tile to be safe
        int stepsX = Math.max(1, (int)Math.ceil(Math.abs(remainingX) / maxStep));
        double stepX = remainingX / stepsX;

        for (int i = 0; i < stepsX; i++) {
            double testX = xPos + stepX;
            if (!level.collidesAt(testX, yPos, width, height)) {
                xPos = testX;
            } else {
                // Snap to tile edge depending on movement direction using the test position
                if (stepX > 0) {
                    int tile = (int) Math.floor((testX + width) / level.tileSize);
                    xPos = tile * level.tileSize - width - 0.0001;
                } else if (stepX < 0) {
                    int tile = (int) Math.floor(testX / level.tileSize);
                    xPos = (tile + 1) * level.tileSize + 0.0001;
                }
                xVelocity = 0.0;
                break;
            }
        }

        // VERTICAL MOVEMENT (gravity + collisions), with sub-steps
        yVelocity += GRAVITY * dt;
        if (yVelocity > MAX_FALL_SPEED) yVelocity = MAX_FALL_SPEED;

        double remainingY = yVelocity * dt;
        int stepsY = Math.max(1, (int)Math.ceil(Math.abs(remainingY) / Math.max(1.0, level.tileSize * 0.5)));
        double stepY = remainingY / stepsY;

        boolean landedThisFrame = false;
        for (int i = 0; i < stepsY; i++) {
            double testY = yPos + stepY;
            if (!level.collidesAt(xPos, testY, width, height)) {
                yPos = testY;
                onGround = false;
            } else {
                if (stepY > 0) {
                    int footTileRow = (int) Math.floor((testY + height) / level.tileSize);
                    yPos = footTileRow * level.tileSize - height;
                    yVelocity = 0.0;
                    onGround = true;
                    landedThisFrame = true;
                    // nudge slightly upward to avoid immediate re-collision in subsequent steps
                    yPos -= 0.001;
                } else if (stepY < 0) {
                    int headTileRow = (int) Math.floor(testY / level.tileSize);
                    yPos = (headTileRow + 1) * level.tileSize + 0.0001;
                    yVelocity = 0.0;
                    onGround = false;
                    // nudge slightly downward to avoid sticking to ceiling
                    yPos += 0.001;
                }
                break;
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

    @Override
    public void draw(java.awt.Graphics2D g) {
        // select frame
        Image drawImg = null;
        if (animations != null && animations.length > 0) {
            int row = Math.max(0, Math.min(currentAnim, animations.length - 1));
            Image[] seq = animations[row];
            if (seq != null && seq.length > 0) drawImg = seq[Math.max(0, Math.min(animIndex, seq.length-1))];
        } else if (frames != null && frames.length > 0) {
            if (animIndex >= 0 && animIndex < frames.length) drawImg = frames[animIndex];
        } else if (image != null) {
            drawImg = image;
        }
        if (drawImg == null) return;

        int ix = (int)Math.round(xPos);
        int iy = (int)Math.round(yPos);
        if (facing >= 0) {
            g.drawImage(drawImg, ix, iy, width, height, null);
        } else {
            // flip horizontally around center
            java.awt.geom.AffineTransform old = g.getTransform();
            g.translate(ix + width, iy);
            g.scale(-1, 1);
            g.drawImage(drawImg, 0, 0, width, height, null);
            g.setTransform(old);
        }
    }

    // Convenience getters
    public int getX() { return (int) Math.round(xPos); }
    public int getY() { return (int) Math.round(yPos); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isOnGround() { return onGround; }
    public void takeDamage(int amount) {
        health -= amount;
        if (health < 0) health = 0;
        System.out.println("playerEntity.takeDamage: amount=" + amount + " health=" + health);
    }

    public int getHealth() { return health; }

    public boolean isDead() { return health <= 0; }

    // Allow the panel to reset the player (for restart)
    public void reset(Image img, double x, double y, int speed) {
        this.image = img;
        this.xPos = x;
        this.yPos = y;
        this.speed = speed;
        this.xVelocity = 0.0;
        this.yVelocity = 0.0;
        this.onGround = false;
        this.coyoteTimer = 0.0;
        this.jumpBufferTimer = 0.0;
        this.health = 5; // default starting health
        this.setActive(true);
    }

    public int getMaxHealth() { return 5; }
}