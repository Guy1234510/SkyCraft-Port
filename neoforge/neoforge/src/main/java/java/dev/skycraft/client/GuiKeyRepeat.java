package dev.skycraft.client;

/** Frame-driven repeat for the GUI: Skyrim sends keyboard edges, not GLFW's repeats. */
public final class GuiKeyRepeat {
	private static final long DELAY = 500_000_000L, PERIOD = 33_000_000L;
	private int key = -1, scan, codePoint;
	private Object screen;
	private long next;
	public void press(int key, int scan, Object screen, long now) {
		this.key = key;
		this.scan = scan;
		this.screen = screen;
		this.codePoint = 0;
		this.next = now + DELAY;
	}
	public void release(int key) { if (this.key == key) reset(); }
	public void text(int codePoint, long now) {
		if (key < 0) return;
		if (this.codePoint != 0) sourceRepeat(now); // don't duplicate repeats from a text source
		this.codePoint = codePoint;
	}
	public void sourceRepeat(long now) { if (key >= 0) next = now + PERIOD; }
	public void reset() { key = -1; screen = null; codePoint = 0; }
	public int key() { return key; }
	public int scan() { return scan; }
	public int codePoint() { return codePoint; }
	public boolean due(Object screen, long now) {
		if (screen == null || screen != this.screen) { reset(); return false; }
		if (key < 0 || now < next) return false;
		// A stalled frame must not replay hundreds of edits at once.
		next = now + PERIOD;
		return true;
	}
}
