package com.mcdanix.fastping.friends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class FriendStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("fastping-friends");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fastping-friends.json");

    private static final Set<String> friends = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> outgoingRequests = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static final Set<String> incomingRequests = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static String secretKey = "default-fp-secret-key";

    private static class ConfigData {
        String secretKey = "default-fp-secret-key";
        Set<String> friends = new HashSet<>();
    }

    public static void load() {
        friends.clear();
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) {
                if (data.friends != null) {
                    friends.addAll(data.friends);
                }
                if (data.secretKey != null && !data.secretKey.isBlank()) {
                    secretKey = data.secretKey;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Cannot load fastping-friend.json", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            ConfigData data = new ConfigData();
            data.secretKey = secretKey;
            data.friends = new HashSet<>(friends);
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Cannot save fastping-friends.json", e);
        }
    }

    public static String getSecretKey() {
        return secretKey;
    }

    public static void setSecretKey(String newKey) {
        secretKey = newKey;
        save();
    }

    public static boolean isFriend(String name) {
        return friends.contains(name);
    }

    public static boolean addFriend(String name) {
        incomingRequests.remove(name);
        outgoingRequests.remove(name);
        if (friends.add(name)) {
            save();
            return true;
        }
        return false;
    }

    public static boolean removeFriend(String name) {
        if (friends.remove(name)) {
            save();
            return true;
        }
        return false;
    }

    public static Set<String> getFriends() {
        return Collections.unmodifiableSet(friends);
    }

    public static void addOutgoingRequest(String name) {
        outgoingRequests.add(name);
    }

    public static boolean hasOutgoingRequest(String name) {
        return outgoingRequests.contains(name);
    }

    public static void removeOutgoingRequest(String name) {
        outgoingRequests.remove(name);
    }

    public static void addIncomingRequest(String name) {
        incomingRequests.add(name);
    }

    public static boolean hasIncomingRequest(String name) {
        return incomingRequests.contains(name);
    }

    public static void removeIncomingRequest(String name) {
        incomingRequests.remove(name);
    }
}
