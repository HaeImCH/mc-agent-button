package com.example.agentbutton.action;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads and clicks the clickable widgets on the currently-open {@link Screen}.
 *
 * <p>All methods here assume they are called on the Minecraft client thread
 * (see {@code ClientThread}).
 */
public final class ScreenTools {
	private ScreenTools() {}

	/** A flat description of one on-screen clickable widget. */
	public record Widget(int index, String type, String label,
	                     int x, int y, int width, int height,
	                     boolean active, boolean visible, boolean hovered) {}

	/**
	 * Collect the clickable widgets of a screen, in render order, descending into list
	 * and container widgets ({@link ParentElement}) so nested controls — e.g. the rows of
	 * the Controls / Key Binds list, or entries of the server list — are reachable too.
	 */
	public static List<ClickableWidget> collect(Screen screen) {
		List<ClickableWidget> out = new ArrayList<>();
		if (screen == null) {
			return out;
		}
		Set<Element> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		collectInto(screen.children(), out, seen);
		return out;
	}

	private static void collectInto(List<? extends Element> elements, List<ClickableWidget> out, Set<Element> seen) {
		for (Element e : elements) {
			if (e == null || !seen.add(e)) {
				continue;
			}
			if (e instanceof ClickableWidget w) {
				out.add(w);
			}
			if (e instanceof ParentElement parent) {
				collectInto(parent.children(), out, seen);
			}
		}
	}

	/** Describe each clickable widget (index, label, geometry, state). */
	public static List<Widget> describe(Screen screen) {
		List<ClickableWidget> widgets = collect(screen);
		List<Widget> out = new ArrayList<>(widgets.size());
		for (int i = 0; i < widgets.size(); i++) {
			ClickableWidget w = widgets.get(i);
			out.add(new Widget(
					i,
					w.getClass().getSimpleName(),
					labelOf(w),
					w.getX(), w.getY(), w.getWidth(), w.getHeight(),
					w.active, w.visible, w.isHovered()
			));
		}
		return out;
	}

	/** The visible text of a widget, or "" if none. */
	public static String labelOf(ClickableWidget w) {
		return w.getMessage() == null ? "" : w.getMessage().getString();
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
