package gymnasiearbete;

import java.awt.Graphics2D;
import java.awt.Image;
import se.egy.graphics.Drawable;

public abstract class Entity implements Drawable {

    protected Image image;
    protected double xPos, yPos;
    protected int speed;
    protected int dx = 0, dy = 0;
    private boolean active = true;

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

    @Override
    public void draw(Graphics2D g) {
        if (active && image != null) {
            g.drawImage(image, (int)xPos, (int)yPos, null);
        }
    }

    public abstract void move(long deltaTime, Level level);
}
