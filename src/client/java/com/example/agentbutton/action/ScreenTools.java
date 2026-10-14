package com.example.agentbutton.action;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads and clicks the clickable targets on the currently-open {@link Screen}: ordinary
 * {@link AbstractWidget}s plus the rows of scrolling lists ({@link AbstractSelectionList}),
 * e.g. the world list, resource packs, or ViaFabricPlus's protocol picker.
 *
 * <p>All methods here assume they are called on the Minecraft client thread
 * (see {@code ClientThread}).
 */
public final class ScreenTools {
	private ScreenTools() {}

	/** A flat description of one on-screen clickable target. */
	public record Widget(int index, String type, String label,
	                     int x, int y, int width, int height,
	                     boolean active, boolean visible, boolean hovered, boolean selected) {}

	/**
	 * A clickable thing on the screen: either a {@link AbstractWidget}, or a row of an
	 * {@link AbstractSelectionList} together with the list that owns it. Rows are typed as
	 * {@link GuiEventListener} so any list, whatever its entry type, is handled.
	 */
	public record Target(AbstractWidget widget, AbstractSelectionList<?> list, GuiEventListener entry) {
		static Target of(AbstractWidget w) {
			return new Target(w, null, null);
		}

		static Target row(AbstractSelectionList<?> list, GuiEventListener entry) {
			return new Target(null, list, entry);
		}

		public boolean isRow() {
			return entry != null;
		}
	}

	/**
	 * Collect the clickable targets of a screen, in render order, descending into list
	 * and container widgets ({@link ContainerEventHandler}) so nested controls — e.g. the rows of
	 * the Controls / Key Binds list, or entries of the server list — are reachable too.
	 *
	 * <p>Rows that hold their own widgets (e.g. Key Binds rows) are not reported themselves;
	 * their buttons are. Plain rows (world list, protocol picker, ...) are reported as
	 * clickable targets.
	 */
	public static List<Target> collect(Screen screen) {
		List<Target> out = new ArrayList<>();
		if (screen == null) {
			return out;
		}
		Set<GuiEventListener> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		collectInto(screen.children(), null, out, seen);
		return out;
	}

	private static void collectInto(List<? extends GuiEventListener> elements, AbstractSelectionList<?> owner,
	                                List<Target> out, Set<GuiEventListener> seen) {
		for (GuiEventListener e : elements) {
			if (e == null || !seen.add(e)) {
				continue;
			}
			if (e instanceof AbstractWidget w && !(e instanceof AbstractSelectionList<?>)) {
				out.add(Target.of(w));
			}
			// Every direct child of an AbstractSelectionList is one of its rows.
			if (owner != null && !(e instanceof ContainerEventHandler)) {
				out.add(Target.row(owner, e));
			}
			if (e instanceof ContainerEventHandler parent) {
				collectInto(parent.children(), e instanceof AbstractSelectionList<?> list ? list : null, out, seen);
			}
		}
	}

	/** Describe each clickable target (index, label, geometry, state). */
	public static List<Widget> describe(Screen screen) {
		List<Target> targets = collect(screen);
		List<Widget> out = new ArrayList<>(targets.size());
		for (int i = 0; i < targets.size(); i++) {
			Target t = targets.get(i);
			if (t.isRow()) {
				AbstractSelectionList<?> list = t.list();
				int row = list.children().indexOf(t.entry());
				int top = list.getRowTop(row);
				int bottom = list.getRowBottom(row);
				boolean inView = bottom > list.getY() && top < list.getBottom();
				out.add(new Widget(
						i,
						"row of " + list.getClass().getSimpleName(),
						labelOf(t),
						list.getRowLeft(), top, list.getRowWidth(), bottom - top,
						list.active, list.visible && inView, false,
						list.getSelected() == t.entry()
				));
			} else {
				AbstractWidget w = t.widget();
				out.add(new Widget(
						i,
						w.getClass().getSimpleName(),
						labelOf(t),
						w.getX(), w.getY(), w.getWidth(), w.getHeight(),
						w.active, w.visible, w.isHovered(), false
				));
			}
		}
		return out;
	}

	/** The visible text of a target, or "" if none. List rows use their narration text. */
	public static String labelOf(Target t) {
		if (t.isRow()) {
			if (t.entry() instanceof ObjectSelectionList.Entry<?> e && e.getNarration() != null) {
				return e.getNarration().getString();
			}
			return "";
		}
		AbstractWidget w = t.widget();
		return w.getMessage() == null ? "" : w.getMessage().getString();
	}

