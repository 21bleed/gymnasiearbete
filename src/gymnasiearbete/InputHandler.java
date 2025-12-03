package gymnasiearbete;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class InputHandler extends KeyAdapter {

    private GamePanel panel;

    public InputHandler(GamePanel panel) {
        this.panel = panel;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) panel.setLeft(true);
        if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) panel.setRight(true);
        if (k == KeyEvent.VK_SPACE || k == KeyEvent.VK_W || k == KeyEvent.VK_UP) panel.setJump(true);
        if (k == KeyEvent.VK_F) panel.fire();
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) panel.setLeft(false);
        if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) panel.setRight(false);
        if (k == KeyEvent.VK_SPACE || k == KeyEvent.VK_W || k == KeyEvent.VK_UP) panel.setJump(false);
    }
}
