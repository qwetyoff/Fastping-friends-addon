package com.mcdanix.fastping.friends;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class FriendCommands {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("fping")
                .then(literal("friend")
                        .then(literal("add")
                                .then(argument("nick", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestOnlinePlayers(builder))
                                        .executes(ctx -> executeAdd(StringArgumentType.getString(ctx, "nick")))))
                        .then(literal("accept")
                                .then(argument("nick", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestOnlinePlayers(builder))
                                        .executes(ctx -> executeAccept(StringArgumentType.getString(ctx, "nick")))))
                        .then(literal("deny")
                                .then(argument("nick", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestOnlinePlayers(builder))
                                        .executes(ctx -> executeDeny(StringArgumentType.getString(ctx, "nick")))))
                        .then(literal("remove")
                                .then(argument("nick", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestSavedFriends(builder))
                                        .executes(ctx -> executeRemove(StringArgumentType.getString(ctx, "nick")))))
                        .then(literal("list")
                                .executes(ctx -> executeList()))
                )
                .then(literal("key")
                        .then(argument("secret", StringArgumentType.greedyString())
                                .executes(ctx -> executeSetKey(StringArgumentType.getString(ctx, "secret")))))
        );
    }

    private static int executeSetKey(String secret) {
        MinecraftClient client = MinecraftClient.getInstance();
        String trimmed = secret.trim();
        if (trimmed.length() < 3) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Ключ должен быть не короче 3 символов."));
            return 0;
        }
        FriendStorage.setSecretKey(trimmed);
        PingNetworkProtocol.sendLocalInfo(client, Text.literal("Ключ шифрования обновлён: ")
                .append(Text.literal(trimmed).formatted(Formatting.AQUA)));
        return 1;
    }

    private static int executeAdd(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        if (nick.equalsIgnoreCase(client.player.getGameProfile().name())) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Нельзя добавить самого себя в друзья."));
            return 0;
        }
        if (FriendStorage.isFriend(nick)) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Игрок " + nick + " уже у вас в друзьях."));
            return 0;
        }

        FriendStorage.addOutgoingRequest(nick);
        PingNetworkProtocol.queueCommand(nick, "REQ");
        PingNetworkProtocol.sendLocalInfo(client, Text.literal("Запрос в друзья отправлен игроку ")
                .append(Text.literal(nick).formatted(Formatting.AQUA))
                .append("."));
        return 1;
    }

    private static int executeAccept(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!FriendStorage.hasIncomingRequest(nick) && !FriendStorage.isFriend(nick)) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Нет активного входящего запроса от " + nick + "."));
            return 0;
        }
        FriendStorage.addFriend(nick);
        PingNetworkProtocol.queueCommand(nick, "ACCEPT");
        PingNetworkProtocol.sendLocalInfo(client, Text.literal("Вы приняли запрос от ")
                .append(Text.literal(nick).formatted(Formatting.GREEN))
                .append("."));
        return 1;
    }

    private static int executeDeny(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!FriendStorage.hasIncomingRequest(nick)) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Нет активного входящего запроса от " + nick + "."));
            return 0;
        }
        FriendStorage.removeIncomingRequest(nick);
        PingNetworkProtocol.queueCommand(nick, "DENY");
        PingNetworkProtocol.sendLocalInfo(client, Text.literal("Вы отклонили запрос от ")
                .append(Text.literal(nick).formatted(Formatting.RED))
                .append("."));
        return 1;
    }

    private static int executeRemove(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (FriendStorage.removeFriend(nick)) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Игрок ")
                    .append(Text.literal(nick).formatted(Formatting.YELLOW))
                    .append(" удалён из списка друзей."));
            return 1;
        }
        PingNetworkProtocol.sendLocalInfo(client, Text.literal("Игрок " + nick + " не найден в списке друзей."));
        return 0;
    }

    private static int executeList() {
        MinecraftClient client = MinecraftClient.getInstance();
        Set<String> friends = FriendStorage.getFriends();
        if (friends.isEmpty()) {
            PingNetworkProtocol.sendLocalInfo(client, Text.literal("Ваш список друзей пуст. Используйте /fping friend add <ник>"));
            return 1;
        }

        Set<String> onlineNames = new HashSet<>();
        if (client.getNetworkHandler() != null) {
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                onlineNames.add(entry.getProfile().name().toLowerCase());
            }
        }

        MutableText listText = Text.literal("Друзья (" + friends.size() + "): ");
        boolean first = true;
        for (String friend : friends) {
            if (!first) listText.append(Text.literal(", ").formatted(Formatting.GRAY));
            boolean isOnline = onlineNames.contains(friend.toLowerCase());
            listText.append(Text.literal(friend).formatted(isOnline ? Formatting.GREEN : Formatting.DARK_GRAY));
            if (isOnline) {
                listText.append(Text.literal(" [Онлайн]").formatted(Formatting.DARK_GREEN));
            }
            first = false;
        }

        PingNetworkProtocol.sendLocalInfo(client, listText);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(SuggestionsBuilder builder) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null && client.player != null) {
            String self = client.player.getGameProfile().name();
            String remaining = builder.getRemaining().toLowerCase();
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                String name = entry.getProfile().name();
                if (!name.equalsIgnoreCase(self) && name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestSavedFriends(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String friend : FriendStorage.getFriends()) {
            if (friend.toLowerCase().startsWith(remaining)) {
                builder.suggest(friend);
            }
        }
        return builder.buildFuture();
    }
}
