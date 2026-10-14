package com.example.agentbutton.command;

import com.example.agentbutton.action.ServerActions;
import com.example.agentbutton.ui.AgentButtonScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Client-side commands mirroring the on-screen buttons, so an agent can drive the
 * client either by clicking or by typing a command:
 *   /agentbutton            open the menu
 *   /abconnect <host[:port]> connect to a server
 *   /abdisconnect           disconnect from the current server
 *   /abservers              list saved servers
 */
public final class AgentButtonCommands {
	private AgentButtonCommands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("agentbutton")
					.executes(ctx -> {
						Minecraft client = Minecraft.getInstance();
						client.execute(() -> client.gui.setScreen(new AgentButtonScreen(client.gui.screen())));
						return 1;
					}));

			dispatcher.register(ClientCommands.literal("abconnect")
					.then(ClientCommands.argument("address", StringArgumentType.greedyString())
							.executes(ctx -> {
								String address = StringArgumentType.getString(ctx, "address");
								ctx.getSource().sendFeedback(Component.literal("Connecting to " + address));
								ServerActions.connect(address);
								return 1;
							})));

			dispatcher.register(ClientCommands.literal("abdisconnect")
					.executes(ctx -> {
						ctx.getSource().sendFeedback(Component.literal("Disconnecting"));
						ServerActions.disconnect();
						return 1;
					}));

			dispatcher.register(ClientCommands.literal("abservers")
					.executes(ctx -> {
						List<ServerActions.SavedServer> servers = ServerActions.listSavedServers();
						ctx.getSource().sendFeedback(Component.literal("Saved servers (" + servers.size() + "):"));
						for (ServerActions.SavedServer s : servers) {
							ctx.getSource().sendFeedback(Component.literal(" - " + s.name() + " (" + s.address() + ")"));
						}
						return 1;
					}));
		});
	}
}
