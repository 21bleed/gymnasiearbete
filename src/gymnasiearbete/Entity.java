package gymnasiearbete;

import java.awt.Graphics2D;
import java.awt.Image;

public abstract class Entity {

    protected Image image;
    protected Image[] frames = null; // animation frames (optional)
    protected Image[][] animations = null; // optional animations arranged by rows
    protected double animTime = 0.0; // seconds
    protected int animIndex = 0;
    protected double animFPS = 16.0; // default fps for animations
    protected int currentAnim = 0;

    protected double xPos, yPos;
    protected int speed;
    protected int dx = 0, dy = 0;
    private boolean active = true;

    // hitbox: offsets are in pixels relative to xPos/yPos (top-left). If hbW/hbH <= 0 the caller should fall back
    // to the entity's visual width/height (subclasses expose getWidth/getHeight()).
    protected int hbOffsetX = 0;
    protected int hbOffsetY = 0;
    protected int hbWidth = -1;
    protected int hbHeight = -1;

    public Entity(Image image, double xPos, double yPos, int speed) {
        this.image = image;
        this.xPos = xPos;
        this.yPos = yPos;
        this.speed = speed;
    }

    public boolean getActive() { return active; }
    public void setActive(boolean v) { active = v; }

    public void setDirectionX(int dx) { this.dx = dx; }
    public void setDirectionY(int dy) { this.dy = dy; }

    // allow subclasses or external code to set animation frames and FPS
    public void setFrames(Image[] frames, double fps) {
        this.frames = frames;
        this.animFPS = (fps > 0.0) ? fps : 8.0;
        this.animTime = 0.0;
        this.animIndex = 0;
    }

    // support multiple animation rows (each row is a sequence)
    public void setAnimations(Image[][] anims, double fps) {
        this.animations = anims;
        this.animFPS = (fps > 0.0) ? fps : 8.0;
        this.animTime = 0.0;
        this.animIndex = 0;
        this.currentAnim = 0;
    }

    public void setAnimationIndex(int idx) { this.animIndex = idx; this.animTime = 0.0; }

    public void setCurrentAnimation(int row, double fps) {
        if (animations == null) return;
        if (row < 0) row = 0;
        if (row >= animations.length) row = 0;
        if (currentAnim != row) {
            currentAnim = row;
            animIndex = 0;
            animTime = 0.0;
        }
        if (fps > 0) animFPS = fps;
    }

    // advance animation by dt seconds (call from move implementations)
    public void updateAnimation(double dt) {
        Image[] seq = null;
        if (animations != null && animations.length > 0) {
            int row = Math.max(0, Math.min(currentAnim, animations.length - 1));
            seq = animations[row];
        } else if (frames != null && frames.length > 0) {
            seq = frames;
        }
        if (seq == null || seq.length == 0) return;
        animTime += dt;
        double period = 1.0 / animFPS;
        if (period <= 0) period = 1.0 / 8.0;
        int idx = (int) Math.floor(animTime / period) % seq.length;
        if (idx < 0) idx = 0;
        animIndex = idx;
    }

    public void draw(Graphics2D g) {
        if (active) {
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
            if (drawImg != null) g.drawImage(drawImg, (int)Math.round(xPos), (int)Math.round(yPos), null);
        }
    }

    public abstract void move(long deltaTime, Level level);

    // Simple position accessors so other systems can query entities generically
    public int getX() { return (int)Math.round(xPos); }
    public int getY() { return (int)Math.round(yPos); }

    // Hitbox API: subclasses may override getWidth/getHeight; by default hbWidth/hbHeight
    // if set (>0) are used; otherwise caller should fall back to visual size exposed by subclass.
    public void setHitbox(int offsetX, int offsetY, int width, int height) {
        this.hbOffsetX = offsetX;
        this.hbOffsetY = offsetY;
        this.hbWidth = width;
        this.hbHeight = height;
    }

    // returns top-left X of hitbox (double precision)
    public double getHitboxX() { return xPos + hbOffsetX; }
    // returns top-left Y of hitbox
    public double getHitboxY() { return yPos + hbOffsetY; }
    // width in pixels; if not set, subclasses should provide width via getWidth()
    public int getHitboxWidth() {
        if (hbWidth > 0) return hbWidth;
        // fallback to known subclass widths if available
        if (this instanceof playerEntity) return ((playerEntity)this).getWidth();
        if (this instanceof gymnasiearbete.EnemyEntity) return ((gymnasiearbete.EnemyEntity)this).getWidth();
        if (this instanceof gymnasiearbete.ObstacleEntity) return ((gymnasiearbete.ObstacleEntity)this).getWidth();
        return 32;
    }
    // height in pixels; if not set, subclasses should provide height via getHeight()
    public int getHitboxHeight() {
        if (hbHeight > 0) return hbHeight;
        if (this instanceof playerEntity) return ((playerEntity)this).getHeight();
        if (this instanceof gymnasiearbete.EnemyEntity) return ((gymnasiearbete.EnemyEntity)this).getHeight();
        if (this instanceof gymnasiearbete.ObstacleEntity) return ((gymnasiearbete.ObstacleEntity)this).getHeight();
        return 32;
    }
}