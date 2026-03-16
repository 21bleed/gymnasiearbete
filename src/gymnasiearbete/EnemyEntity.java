package gymnasiearbete;

import java.awt.Image;
import java.awt.Graphics2D;
import java.awt.Color;

/**
 * Simple enemy that patrols between two X bounds and occasionally jumps.
 * Behaves similarly to player physics but simplified.
 */
public class EnemyEntity extends Entity {
    private double yVelocity = 0.0;
    private double xVelocity = 0.0;
    private final int width = 32;
    private final int height = 32;

    private static final double GRAVITY = 1200.0;
    private static final double MAX_FALL_SPEED = 2000.0;
    private static final double WALK_SPEED = 90.0; // px/s
    private static final double JUMP_VELOCITY = -420.0;

    private int dir = 1; // 1 = right, -1 = left
    private int leftBound, rightBound; // world coordinates for patrol
    private double jumpCooldown = 0.0;

    // health and shooting state
    private int health = 3;
    private int maxHealth = 3;
    private double shootCooldown = 0.0;

    public EnemyEntity(Image image, double x, double y, int leftBound, int rightBound) {
        super(image, x, y, 0);
        this.leftBound = leftBound;
        this.rightBound = rightBound;
        this.dir = 1;
        this.setActive(true);
        // set hitbox to full visual size by default
        this.setHitbox(0, 0, width, height);
    }

    @Override
    public void move(long deltaTime, Level level) {
        if (!getActive()) return;
        double dt = deltaTime / 1_000_000_000.0;
        if (dt <= 0) return;

        // advance animation
        updateAnimation(dt);

        // simple patrol: move horizontally, reverse at bounds or on obstacle
        xVelocity = WALK_SPEED * dir;

        // horizontal step with collision
        double remainingX = xVelocity * dt;
        int stepsX = Math.max(1, (int)Math.ceil(Math.abs(remainingX) / Math.max(1.0, level.tileSize * 0.5)));
        double stepX = remainingX / stepsX;
        boolean hit = false;
        for (int i = 0; i < stepsX; i++) {
            double testX = xPos + stepX;
            if (!level.collidesAt(testX, yPos, width, height)) {
                xPos = testX;
            } else {
                // reverse on collision
                xVelocity = 0;
                dir = -dir;
                hit = true;
                break;
            }
        }

        // Jump occasionally when cooldown elapsed or when cornered
        jumpCooldown -= dt;
        if ((xPos < leftBound && dir < 0) || (xPos > rightBound && dir > 0)) {
            dir = -dir; // reverse if out of patrol bounds
        }
        if (jumpCooldown <= 0 && Math.random() < 0.05) {
            // jump
            yVelocity = JUMP_VELOCITY;
            jumpCooldown = 1.0 + Math.random() * 2.0;
        }

        // gravity
        yVelocity += GRAVITY * dt;
        if (yVelocity > MAX_FALL_SPEED) yVelocity = MAX_FALL_SPEED;

        double remainingY = yVelocity * dt;
        int stepsY = Math.max(1, (int)Math.ceil(Math.abs(remainingY) / Math.max(1.0, level.tileSize * 0.5)));
        double stepY = remainingY / stepsY;
        for (int i = 0; i < stepsY; i++) {
            double testY = yPos + stepY;
            if (!level.collidesAt(xPos, testY, width, height)) {
                yPos = testY;
            } else {
                if (stepY > 0) {
                    int footTileRow = (int) Math.floor((testY + height) / level.tileSize);
                    yPos = footTileRow * level.tileSize - height;
                    yVelocity = 0.0;
                } else if (stepY < 0) {
                    int headTileRow = (int) Math.floor(testY / level.tileSize);
                    yPos = (headTileRow + 1) * level.tileSize + 0.0001;
                    yVelocity = 0.0;
                }
                break;
            }
        }

        // if reached patrol bounds, reverse
        if (xPos < leftBound) { xPos = leftBound; dir = 1; }
        if (xPos > rightBound) { xPos = rightBound; dir = -1; }

        // cooldown for shooting
        shootCooldown = Math.max(0.0, shootCooldown - dt);

        // die if health <= 0
        if (health <= 0) setActive(false);
    }

