package com.example.agentbutton.command;

import com.example.agentbutton.action.ServerActions;
import com.example.agentbutton.ui.AgentButtonScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

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
			dispatcher.register(ClientCommandManager.literal("agentbutton")
					.executes(ctx -> {
						MinecraftClient client = MinecraftClient.getInstance();
						client.execute(() -> client.setScreen(new AgentButtonScreen(client.currentScreen)));
						return 1;
					}));

			dispatcher.register(ClientCommandManager.literal("abconnect")
					.then(ClientCommandManager.argument("address", StringArgumentType.greedyString())
							.executes(ctx -> {
								String address = StringArgumentType.getString(ctx, "address");
								ctx.getSource().sendFeedback(Text.literal("Connecting to " + address));
								ServerActions.connect(address);
								return 1;
							})));

			dispatcher.register(ClientCommandManager.literal("abdisconnect")
					.executes(ctx -> {
						ctx.getSource().sendFeedback(Text.literal("Disconnecting"));
						ServerActions.disconnect();
						return 1;
					}));

			dispatcher.register(ClientCommandManager.literal("abservers")
					.executes(ctx -> {
						List<ServerActions.SavedServer> servers = ServerActions.listSavedServers();
						ctx.getSource().sendFeedback(Text.literal("Saved servers (" + servers.size() + "):"));
						for (ServerActions.SavedServer s : servers) {
							ctx.getSource().sendFeedback(Text.literal(" - " + s.name() + " (" + s.address() + ")"));
						}
						return 1;
					}));
		});
	}
}
