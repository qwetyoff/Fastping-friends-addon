package com.mcdanix.fastping.friends;

import com.mcdanix.fastping.api.PingManager;
import com.mcdanix.fastping.api.PingMarker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PingNetworkProtocol {
    public static final String TAG = "~";
    private static final Pattern PACKET_PATTERN = Pattern.compile("~([A-Za-z0-9_-]{10,28})(?![A-Za-z0-9_-])");

    private static final int DEFAULT_FRIEND_PING_COLOR = 0xFF55FF55;
    private static final int TICK_DELAY_BETWEEN_MSGS = 22;
    private static final long PING_MEMORY_TTL_MS = 12_000L;

    private static final Queue<String> commandQueue = new ArrayDeque<>();
    private static int tickCooldown = 0;
    private static final Map<String, Long> pingMemory = new ConcurrentHashMap<>();

    public static void queueCommand(String targetNick, String typeAndArgs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String selfNick = client.player.getGameProfile().name();
        if (targetNick.equalsIgnoreCase(selfNick)) {
            return;
        }

        List<String> parts = Arrays.asList(typeAndArgs.split("\\s+"));
        String type = parts.get(0);
        byte[] rawPayload = null;
        if ("REQ".equals(type)) {
            rawPayload = PingCrypto.packSimple(1, selfNick);
        } else if ("ACCEPT".equals(type)) {
            rawPayload = PingCrypto.packSimple(2, selfNick);
        } else if ("DENY".equals(type)) {
            rawPayload = PingCrypto.packSimple(3, selfNick);
        } else if ("PING".equals(type) && parts.size() >= 5) {
            double x = Double.parseDouble(parts.get(1));
            double y = Double.parseDouble(parts.get(2));
            double z = Double.parseDouble(parts.get(3));
            String dimId = parts.get(4);
            rawPayload = PingCrypto.packPing(selfNick, x, y, z, dimId);
        }

        if (rawPayload == null) return;

        String encrypted = PingCrypto.encryptBytes(rawPayload, FriendStorage.getSecretKey());
        if (encrypted == null) return;

        if ("PING".equals(type)) {
            String prefixToReplace = "msg " + targetNick + " ~";
            commandQueue.removeIf(cmd -> cmd.toLowerCase(Locale.ROOT)
                    .startsWith(prefixToReplace.toLowerCase(Locale.ROOT)) && cmd.length() > prefixToReplace.length() + 15);
        }

        commandQueue.offer("msg " + targetNick + " " + TAG + encrypted);
    }

    public static void onClientTick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            commandQueue.clear();
            tickCooldown = 0;
            return;
        }
        if (tickCooldown > 0) {
            tickCooldown--;
            return;
        }
        String nextCmd = commandQueue.poll();
        if (nextCmd != null) {
            List<String> cmdParts = Arrays.asList(nextCmd.split("\\s+", 3));
            if (cmdParts.size() >= 2) {
                String target = cmdParts.get(1);
                boolean isOnline = client.getNetworkHandler().getPlayerList().stream()
                        .anyMatch(e -> e.getProfile().name().equalsIgnoreCase(target));
                if (!isOnline) {
                    return;
                }
            }
            client.getNetworkHandler().sendChatCommand(nextCmd);
            tickCooldown = TICK_DELAY_BETWEEN_MSGS;
        }
    }

    public static void broadcastPing(MinecraftClient client, Vec3d pos) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            return;
        }

        String selfName = client.player.getGameProfile().name();
        String dimId = client.world.getRegistryKey().getValue().toString();

        for (PingMarker marker : PingManager.getPings()) {
            if (marker.pos().squaredDistanceTo(pos) < 0.05
                    && !marker.authorName().equalsIgnoreCase(selfName)) {
                return;
            }
        }

        cleanupOldMemory();

        String coordsSuffix = formatCoordsSuffix(pos.x, pos.y, pos.z, dimId);
        for (String key : pingMemory.keySet()) {
            if (key.endsWith(coordsSuffix)) {
                return;
            }
        }

        pingMemory.put(selfName.toLowerCase(Locale.ROOT) + coordsSuffix, System.currentTimeMillis());

        String args = String.format(Locale.ROOT, "PING %.1f %.1f %.1f %s", pos.x, pos.y, pos.z, dimId);

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String onlineName = entry.getProfile().name();
            if (!onlineName.equalsIgnoreCase(selfName) && FriendStorage.isFriend(onlineName)) {
                queueCommand(onlineName, args);
            }
        }
    }
    public static void handleIncomingMessage(MinecraftClient client, String rawMessage) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        Matcher matcher = PACKET_PATTERN.matcher(rawMessage);
        if (!matcher.find()) {
            return;
        }

        String base64Payload = matcher.group(1);
        byte[] plain = PingCrypto.decryptBytes(base64Payload, FriendStorage.getSecretKey());
        if (plain == null || plain.length < 3) {
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(plain);
        int header = buf.get() & 0xFF;
        int type = (header >> 4) & 0x0F;
        int dimCode = header & 0x0F;
        short senderHash = buf.getShort();

        String selfName = client.player.getGameProfile().name();
        if (PingCrypto.crc16Nick(selfName) == senderHash) {
            return;
        }

        String sender = resolveNickByHash(client, senderHash);
        if (sender == null) {
            return;
        }

        client.execute(() -> {
            if (type == 1) {
                if (FriendStorage.isFriend(sender)) {
                    queueCommand(sender, "ACCEPT");
                    return;
                }
                FriendStorage.addIncomingRequest(sender);
                sendFriendRequestPrompt(client, sender);
            } else if (type == 2) {
                if (FriendStorage.hasOutgoingRequest(sender) || !FriendStorage.isFriend(sender)) {
                    FriendStorage.addFriend(sender);
                    sendLocalInfo(client, Text.translatable("message.fastping-friends.request_accepted_by",
                            Text.literal(sender).formatted(Formatting.AQUA)));
                }
            } else if (type == 3) {
                if (FriendStorage.hasOutgoingRequest(sender)) {
                    FriendStorage.removeOutgoingRequest(sender);
                    sendLocalInfo(client, Text.translatable("message.fastping-friends.request_denied_by",
                            Text.literal(sender).formatted(Formatting.RED)));
                }
            } else if (type == 4) {
                if (buf.remaining() < 8 || client.world == null || !FriendStorage.isFriend(sender)) {
                    return;
                }
                double x = PingCrypto.getInt24(buf) / 10.0;
                double y = buf.getShort() / 10.0;
                double z = PingCrypto.getInt24(buf) / 10.0;
                String dimId = PingCrypto.decodeDimension(dimCode);

                if (!client.world.getRegistryKey().getValue().toString().equals(dimId)) {
                    return;
                }

                cleanupOldMemory();
                String fullKey = sender.toLowerCase(Locale.ROOT) + formatCoordsSuffix(x, y, z, dimId);
                if (pingMemory.putIfAbsent(fullKey, System.currentTimeMillis()) != null) {
                    return;
                }

                PingManager.addPing(new Vec3d(x, y, z), sender, DEFAULT_FRIEND_PING_COLOR);
            }
        });
    }

    private static String resolveNickByHash(MinecraftClient client, short targetHash) {
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().name();
            if (PingCrypto.crc16Nick(name) == targetHash) {
                return name;
            }
        }
        return null;
    }

    private static String formatCoordsSuffix(double x, double y, double z, String dimId) {
        return String.format(Locale.ROOT, "|%.1f|%.1f|%.1f|%s", x, y, z, dimId);
    }

    private static void cleanupOldMemory() {
        long now = System.currentTimeMillis();
        pingMemory.entrySet().removeIf(entry -> (now - entry.getValue()) > PING_MEMORY_TTL_MS);
    }

    private static void sendFriendRequestPrompt(MinecraftClient client, String sender) {
        MutableText acceptBtn = Text.translatable("gui.fastping-friends.btn_accept")
                .formatted(Formatting.GREEN, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/fping friend accept " + sender))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.translatable("gui.fastping-friends.btn_accept_hover", sender))));

        MutableText denyBtn = Text.translatable("gui.fastping-friends.btn_deny")
                .formatted(Formatting.RED, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/fping friend deny " + sender))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.translatable("gui.fastping-friends.btn_deny_hover", sender))));

        MutableText msg = Text.literal("[FastPing] ").formatted(Formatting.GOLD)
                .append(Text.translatable("message.fastping-friends.incoming_request",
                        Text.literal(sender).formatted(Formatting.AQUA),
                        acceptBtn,
                        denyBtn).formatted(Formatting.GRAY));

        client.inGameHud.getChatHud().addMessage(msg);
    }

    public static void sendLocalInfo(MinecraftClient client, Text content) {
        if (client.inGameHud != null) {
            MutableText prefix = Text.literal("[FastPing] ").formatted(Formatting.GOLD);
            client.inGameHud.getChatHud().addMessage(prefix.append(content.copy().formatted(Formatting.GRAY)));
        }
    }
}
