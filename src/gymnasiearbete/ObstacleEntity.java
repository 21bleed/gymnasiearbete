package gymnasiearbete;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Color;

/**
 * Simple rectangular obstacle that acts as a solid block in the level.
 */
public class ObstacleEntity extends Entity {
    private int width;
    private int height;
    private Color debugColor = new Color(160, 82, 45);

    public ObstacleEntity(Image image, double x, double y, int w, int h) {
        super(image, x, y, 0);
        this.width = w;
        this.height = h;
        this.setActive(true);
    }

    @Override
    public void move(long deltaTime, Level level) {
        // obstacles are static
    }

    @Override
    public void draw(Graphics2D g) {
        if (!getActive()) return;
        if (image != null) {
            g.drawImage(image, (int)xPos, (int)yPos, width, height, null);
        } else {
            Color old = g.getColor();
            g.setColor(debugColor);
            g.fillRect((int)xPos, (int)yPos, width, height);
            g.setColor(old);
        }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
