package com.example.agentbutton.mcp;

import com.example.agentbutton.action.ScreenTools;
import com.example.agentbutton.action.ServerActions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;
import java.util.function.Consumer;

/**
 * The MCP tool catalogue and dispatcher. Tools that read client state hop onto the
 * client thread via {@link ClientThread}; tools that only trigger an action delegate to
 * {@link ServerActions}, which already schedules onto the client thread internally.
 */
public final class McpTools {
	private static final long TIMEOUT_SECONDS = 10;

	// ---- tools/list ----

	public JsonObject list() {
		JsonArray tools = new JsonArray();

		tools.add(tool("list_servers",
				"List the saved multiplayer servers (the entries on the Multiplayer screen): name, address and index.",
				schema(p -> {})));

		tools.add(tool("join_server",
				"Connect to a multiplayer server, either by 'address' (host or host:port) or by 'index' from list_servers.",
				schema(p -> {
					p.add("address", stringProp("Server address, e.g. \"play.example.com\" or \"localhost:25565\""));
					p.add("index", integerProp("Index of a saved server from list_servers"));
				})));

		tools.add(tool("disconnect",
				"Disconnect from the current server/world and return to the multiplayer menu.",
				schema(p -> {})));

		tools.add(tool("get_screen",
				"Read the current on-screen GUI: the open screen and every clickable button/widget — including rows of scrolling lists (world list, version pickers, ...) — with its index, label, state and position. Returns nothing to click when the player is in-game with no menu open.",
				schema(p -> {})));

		tools.add(tool("click_button",
				"MouseButtonEvent a button or list row on the current screen (rows are scrolled into view and selected), identified by 'index' (from get_screen) or by 'label' (visible text, case-insensitive, substring allowed).",
				schema(p -> {
					p.add("index", integerProp("Widget index from get_screen"));
					p.add("label", stringProp("Visible button text to match"));
				})));

		tools.add(tool("press_key",
				"Send a key press to the current screen — e.g. to set a key while a Key Binds row is armed, or to press Escape/Enter. Provide 'key' (a single letter/digit or a name like escape, enter, space, tab, up, down, left, right) or 'code' (a raw GLFW key code).",
				schema(p -> {
					p.add("key", stringProp("Single letter/digit (e.g. \"Q\") or key name (escape, enter, space, tab, up, down, left, right)"));
					p.add("code", integerProp("Raw GLFW key code (alternative to 'key')"));
				})));

		tools.add(tool("get_status",
				"Report client status: whether connected to a world/server, the current server address, the player name, and the open screen.",
				schema(p -> {})));

		JsonObject r = new JsonObject();
		r.add("tools", tools);
		return r;
	}

	// ---- tools/call ----

	public JsonObject call(JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();

		try {
			return switch (name) {
				case "list_servers" -> text(listServers());
				case "join_server" -> text(joinServer(args));
				case "disconnect" -> {
					ServerActions.disconnect();
					yield text("Disconnecting from the current server.");
				}
				case "get_screen" -> text(getScreen());
				case "click_button" -> text(clickButton(args));
				case "press_key" -> text(pressKey(args));
				case "get_status" -> text(getStatus());
				default -> errorText("Unknown tool: " + name);
			};
		} catch (Exception e) {
			return errorText("Tool '" + name + "' failed: " + e.getMessage());
		}
	}

	// ---- tool implementations ----

	private String listServers() {
		List<ServerActions.SavedServer> servers =
				ClientThread.get(ServerActions::listSavedServers, TIMEOUT_SECONDS);
		if (servers.isEmpty()) {
			return "No saved servers found.";
		}
		StringBuilder sb = new StringBuilder("Saved servers (" + servers.size() + "):\n");
		for (int i = 0; i < servers.size(); i++) {
			ServerActions.SavedServer s = servers.get(i);
			sb.append("  [").append(i).append("] ").append(s.name())
					.append(" -> ").append(s.address()).append('\n');
		}
		return sb.toString();
	}

