package com.example.agentbutton.mcp;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bridges the MCP HTTP worker threads onto Minecraft's render (client) thread.
 *
 * <p>Almost all client state (the open {@code Screen}, widgets, the world) may only be
 * touched from the client thread. MCP tool handlers run on HTTP worker threads, so they
 * schedule work via {@link Minecraft#execute(Runnable)} and block for the result.
 */
public final class ClientThread {
	private ClientThread() {}

	/** Run {@code supplier} on the client thread and return its result, blocking up to {@code timeoutSeconds}. */
	public static <T> T get(Supplier<T> supplier, long timeoutSeconds) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread()) {
			return supplier.get();
		}

		CompletableFuture<T> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(supplier.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});

		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for the Minecraft client thread", e);
		} catch (TimeoutException e) {
			throw new RuntimeException("Timed out waiting for the Minecraft client thread", e);
		}
	}

	/** Run {@code action} on the client thread, blocking up to {@code timeoutSeconds}. */
	public static void run(Runnable action, long timeoutSeconds) {
		get(() -> {
			action.run();
			return null;
		}, timeoutSeconds);
	}
}