	/** Whether the target can be clicked right now (rows scrolled out of view still can). */
	public static boolean isClickable(Target t) {
		AbstractWidget w = t.isRow() ? t.list() : t.widget();
		return w.active && w.visible;
	}

	/** Click a target; see {@link #click(AbstractWidget)} and {@link #clickRow}. */
	public static boolean click(Screen screen, Target t) {
		return t.isRow() ? clickRow(screen, t.list(), t.entry()) : click(t.widget());
	}

	/**
	 * Synthesize a left-click at the widget's centre. Minecraft routes mouse input through
	 * {@link MouseButtonEvent} / {@link MouseButtonInfo} records; calling the widget's own
	 * {@code mouseClicked}/{@code mouseReleased} reproduces a real user click (hit-tests
	 * the coordinates and fires the widget's press action).
	 *
	 * @return true if the widget handled the click
	 */
	public static boolean click(AbstractWidget w) {
		double cx = w.getX() + w.getWidth() / 2.0;
		double cy = w.getY() + w.getHeight() / 2.0;
		MouseButtonEvent click = new MouseButtonEvent(cx, cy, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)); // no modifiers
		boolean handled = w.mouseClicked(click, false);
		w.mouseReleased(click);
		return handled;
	}

	/**
	 * Click a list row: scroll it into the middle of the list, then send the click through
	 * the screen so the list gets focus and selects the row exactly as a real click would
	 * (arrow keys then move within the list).
	 *
	 * @return true if the screen handled the click
	 */
	public static boolean clickRow(Screen screen, AbstractSelectionList<?> list, GuiEventListener entry) {
		int row = list.children().indexOf(entry);
		if (row < 0) {
			return false;
		}
		int top = list.getRowTop(row);
		int height = list.getRowBottom(row) - top;
		list.setScrollAmount(list.scrollAmount() + (top - list.getY()) - (list.getHeight() - height) / 2.0);

		top = list.getRowTop(row);
		double cx = list.getRowLeft() + list.getRowWidth() / 2.0;
		double cy = top + (list.getRowBottom(row) - top) / 2.0;
		MouseButtonEvent click = new MouseButtonEvent(cx, cy, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
		boolean handled = screen.mouseClicked(click, false);
		screen.mouseReleased(click);
		return handled;
	}

	/**
	 * Send a key press + release to the screen. Minecraft delivers keyboard input as
	 * {@link KeyEvent} records; routing them through {@code Screen.keyPressed} reproduces
	 * a real keystroke (used e.g. to set a key while a Key Binds row is armed).
	 *
	 * @param keyCode an {@code InputConstants.KEY_*} code (26.x uses SDL-style scancodes, not GLFW codes)
	 * @return true if the screen handled the press
	 */
	public static boolean pressKey(Screen screen, int keyCode, int modifiers) {
		KeyEvent input = new KeyEvent(keyCode, sdlKeycodeOf(keyCode), modifiers);
		boolean handled = screen.keyPressed(input);
		screen.keyReleased(input);
		return handled;
	}

	/**
	 * The layout keycode for a scancode, which {@link KeyEvent#shortcutKey()} returns and
	 * screens switch on (e.g. arrow-key focus navigation). Follows SDL's rule: printable
	 * keys map to their character, everything else is {@code scancode | 1 << 30}.
	 */
	private static int sdlKeycodeOf(int scancode) {
		if (scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z) {
			return 'a' + (scancode - InputConstants.KEY_A);
		}
		if (scancode >= InputConstants.KEY_1 && scancode <= InputConstants.KEY_9) {
			return '1' + (scancode - InputConstants.KEY_1);
		}
		if (scancode == InputConstants.KEY_0) {
			return '0';
		}
		if (scancode == InputConstants.KEY_RETURN) {
			return '\r';
		}
		if (scancode == InputConstants.KEY_ESCAPE) {
			return 27;
		}
		if (scancode == InputConstants.KEY_BACKSPACE) {
			return '\b';
		}
		if (scancode == InputConstants.KEY_TAB) {
			return '\t';
		}
		if (scancode == InputConstants.KEY_SPACE) {
			return ' ';
		}
		return scancode | 1 << 30;
	}
}