	private String joinServer(JsonObject args) {
		if (args.has("index") && !args.get("index").isJsonNull()) {
			int idx = args.get("index").getAsInt();
			List<ServerActions.SavedServer> servers =
					ClientThread.get(ServerActions::listSavedServers, TIMEOUT_SECONDS);
			if (idx < 0 || idx >= servers.size()) {
				return "No saved server at index " + idx + " (there are " + servers.size() + ").";
			}
			ServerActions.SavedServer s = servers.get(idx);
			ServerActions.connect(s.address());
			return "Connecting to " + s.name() + " (" + s.address() + ").";
		}
		if (args.has("address") && !args.get("address").isJsonNull()) {
			String address = args.get("address").getAsString().trim();
			if (address.isEmpty()) {
				return "Empty address.";
			}
			ServerActions.connect(address);
			return "Connecting to " + address + ".";
		}
		return "Provide either 'address' or 'index'.";
	}

	private String getScreen() {
		return ClientThread.get(() -> {
			Screen screen = Minecraft.getInstance().gui.screen();
			if (screen == null) {
				return "No screen is open (the player is in-game). There are no GUI buttons to click right now; use get_status.";
			}
			List<ScreenTools.Widget> widgets = ScreenTools.describe(screen);
			StringBuilder sb = new StringBuilder();
			sb.append("Screen: ").append(screen.getClass().getSimpleName());
			if (screen.getTitle() != null) {
				sb.append("  |  Title: ").append(screen.getTitle().getString());
			}
			sb.append('\n').append("Clickable widgets (").append(widgets.size()).append("):\n");
			for (ScreenTools.Widget w : widgets) {
				sb.append("  [").append(w.index()).append("] \"").append(w.label()).append("\" ")
						.append('(').append(w.type()).append(')')
						.append(w.selected() ? " [selected]" : "")
						.append(w.active() ? "" : " [disabled]")
						.append(w.visible() ? "" : (w.type().startsWith("row of ") ? " [scrolled out of view]" : " [hidden]"))
						.append(" @ ").append(w.x()).append(',').append(w.y())
						.append(' ').append(w.width()).append('x').append(w.height())
						.append('\n');
			}
			if (widgets.isEmpty()) {
				sb.append("  (no clickable widgets)\n");
			}
			return sb.toString();
		}, TIMEOUT_SECONDS);
	}

	private String clickButton(JsonObject args) {
		return ClientThread.get(() -> {
			Screen screen = Minecraft.getInstance().gui.screen();
			if (screen == null) {
				return "Cannot click: no screen is open.";
			}
			List<ScreenTools.Target> widgets = ScreenTools.collect(screen);

			ScreenTools.Target target = null;
			String matchedBy;
			if (args.has("index") && !args.get("index").isJsonNull()) {
				int idx = args.get("index").getAsInt();
				if (idx < 0 || idx >= widgets.size()) {
					return "No widget at index " + idx + " (screen has " + widgets.size() + " clickable widgets).";
				}
				target = widgets.get(idx);
				matchedBy = "index " + idx;
			} else if (args.has("label") && !args.get("label").isJsonNull()) {
				String label = args.get("label").getAsString();
				for (ScreenTools.Target w : widgets) {
					if (ScreenTools.labelOf(w).equalsIgnoreCase(label)) {
						target = w;
						break;
					}
				}
				if (target == null) {
					String lower = label.toLowerCase();
					for (ScreenTools.Target w : widgets) {
						if (ScreenTools.labelOf(w).toLowerCase().contains(lower)) {
							target = w;
							break;
						}
					}
				}
				if (target == null) {
					return "No button matching label \"" + label + "\". Use get_screen to see available buttons.";
				}
				matchedBy = "label \"" + label + "\"";
			} else {
				return "Provide either 'index' or 'label'.";
			}

			String label = ScreenTools.labelOf(target);
			if (!ScreenTools.isClickable(target)) {
				return "Button \"" + label + "\" is currently disabled or hidden; not clicking.";
			}
			ScreenTools.click(screen, target);
			return "Clicked " + (target.isRow() ? "list row " : "") + "\"" + label + "\" (matched by " + matchedBy + ").";
		}, TIMEOUT_SECONDS);
	}

