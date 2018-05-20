package ru.pearx.cwl;

import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.sql.SqlService;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.service.whitelist.WhitelistService;
import org.spongepowered.api.text.serializer.TextSerializers;
import ru.pearx.cwl.commands.GenWhitelistCommand;
import ru.pearx.cwl.commands.ReloadConfigCommand;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.sql.DataSource;

@Plugin(id = "cwl", name = "Custom Whitelist", authors = "mrAppleXZ", description = "Customize your whitelist message & generate the whitelist!",
        version = "1.3.2")
public class CWL {
    public static CWL INSTANCE;
    private CommentedConfigurationNode config = null;

    @Inject
    @DefaultConfig(sharedRoot = true)
    private ConfigurationLoader<CommentedConfigurationNode> configManager;

    private ConfigurationLoader<CommentedConfigurationNode> getConfigManager() {
        return this.configManager;
    }

    @Inject
    @ConfigDir(sharedRoot = false)
    private File configDir;





    @Inject
    @DefaultConfig(sharedRoot = true)
    private File defaultConfig;

    private File getDefaultConfig() {
        return this.defaultConfig;
    }

    @Inject
    private Logger log;

    public Task syncTask;

    public Logger getLog() {
        return log;
    }

    public String getWhitelistMessage() {
        return this.config.getNode("main", "whitelist_msg").getString();
    }

    @Nullable
    public DataSource getDbConnection() throws SQLException {
        return Sponge.getServiceManager().provide(SqlService.class).get().getDataSource(this.config.getNode("db", "db_connection").getString());
    }

    public String getDbQuery() {
        return this.config.getNode("db", "db_query").getString();
    }

    public int getSecondsSyncDelay() {
        return this.config.getNode("main", "sync_delay").getInt();
    }

    public String getMode() {
        return this.config.getNode("main", "mode").getString();
    }

    public void reloadConfig() {
        try {
            if (!defaultConfig.exists()) {
                getDefaultConfig().createNewFile();
                this.config = getConfigManager().load();

                this.config.getNode("main", "whitelist_msg").setValue("Send a whitelist request firstly!").setComment("Message displayed to not whitelisted users");
                this.config.getNode("main", "sync_delay").setValue(120).setComment("Time in seconds between automatic whitelist sync");
                this.config.getNode("main", "mode").setValue("none").setComment("Modes: db, json, none, closed");
                this.config.getNode("main", "logging").setValue(false).setComment("Log successful syncs to console?");
                this.config.getNode("main", "bypass").setValue("069a79f4-44e9-4726-a5be-fca90e38aaf5,61699b2e-d327-4a01-9f1e-0ea8c3f06bc6")
                        .setComment("Comma separated list of users that can join when closed.");

                //dbConnection = Sponge.getServiceManager().provide(SqlService.class).get().getDataSource(this.config.getNode("db", "db_connection").getString());
                this.config.getNode("db", "db_connection").setValue("jdbc:mysql://localhost/lc?user=YOUR_USERNAME&password=YOUR_PASSWORD");
                this.config.getNode("db", "db_query").setValue("SELECT `username`, `uuid` from `players` WHERE `access` = 2;");

                this.config.getNode("json", "json_url").setValue("https://example.com/whitelist.json").setComment("Link to a vanilla whitelist.json");

                getConfigManager().save(this.config);
            }
            this.config = getConfigManager().load();
        } catch (IOException ex) {
            getLog().error("Couldn't create default configuration file!");
        }
    }

