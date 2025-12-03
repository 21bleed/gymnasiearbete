package gymnasiearbete;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

@SuppressWarnings("serial")
public class GameWindow extends JFrame {

    private final GamePanel gamePanel;

    public GameWindow() {
        super("Gymnasiearbete Platformer");
        System.setProperty("sun.java2d.opengl", "true");

        gamePanel = new GamePanel();
        add(gamePanel, BorderLayout.CENTER);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        gamePanel.requestFocusInWindow();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gamePanel.stop();
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                gamePanel.requestFocusInWindow();
            }
        });
    }

    public void start() {
        setVisible(true);
        gamePanel.start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            GameWindow window = new GameWindow();
            window.start();
        });
    }
}