	private String pressKey(JsonObject args) {
		int code;
		String shown;
		if (args.has("code") && !args.get("code").isJsonNull()) {
			code = args.get("code").getAsInt();
			shown = "code " + code;
		} else if (args.has("key") && !args.get("key").isJsonNull()) {
			String key = args.get("key").getAsString().trim();
			code = resolveKey(key);
			if (code < 0) {
				return "Unrecognised key \"" + key + "\". Use a single letter/digit or: escape, enter, space, tab, backspace, up, down, left, right.";
			}
			shown = "\"" + key + "\"";
		} else {
			return "Provide either 'key' or 'code'.";
		}

		final int glfwCode = code;
		return ClientThread.get(() -> {
			Screen screen = Minecraft.getInstance().gui.screen();
			if (screen == null) {
				return "Cannot press a key: no screen is open.";
			}
			boolean handled = ScreenTools.pressKey(screen, glfwCode, 0);
			return "Pressed " + shownLabel(glfwCode) + " on " + screen.getClass().getSimpleName()
					+ (handled ? "." : " (screen did not consume it).");
		}, TIMEOUT_SECONDS);
	}

	private static String shownLabel(int code) {
		if (code >= 32 && code <= 126) {
			return "'" + (char) code + "' (code " + code + ")";
		}
		return "code " + code;
	}

	/** Map a single character or key name to a GLFW key code, or -1 if unknown. */
	private static int resolveKey(String key) {
		if (key.length() == 1) {
			char c = Character.toUpperCase(key.charAt(0));
			// GLFW letter/digit codes equal their ASCII uppercase values.
			if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ') {
				return c;
			}
		}
		return switch (key.toLowerCase()) {
			case "escape", "esc" -> 256;
			case "enter", "return" -> 257;
			case "tab" -> 258;
			case "backspace" -> 259;
			case "space", "spacebar" -> 32;
			case "right" -> 262;
			case "left" -> 263;
			case "down" -> 264;
			case "up" -> 265;
			default -> -1;
		};
	}

	private String getStatus() {
		return ClientThread.get(() -> {
			Minecraft mc = Minecraft.getInstance();
			boolean connected = mc.level != null;
			ServerData entry = mc.getCurrentServer();
			String server = entry != null ? entry.ip : (connected ? "(integrated/singleplayer)" : "none");
			String player = mc.getUser() != null ? mc.getUser().getName() : "?";
			String screen = mc.gui.screen() != null ? mc.gui.screen().getClass().getSimpleName() : "none (in-game)";
			return "connected=" + connected
					+ "\nserver=" + server
					+ "\nplayer=" + player
					+ "\ncurrentScreen=" + screen;
		}, TIMEOUT_SECONDS);
	}

	// ---- result + schema helpers ----

	private static JsonObject text(String message) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", message);
		JsonArray arr = new JsonArray();
		arr.add(content);
		JsonObject r = new JsonObject();
		r.add("content", arr);
		r.addProperty("isError", false);
		return r;
	}

	private static JsonObject errorText(String message) {
		JsonObject r = text(message);
		r.addProperty("isError", true);
		return r;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema) {
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("description", description);
		t.add("inputSchema", inputSchema);
		return t;
	}

	private static JsonObject schema(Consumer<JsonObject> properties) {
		JsonObject props = new JsonObject();
		properties.accept(props);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		return schema;
	}

	private static JsonObject stringProp(String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "string");
		o.addProperty("description", description);
		return o;
	}

	private static JsonObject integerProp(String description) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "integer");
		o.addProperty("description", description);
		return o;
	}
}
