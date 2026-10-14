package com.example.agentbutton.action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The three core actions, shared by the on-screen buttons, the client commands,
 * and any external agent driving the client. Every method that mutates client
 * state is scheduled onto the render thread via {@link Minecraft#execute}.
 */
public final class ServerActions {
	private ServerActions() {}

	/** A saved multiplayer server entry (the rows shown on the Multiplayer screen). */
	public record SavedServer(String name, String address) {}

	/**
	 * Connect to a server given "host" or "host:port".
	 * {@link ConnectScreen#startConnecting} spins up the network thread, swaps in a
	 * ConnectScreen, and drives the full handshake, so we do not call setScreen after it.
	 */
	public static void connect(String hostPort) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			Screen parent = client.gui.screen() != null ? client.gui.screen() : new TitleScreen();

			ServerAddress address = ServerAddress.parseString(hostPort); // bare host + default port + SRV
			ServerData info = new ServerData(hostPort, hostPort, ServerData.Type.OTHER);

			// 26.3 signature: (Screen, Minecraft, ServerAddress, ServerData,
			//                 boolean quickPlay, @Nullable TransferState)
			ConnectScreen.startConnecting(parent, client, address, info, false, null);
		});
	}

	/** Disconnect from the current world/server and return to the multiplayer list. */
	public static void disconnect() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			ClientLevel world = client.level;
			if (world != null) {
				// disconnect() requires a reason Component (shown if it surfaces a screen).
				world.disconnect(Component.translatable("disconnect.agentbutton"));
			}
			// After a disconnect the client has no world and no screen — give it one.
			client.gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
		});
	}

	/** True when the client is currently connected to a world/server. */
	public static boolean isConnected() {
		return Minecraft.getInstance().level != null;
	}

	/**
	 * Read the saved multiplayer server list (servers.dat) — the same data the
	 * Multiplayer screen shows. Client-side only; does file I/O, so don't call per frame.
	 */
	public static List<SavedServer> listSavedServers() {
		Minecraft client = Minecraft.getInstance();
		ServerList serverList = new ServerList(client);
		serverList.load(); // reads <gameDir>/servers.dat into memory

		List<SavedServer> result = new ArrayList<>();
		for (int i = 0; i < serverList.size(); i++) { // no getServers() — use size()/get(int)
			ServerData info = serverList.get(i);
			result.add(new SavedServer(info.name, info.ip)); // name & ip are public fields
		}
		return result;
	}
}
