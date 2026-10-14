package com.example.agentbutton.ui;

import com.example.agentbutton.action.ServerActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * On-screen menu of clickable buttons for an agent to interact with.
 *
 * <p>Top row: the three core actions — Connect, Disconnect, Refresh server list.
 * Below that, one clickable button per saved server that connects to it directly.
 */
public class AgentButtonScreen extends Screen {
	private final Screen parent;

	// Target used by the generic "Connect to Server" button when no row is clicked.
	private static final String DEFAULT_TARGET = "localhost:25565";

	private List<ServerActions.SavedServer> servers = List.of();

	public AgentButtonScreen(Screen parent) {
		super(Component.translatable("screen.agentbutton.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Load the saved servers up front so we can render a button per entry.
		this.servers = ServerActions.listSavedServers();

		final int w = 220, h = 20, spacing = 24;
		final int x = this.width / 2 - w / 2;
		int y = 40;

		// --- Core actions ---
		this.addRenderableWidget(Button.builder(
				Component.translatable("button.agentbutton.connect"),
				b -> ServerActions.connect(DEFAULT_TARGET)
		).bounds(x, y, w, h).build());

		y += spacing;
		this.addRenderableWidget(Button.builder(
				Component.translatable("button.agentbutton.disconnect"),
				b -> ServerActions.disconnect()
		).bounds(x, y, w, h).build());

		y += spacing;
		this.addRenderableWidget(Button.builder(
				Component.translatable("button.agentbutton.list"),
				b -> this.rebuildWidgets() // re-run init() to refresh the server rows
		).bounds(x, y, w, h).build());

		// --- One clickable button per saved server (the "server list") ---
		y += spacing + 14; // gap below the "Saved Servers (n)" header drawn in render()
		for (ServerActions.SavedServer s : this.servers) {
			final String address = s.address();
			this.addRenderableWidget(Button.builder(
					Component.literal(s.name() + "  (" + address + ")"),
					b -> ServerActions.connect(address)
			).bounds(x, y, w, h).build());
			y += spacing;
			if (y > this.height - 48) break; // don't overflow off the bottom
		}

		// --- Close ---
		this.addRenderableWidget(Button.builder(
				Component.translatable("button.agentbutton.done"),
				b -> this.onClose()
		).bounds(x, this.height - 28, w, h).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta); // background + widgets

		context.centeredText(
				this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

		// Header above the server rows.
		Component header = this.servers.isEmpty()
				? Component.translatable("label.agentbutton.no_servers")
				: Component.translatable("label.agentbutton.saved_servers", this.servers.size());
		context.centeredText(
				this.font, header, this.width / 2, 40 + 3 * 24 + 2, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
