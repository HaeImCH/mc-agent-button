package com.example.agentbutton.action;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.narration.NarrationTrigger;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

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
	                     boolean active, boolean visible, boolean hovered, boolean selected,
	                     boolean scrolledOut) {}

	/**
	 * A clickable thing on the screen: either a {@link AbstractWidget}, or a row of an
	 * {@link AbstractSelectionList} together with the list that owns it. Rows are typed as
	 * {@link GuiEventListener} so any list, whatever its entry type, is handled.
	 * A widget that lives inside a list row (e.g. a Key Binds button) has both
	 * {@code widget} and {@code list}/{@code entry} set.
	 * {@code parents} is the container chain from the screen down to the target's direct
	 * parent, so a click can update focus the way a real click does.
	 */
	public record Target(AbstractWidget widget, AbstractSelectionList<?> list, GuiEventListener entry,
	                     List<ContainerEventHandler> parents) {
		static Target of(AbstractWidget w, AbstractSelectionList<?> rowList, GuiEventListener row,
		                 List<ContainerEventHandler> parents) {
			return new Target(w, rowList, row, parents);
		}

		static Target row(AbstractSelectionList<?> list, GuiEventListener entry, List<ContainerEventHandler> parents) {
			return new Target(null, list, entry, parents);
		}

		/** A list row itself (not a widget inside one). */
		public boolean isRow() {
			return widget == null;
		}

		/** A widget that sits inside a list row. */
		public boolean isInRow() {
			return widget != null && entry != null;
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
		collectInto(screen.children(), null, null, null, List.of(screen), out, seen);
		return out;
	}

	/**
	 * @param owner   the list whose direct children {@code elements} are, or null
	 * @param rowList the list owning the row we are inside of (at any depth), or null
	 * @param row     that row, or null
	 */
	private static void collectInto(List<? extends GuiEventListener> elements, AbstractSelectionList<?> owner,
	                                AbstractSelectionList<?> rowList, GuiEventListener row,
	                                List<ContainerEventHandler> parents, List<Target> out, Set<GuiEventListener> seen) {
		for (GuiEventListener e : elements) {
			if (e == null || !seen.add(e)) {
				continue;
			}
			if (e instanceof AbstractWidget w && !(e instanceof AbstractSelectionList<?>)) {
				out.add(Target.of(w, rowList, row, parents));
			}
			// Every direct child of an AbstractSelectionList is one of its rows.
			if (owner != null && !(e instanceof ContainerEventHandler)) {
				out.add(Target.row(owner, e, parents));
			}
			if (e instanceof ContainerEventHandler parent) {
				List<ContainerEventHandler> chain = new ArrayList<>(parents);
				chain.add(parent);
				// A container that is a direct child of a list is a row holding its own widgets.
				boolean isRowContainer = owner != null;
				collectInto(parent.children(), e instanceof AbstractSelectionList<?> list ? list : null,
						isRowContainer ? owner : rowList, isRowContainer ? e : row,
						List.copyOf(chain), out, seen);
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
				out.add(new Widget(
						i,
						"row of " + list.getClass().getSimpleName(),
						labelOf(t),
						list.getRowLeft(), top, list.getRowWidth(), bottom - top,
						list.active, list.visible, false,
						list.getSelected() == t.entry(),
						!isRowInView(list, t.entry())
				));
			} else if (t.isInRow() && !isRowInView(t.list(), t.entry())) {
				// Rows only position their widgets when drawn, so an off-screen row's widgets
				// still hold stale coordinates (often 0,0). Report the row's position instead.
				AbstractWidget w = t.widget();
				AbstractSelectionList<?> list = t.list();
				int row = list.children().indexOf(t.entry());
				int top = list.getRowTop(row);
				out.add(new Widget(
						i,
						w.getClass().getSimpleName(),
						labelOf(t),
						list.getRowLeft(), top, w.getWidth(), w.getHeight(),
						w.active, w.visible, false, false,
						true
				));
			} else {
				AbstractWidget w = t.widget();
				out.add(new Widget(
						i,
						w.getClass().getSimpleName(),
						labelOf(t),
						w.getX(), w.getY(), w.getWidth(), w.getHeight(),
						w.active, w.visible, w.isHovered(), false,
						false
				));
			}
		}
		return out;
	}

	/**
	 * The visible text of a target, or "" if none. List rows use their narration text.
	 * Widgets inside a row also use their narration title when they have one, because their
	 * own text often lacks the row's context: a Key Binds button just says "Space", while its
	 * narration says which action it is bound to.
	 */
	public static String labelOf(Target t) {
		if (t.isRow()) {
			if (t.entry() instanceof ObjectSelectionList.Entry<?> e && e.getNarration() != null) {
				return e.getNarration().getString();
			}
			return "";
		}
		AbstractWidget w = t.widget();
		String message = w.getMessage() == null ? "" : w.getMessage().getString();
		if (t.isInRow()) {
			String title = narrationTitleOf(w);
			if (!title.isBlank()) {
				return title;
			}
		}
		return message;
	}

	/** The TITLE part of a widget's screen-reader narration, or "" if none. */
	private static String narrationTitleOf(AbstractWidget w) {
		StringBuilder title = new StringBuilder();
		w.updateNarration(new NarrationElementOutput() {
			@Override
			public void add(NarratedElementType type, NarrationThunk<?> contents) {
				if (type == NarratedElementType.TITLE) {
					contents.getText(text -> {
						if (!title.isEmpty()) {
							title.append(' ');
						}
						title.append(text);
					});
				}
			}

			@Override
			public NarrationElementOutput nest() {
				return this;
			}

			@Override
			public NarrationTrigger narrationTrigger() {
				return NarrationTrigger.SYSTEM;
			}
		});
		return stripButtonWrapper(title.toString());
	}

	/**
	 * Remove the "%s button" wrapper ({@link AbstractWidget#wrapDefaultNarrationMessage})
	 * that button narration adds, in whatever language the game uses.
	 */
	private static String stripButtonWrapper(String text) {
		String marker = "\u0000";
		String wrapped = AbstractWidget.wrapDefaultNarrationMessage(Component.literal(marker)).getString();
		int at = wrapped.indexOf(marker);
		if (at < 0) {
			return text;
		}
		String prefix = wrapped.substring(0, at);
		String suffix = wrapped.substring(at + marker.length());
		if (text.length() >= prefix.length() + suffix.length() && text.startsWith(prefix) && text.endsWith(suffix)) {
			return text.substring(prefix.length(), text.length() - suffix.length());
		}
		return text;
	}

	/** Whether a list row is fully inside the list's visible area. */
	public static boolean isRowInView(AbstractSelectionList<?> list, GuiEventListener entry) {
		int row = list.children().indexOf(entry);
		if (row < 0) {
			return false;
		}
		return list.getRowTop(row) >= list.getY() && list.getRowBottom(row) <= list.getBottom();
	}

	/**
	 * Scroll a list so the given row sits in the middle of its visible area.
	 *
	 * @return false if the row is not in the list
	 */
	public static boolean scrollRowIntoView(AbstractSelectionList<?> list, GuiEventListener entry) {
		int row = list.children().indexOf(entry);
		if (row < 0) {
			return false;
		}
		int top = list.getRowTop(row);
		int height = list.getRowBottom(row) - top;
		list.setScrollAmount(list.scrollAmount() + (top - list.getY()) - (list.getHeight() - height) / 2.0);
		return true;
	}

	/** Whether the target can be clicked right now (rows scrolled out of view still can). */
	public static boolean isClickable(Target t) {
		AbstractWidget w = t.isRow() ? t.list() : t.widget();
		return w.active && w.visible;
	}

	/** Click a target; see {@link #click(AbstractWidget)} and {@link #clickRow}. */
	public static boolean click(Screen screen, Target t) {
		if (t.isRow()) {
			return clickRow(screen, t.list(), t.entry());
		}
		AbstractWidget w = t.widget();
		boolean handled = click(w);
		if (handled && w.shouldTakeFocusAfterInteraction()) {
			focusPath(t.parents(), w);
		}
		return handled;
	}

	/**
	 * Focus {@code child} in each container from its direct parent up to the screen, as
	 * {@code ContainerEventHandler.mouseClicked} does on a real click. Some containers act
	 * on this: e.g. {@code TabNavigationBar.setFocused} is what switches tabs, since a
	 * {@code TabButton} has no click action of its own.
	 */
	private static void focusPath(List<ContainerEventHandler> parents, GuiEventListener child) {
		for (int i = parents.size() - 1; i >= 0; i--) {
			ContainerEventHandler parent = parents.get(i);
			parent.setFocused(child);
			child = parent;
		}
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
		if (!scrollRowIntoView(list, entry)) {
			return false;
		}
		int row = list.children().indexOf(entry);
		int top = list.getRowTop(row);
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
