package com.example.agentbutton;

import com.example.agentbutton.command.AgentButtonCommands;
import com.example.agentbutton.mcp.McpServer;
import com.example.agentbutton.ui.AgentButtonScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentButtonClient implements ClientModInitializer {
	public static final String MOD_ID = "agentbutton";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Default MCP port; override with -Dagentbutton.mcp.port=NNN or AGENTBUTTON_MCP_PORT. */
	private static final int DEFAULT_MCP_PORT = 8642;

	private static KeyMapping openScreenKey;
	private McpServer mcpServer;

	@Override
	public void onInitializeClient() {
		// 1) Register a keybinding (default: G) to open the agent menu — a handy test
		//    surface for the get_screen / click_button MCP tools. Rebindable in
		//    Options > Controls. KeyMapping takes a Category object, not a String.
		openScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.agentbutton.open_screen",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_G,
				KeyMapping.Category.MULTIPLAYER
		));

		// 2) Poll the keybinding each client tick; drain with a while-loop.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openScreenKey.consumeClick()) {
				client.gui.setScreen(new AgentButtonScreen(client.gui.screen()));
			}
		});

		// 3) Register client-side commands (/agentbutton, /abconnect, /abdisconnect, /abservers).
		AgentButtonCommands.register();

		// 4) Start the embedded MCP server so an external agent can drive the client.
		startMcpServer();
	}

	private void startMcpServer() {
		int port = resolvePort();
		mcpServer = new McpServer(port);
		try {
			mcpServer.start();
			LOGGER.info("[agentbutton] MCP server ready at http://127.0.0.1:{}/mcp", port);
		} catch (Exception e) {
			LOGGER.error("[agentbutton] Failed to start MCP server on port {}", port, e);
		}

		// Stop cleanly when the game closes.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (mcpServer != null) {
				mcpServer.stop();
			}
		});
	}

	private static int resolvePort() {
		String raw = System.getProperty("agentbutton.mcp.port");
		if (raw == null || raw.isBlank()) {
			raw = System.getenv("AGENTBUTTON_MCP_PORT");
		}
		if (raw != null && !raw.isBlank()) {
			try {
				return Integer.parseInt(raw.trim());
			} catch (NumberFormatException ignored) {
				LOGGER.warn("[agentbutton] Invalid MCP port '{}', using default {}", raw, DEFAULT_MCP_PORT);
			}
		}
		return DEFAULT_MCP_PORT;
	}
}
