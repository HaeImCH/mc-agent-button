package com.example.agentbutton.action;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads and clicks the clickable targets on the currently-open {@link Screen}: ordinary
 * {@link ClickableWidget}s plus the rows of scrolling lists ({@link EntryListWidget}),
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
	 * A clickable thing on the screen: either a {@link ClickableWidget}, or a row of an
	 * {@link EntryListWidget} together with the list that owns it. Rows are typed as
	 * {@link Element} because {@code EntryListWidget.Entry} is protected.
	 */
	public record Target(ClickableWidget widget, EntryListWidget<?> list, Element entry) {
		static Target of(ClickableWidget w) {
			return new Target(w, null, null);
		}

		static Target row(EntryListWidget<?> list, Element entry) {
			return new Target(null, list, entry);
		}

		public boolean isRow() {
			return entry != null;
		}
	}

	/**
	 * Collect the clickable targets of a screen, in render order, descending into list
	 * and container widgets ({@link ParentElement}) so nested controls — e.g. the rows of
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
		Set<Element> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		collectInto(screen.children(), null, out, seen);
		return out;
	}

	private static void collectInto(List<? extends Element> elements, EntryListWidget<?> owner,
	                                List<Target> out, Set<Element> seen) {
		for (Element e : elements) {
			if (e == null || !seen.add(e)) {
				continue;
			}
			if (e instanceof ClickableWidget w && !(e instanceof EntryListWidget<?>)) {
				out.add(Target.of(w));
			}
			// Every direct child of an EntryListWidget is one of its rows.
			if (owner != null && !(e instanceof ParentElement)) {
				out.add(Target.row(owner, e));
			}
			if (e instanceof ParentElement parent) {
				collectInto(parent.children(), e instanceof EntryListWidget<?> list ? list : null, out, seen);
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
				EntryListWidget<?> list = t.list();
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
						list.getSelectedOrNull() == t.entry()
				));
			} else {
				ClickableWidget w = t.widget();
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
			if (t.entry() instanceof AlwaysSelectedEntryListWidget.Entry<?> e && e.getNarration() != null) {
				return e.getNarration().getString();
			}
			return "";
		}
		ClickableWidget w = t.widget();
		return w.getMessage() == null ? "" : w.getMessage().getString();
	}

	/** Whether the target can be clicked right now (rows scrolled out of view still can). */
	public static boolean isClickable(Target t) {
		ClickableWidget w = t.isRow() ? t.list() : t.widget();
		return w.active && w.visible;
	}

	/** Click a target; see {@link #click(ClickableWidget)} and {@link #clickRow}. */
	public static boolean click(Screen screen, Target t) {
		return t.isRow() ? clickRow(screen, t.list(), t.entry()) : click(t.widget());
	}

	/**
	 * Synthesize a left-click at the widget's centre. 1.21.11 routes mouse input through
	 * {@link Click} / {@link MouseInput} records; calling the widget's own
	 * {@code mouseClicked}/{@code mouseReleased} reproduces a real user click (hit-tests
	 * the coordinates and fires the widget's press action).
	 *
	 * @return true if the widget handled the click
	 */
	public static boolean click(ClickableWidget w) {
		double cx = w.getX() + w.getWidth() / 2.0;
		double cy = w.getY() + w.getHeight() / 2.0;
		Click click = new Click(cx, cy, new MouseInput(0, 0)); // button 0 = left, no modifiers
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
	public static boolean clickRow(Screen screen, EntryListWidget<?> list, Element entry) {
		int row = list.children().indexOf(entry);
		if (row < 0) {
			return false;
		}
		int top = list.getRowTop(row);
		int height = list.getRowBottom(row) - top;
		list.setScrollY(list.getScrollY() + (top - list.getY()) - (list.getHeight() - height) / 2.0);

		top = list.getRowTop(row);
		double cx = list.getRowLeft() + list.getRowWidth() / 2.0;
		double cy = top + (list.getRowBottom(row) - top) / 2.0;
		Click click = new Click(cx, cy, new MouseInput(0, 0));
		boolean handled = screen.mouseClicked(click, false);
		screen.mouseReleased(click);
		return handled;
	}

	/**
	 * Send a key press + release to the screen. 1.21.11 delivers keyboard input as
	 * {@link KeyInput} records; routing them through {@code Screen.keyPressed} reproduces
	 * a real keystroke (used e.g. to set a key while a Key Binds row is armed).
	 *
	 * @param glfwCode a {@code GLFW_KEY_*} code
	 * @return true if the screen handled the press
	 */
	public static boolean pressKey(Screen screen, int glfwCode, int modifiers) {
		KeyInput input = new KeyInput(glfwCode, 0, modifiers);
		boolean handled = screen.keyPressed(input);
		screen.keyReleased(input);
		return handled;
	}
}