    public void syncWhitelist() throws SQLException {
        if (getMode().equals("db")) {
            if (getDbConnection() == null) {
                getLog().error("Can't sync the whitelist! Recheck the database settings and run /cwl reload!");
            }
            WhitelistService wh = Sponge.getServiceManager().provide(WhitelistService.class).get();
            wh.getWhitelistedProfiles().stream().forEach(gameProfile -> wh.removeProfile(gameProfile));
            try (Connection conn = getDbConnection().getConnection()) {
                try (PreparedStatement st = conn.prepareStatement(getDbQuery())) {
                    ResultSet res = st.executeQuery();
                    while (res.next()) {
                        String name = res.getString(1);
                        String uuid = res.getString(2);
                        wh.addProfile(GameProfile.of(UUID.fromString(
                                uuid.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                                        "$1-$2-$3-$4-$5")), name));
                    }
                }
            }
            if (this.config.getNode("main", "logging").getBoolean())
                getLog().info("The whitelist was successfully synced!");
        } else if (getMode().equals("closed")) {
            WhitelistService wh = Sponge.getServiceManager().provide(WhitelistService.class).get();
            wh.getWhitelistedProfiles().stream().forEach(gameProfile -> wh.removeProfile(gameProfile));
            // wh.addProfile(GameProfile.of(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "notch"));
            // getLog().error("whitelist:" + Arrays.toString(wh.getWhitelistedProfiles().toArray()));
            List<String> bypassList = Arrays.asList(this.config.getNode("main", "bypass").getString().split(","));
            if (!bypassList.isEmpty()) {
                for (String bypasser : bypassList) {
                    Optional<UserStorageService> optStorage = Sponge.getServiceManager().provide(UserStorageService.class);
                    if (optStorage.isPresent() && !bypasser.equals("")){
                        Optional<User> usr = optStorage.get().get(UUID.fromString(bypasser));
                        if (usr.isPresent()) {
                            wh.addProfile(GameProfile.of(UUID.fromString(bypasser), usr.get().getName()));
                        } else {
                            wh.addProfile(GameProfile.of(UUID.fromString(bypasser), "bypasser"));
                        }
                    }
                }
            }
            if (syncTask != null) {
                syncTask.cancel();
            }
            getLog().info("Whitelist closed!");
        } else if (getMode().equals("json")) {
            try {
                URL url = new URL(this.config.getNode("json", "json_url").getString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                try {
                    JsonReader reader = new JsonReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    JsonParser parser = new JsonParser();
                    JsonArray userArray = parser.parse(reader).getAsJsonArray();
                    WhitelistService wh = Sponge.getServiceManager().provide(WhitelistService.class).get();
                    wh.getWhitelistedProfiles().stream().forEach(gameProfile -> wh.removeProfile(gameProfile));
                    for (JsonElement user : userArray) {
                        JsonObject userObj = user.getAsJsonObject();
                        String name = userObj.get("name").getAsString();
                        String uuid = userObj.get("uuid").getAsString();
                        wh.addProfile(GameProfile.of(UUID.fromString(
                                uuid.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                                        "$1-$2-$3-$4-$5")), name));
                    }
                    if (this.config.getNode("main", "logging").getBoolean())
                        getLog().info("The whitelist was successfully synced!");
                } catch (Exception ex) {
                    getLog().error("sync", ex);
                }
            } catch (Exception ex) {
                getLog().error("opening conn", ex);
            }
        } else {
            getLog().error("Syncing not configured!");
        }
    }

    public void reloadTask() {
        if (syncTask != null) {
            syncTask.cancel();
        }
        if (getSecondsSyncDelay() >= 1) {
            syncTask = Task.builder().execute(() -> {
                        try {
                            syncWhitelist();
                        } catch (Exception e1) {
                            getLog().error("An exception occurred while syncing whitelist!", e1);
                        }
                    }
            ).async().interval(getSecondsSyncDelay(), TimeUnit.SECONDS).submit(this);
        }
    }

    public void reload() throws IOException, SQLException {
        reloadConfig();
        reloadTask();
    }

    @Listener
    public void onPreInitialization(GamePreInitializationEvent event) {
        reloadConfig();
    }

    @Listener
    public void omServerStarted(GameStartedServerEvent e) throws IOException {
        INSTANCE = this;
        Sponge.getCommandManager().register(this,
                CommandSpec.builder().permission("cwl.command.base").child(
                        CommandSpec.builder().permission("cwl.command.whitelist")
                                .executor(new GenWhitelistCommand(this)).build(), "whitelist", "gen-whitelist"
                ).child(
                        CommandSpec.builder().permission("cwl.command.reload")
                                .executor(new ReloadConfigCommand(this)).build(), "reload", "reload-config"
                ).build(), "cwl");
        try {
            reload();
        } catch (SQLException | UncheckedExecutionException e1) {
            getLog().error("An exception occurred while setting up the CWL plugin! Recheck the database settings and run /cwl reload!");
        }
    }

    @Listener(order = Order.FIRST)
    public void onClientConnection(ClientConnectionEvent.Auth event) {
        if (Sponge.getServer().hasWhitelist()) {
            Optional<WhitelistService> wls = Sponge.getServiceManager().provide(WhitelistService.class);
            if (wls.isPresent()) {
                if (!wls.get().isWhitelisted(event.getProfile())) {
                    event.setCancelled(true);
                    event.setMessage(TextSerializers.FORMATTING_CODE.deserialize(getWhitelistMessage()));
                }
            }
        }
    }
}