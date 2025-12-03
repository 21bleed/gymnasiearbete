package gymnasiearbete;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.util.Random;

public class Level {
	public int tileSize = 32;
	public int cols, rows;
	public int width, height;

	private int[][] tiles;
	private Image tileImage;
	private Image obstacleImage;
	private Image pointImage;
	private Image tileImage0;
	private Image tileImage1;

	@SuppressWarnings("unused")
	private final Random rand = new Random();

	public Level(int cols, int rows) {
		this.cols = cols;
		this.rows = rows;
		width = cols * tileSize;
		height = rows * tileSize;
		tiles = new int[rows][cols];
	}

	public void setTileImage(Image img) {
		this.tileImage = img;
	}
	public void setTileImage0(Image img) { 
		this.tileImage0 = img; 
	}
	public void setTileImage1(Image img) {
		this.tileImage1 = img;
	}
	public void setObstacleImage(Image img) {
		this.obstacleImage = img;
	}
	public void setPointImage(Image img) { 
		this.pointImage = img; 
	}

	public void setTile(int col, int row, int val) {
		if (inBounds(col, row)) tiles[row][col] = val;
	}

	public int getTile(int col, int row) {
		if (inBounds(col, row)) return tiles[row][col];
		return 1; // out of bounds = solid
	}

	private boolean inBounds(int col, int row) {
		return col >= 0 && col < cols && row >= 0 && row < rows;
	}

	public boolean collidesAt(double x, double y, int w, int h) {
		int left = (int)Math.floor(x / tileSize);
		int right = (int)Math.floor((x + w - 1) / tileSize);
		int top = (int)Math.floor(y / tileSize);
		int bottom = (int)Math.floor((y + h - 1) / tileSize);

		for (int r = top; r <= bottom; r++) {
			for (int c = left; c <= right; c++) {
				if (isSolid(c, r)) return true;
			}
		}
		return false;
	}

	private boolean isSolid(int col, int row) {
		int t = getTile(col, row);
		return switch (t) {
		case 1, 2, 3 -> true;
		default -> false;
		};
	}


	public void draw(Graphics2D g) {
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				int tile = tiles[r][c];
				if (tile == 0) continue;

				Image img = switch (tile) {
				case 1 -> tileImage;
				case 2 -> tileImage0;
				case 3 -> tileImage1;
				case 4 -> obstacleImage;
				case 5 -> pointImage;
				default -> null;
				};

				int x = c * tileSize;
				int y = r * tileSize;

				if (img != null) {
					g.drawImage(img, x, y, tileSize, tileSize, null);
				} else {
					g.setColor(tile == 1 ? Color.GRAY :
						(tile == 2 ? Color.RED : Color.YELLOW));
					g.fillRect(x, y, tileSize, tileSize);
				}
			}
		}
	}
}
