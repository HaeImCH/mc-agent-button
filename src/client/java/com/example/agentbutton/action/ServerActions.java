package com.example.agentbutton.action;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The three core actions, shared by the on-screen buttons, the client commands,
 * and any external agent driving the client. Every method that mutates client
 * state is scheduled onto the render thread via {@link MinecraftClient#execute}.
 */
public final class ServerActions {
	private ServerActions() {}

	/** A saved multiplayer server entry (the rows shown on the Multiplayer screen). */
	public record SavedServer(String name, String address) {}

	/**
	 * Connect to a server given "host" or "host:port".
	 * {@link ConnectScreen#connect} spins up the network thread, swaps in a
	 * ConnectScreen, and drives the full handshake, so we do not call setScreen after it.
	 */
	public static void connect(String hostPort) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			Screen parent = client.currentScreen != null ? client.currentScreen : new TitleScreen();

			ServerAddress address = ServerAddress.parse(hostPort); // bare host + default port + SRV
			ServerInfo info = new ServerInfo(hostPort, hostPort, ServerInfo.ServerType.OTHER);

			// 1.21.x signature: (Screen, MinecraftClient, ServerAddress, ServerInfo,
			//                    boolean quickPlay, @Nullable CookieStorage)
			ConnectScreen.connect(parent, client, address, info, false, null);
		});
	}

	/** Disconnect from the current world/server and return to the multiplayer list. */
	public static void disconnect() {
		MinecraftClient client = MinecraftClient.getInstance();
		client.execute(() -> {
			ClientWorld world = client.world;
			if (world != null) {
				// 1.21.11: disconnect() requires a reason Text (shown if it surfaces a screen).
				world.disconnect(Text.translatable("disconnect.agentbutton"));
			}
			// After a disconnect the client has no world and no screen — give it one.
			client.setScreen(new MultiplayerScreen(new TitleScreen()));
		});
	}

	/** True when the client is currently connected to a world/server. */
	public static boolean isConnected() {
		return MinecraftClient.getInstance().world != null;
	}

	/**
	 * Read the saved multiplayer server list (servers.dat) — the same data the
	 * Multiplayer screen shows. Client-side only; does file I/O, so don't call per frame.
	 */
	public static List<SavedServer> listSavedServers() {
		MinecraftClient client = MinecraftClient.getInstance();
		ServerList serverList = new ServerList(client);
		serverList.loadFile(); // reads <gameDir>/servers.dat into memory

		List<SavedServer> result = new ArrayList<>();
		for (int i = 0; i < serverList.size(); i++) { // no getServers() in Yarn — use size()/get(int)
			ServerInfo info = serverList.get(i);
			result.add(new SavedServer(info.name, info.address)); // name & address are public fields
		}
		return result;
	}
}
