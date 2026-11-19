package com.mcdanix.fastping.friends;

import com.mcdanix.fastping.api.PingManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FastPingFriendsClient implements ClientModInitializer {
    public static final String MOD_ID = "fastping-friends";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String PACKET_REGEX = ".*~[A-Za-z0-9_-]{10,28}(?![A-Za-z0-9_-]).*";

    @Override
    public void onInitializeClient() {
        FriendStorage.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                FriendCommands.register(dispatcher)
        );

        ClientTickEvents.END_CLIENT_TICK.register(PingNetworkProtocol::onClientTick);

        PingManager.addListener(pos ->
                PingNetworkProtocol.broadcastPing(MinecraftClient.getInstance(), pos)
        );

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            String raw = message.getString();
            if (raw.matches(PACKET_REGEX)) {
                PingNetworkProtocol.handleIncomingMessage(MinecraftClient.getInstance(), raw);
                return false;
            }
            return true;
        });

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String raw = message.getString();
            if (raw.matches(PACKET_REGEX)) {
                PingNetworkProtocol.handleIncomingMessage(MinecraftClient.getInstance(), raw);
                return false;
            }
            return true;
        });

        LOGGER.info("FastPing: Friends Addon initialized.");
    }
}