    @Override
    public void draw(Graphics2D g) {
        if (!getActive()) return;
        // prefer animation frames if present, otherwise image; scale to width/height
        if (frames != null && frames.length > 0) {
            Image f = frames[animIndex];
            if (f != null) g.drawImage(f, (int)Math.round(xPos), (int)Math.round(yPos), width, height, null);
            return;
        }
        if (image != null) {
            g.drawImage(image, (int)Math.round(xPos), (int)Math.round(yPos), width, height, null);
        } else {
            // fallback: draw a visible placeholder rectangle so enemies are visible
            Color old = g.getColor();
            g.setColor(new Color(180, 30, 30));
            g.fillRect((int)Math.round(xPos), (int)Math.round(yPos), width, height);
            g.setColor(Color.BLACK);
            g.drawRect((int)Math.round(xPos), (int)Math.round(yPos), width, height);
            g.setColor(old);
        }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public int getHealth() { return health; }
    public int getMaxHealth() { return maxHealth; }

    public boolean isDead() { return health <= 0; }

    // allow external systems to nudge direction without direct field access
    public void setDirection(int d) { if (d >= 0) dir = 1; else dir = -1; }

    /** Reset this enemy for reuse from a pool. */
    public void reset(Image image, double x, double y, int leftBound, int rightBound, int healthOverride) {
        this.image = image;
        this.xPos = x;
        this.yPos = y;
        this.leftBound = leftBound;
        this.rightBound = rightBound;
        this.dir = 1;
        this.yVelocity = 0.0;
        this.xVelocity = 0.0;
        this.jumpCooldown = 0.0;
        this.shootCooldown = 0.0;
        if (healthOverride > 0) {
            this.maxHealth = healthOverride;
            this.health = healthOverride;
        } else {
            this.maxHealth = 3;
            this.health = 3;
        }
        // ensure hitbox is correct after reset
        this.setHitbox(0, 0, width, height);
        this.setActive(true);
    }

    /**
     * Attempt to create a missile aimed at the player. This does not add the missile to any list; caller must do that.
     * Uses simple line-of-sight check (no solid tile between enemy and player horizontally) and cooldown.
     */
    public MissileEntity tryCreateMissile(Level level, playerEntity player, Image missileImage) {
        if (!getActive()) return null;
        if (player == null || !player.getActive()) return null;
        if (shootCooldown > 0) return null;

        // Only shoot when roughly on same vertical band and player within some horizontal range
        double dx = player.getX() - this.getX();
        double dy = player.getY() - this.getY();
        double dist = Math.hypot(dx, dy);
        if (dist > 400) return null; // too far
        if (Math.abs(dy) > 80) return null; // too different vertically

        // simple LOS: sample tiles between enemy and player horizontally, ensure no solid tile in between at enemy's foot row
        int steps = Math.max(1, (int)Math.ceil(Math.abs(dx) / level.tileSize));
        int sign = dx >= 0 ? 1 : -1;
        int baseRow = (int)Math.floor((this.getY() + this.getHeight()/2) / level.tileSize);
        boolean blocked = false;
        for (int i = 1; i < steps; i++) {
            int col = (int)Math.floor((this.getX() + i * sign * level.tileSize) / level.tileSize);
            if (level.isTileSolid(baseRow, col)) { blocked = true; break; }
        }
        if (blocked) return null;

        // create missile aimed at player (straight fast bullet)
        int dirSign = dx >= 0 ? 1 : -1;
        double sx = this.xPos + (dirSign > 0 ? this.width : -8);
        double sy = this.yPos + this.height / 2.0;
        MissileEntity m = new MissileEntity(missileImage, sx, sy, 320, dirSign, 2000000000L, 0);
        m.setOwner(2);
        // set minor vertical velocity to help hit moving player
        double aimVY = (player.getY() - sy) * 0.5;
        m.setVelocity(dirSign * Math.abs(320), aimVY);
        shootCooldown = 1.0 + Math.random() * 2.0;
        return m;
    }

    public void takeDamage(int amount) {
        health -= amount;
        if (health < 0) health = 0;
        if (health == 0) setActive(false);
    }

}