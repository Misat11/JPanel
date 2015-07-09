package net.rymate.jpanel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.rymate.jpanel.Utils.Lag;
import net.rymate.jpanel.getters.IndexGetter;
import net.rymate.jpanel.getters.PlayersGetter;
import net.rymate.jpanel.getters.StatsGetter;
import net.rymate.jpanel.getters.SwitchThemeGetter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.java_websocket.drafts.Draft_17;
import spark.ModelAndView;
import spark.template.handlebars.HandlebarsTemplateEngine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.util.*;

import static spark.Spark.*;

/**
 * Main Class of JPanel
 *
 * Created by Ryan on 22/06/2015.
 */
public class PanelPlugin extends JavaPlugin {

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger("Minecraft-Server");
    private static final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
    private ConsoleSocket socket;
    private FileConfiguration config;

    private int httpPort = 4567;
    private int socketPort = 9003;
    private PanelSessions sessions;


    public void onDisable() {
        stop();
        try {
            socket.stop();
            socket = null;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        sessions.destroy();
    }

    public void onEnable() {
        Lag lag = Lag.getInstance();
        sessions = PanelSessions.getInstance();

        getServer().getScheduler().scheduleSyncRepeatingTask(this, lag, 100L, 1L);

        config = getConfig();

        if (config.isConfigurationSection("users")) {
            // load the users
            for (String key : config.getConfigurationSection("users").getKeys(false)) {
                String password = config.getString("users." + key + ".password");
                boolean canEditFiles = config.getBoolean("users." + key + ".canEditFiles");
                PanelUser user = new PanelUser(password, canEditFiles);
                sessions.addUser(key, user);
            }
        }

        config.set("http-port", config.get("http-port", httpPort));
        config.set("websocket-port", config.get("websocket-port", socketPort));

        httpPort = config.getInt("http-port");
        socketPort = config.getInt("websocket-port");

        //saveConfig();

        // init spark server
        //setupSpark();

        staticFileLocation("/public");
        port(httpPort);

        // pages (temporary until the page manager is implemented
        new IndexGetter("/", "index.hbs", this);
        new PlayersGetter("/players", "players.hbs", this);
        new FilesPageGetter("/files", "file-manager.hbs", this);

        // text only paths
        new StatsGetter("/stats");
        new LoginPost("/login");
        new SwitchThemeGetter("/switchtheme");
        new FileGetter("/file/*");


        setupWS();

        System.out.println("[JPanel] JPanel enabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("addlogin")) {
            if (args.length < 1) {
                sender.sendMessage("You must specify a username and a password!");
                return true;
            }

            if (sender instanceof Player) {
                sender.sendMessage("This must be run by the console!");
                return true;
            }

            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(args[1].getBytes());

                byte byteData[] = md.digest();

                //convert the byte to hex format method 1
                StringBuffer sb = new StringBuffer();
                for (int i = 0; i < byteData.length; i++) {
                    sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
                }

                PanelUser user = new PanelUser(sb.toString(), false);

                sessions.addUser(args[0], user);

                config.set("users." + args[0] + ".password", user.password);
                config.set("users." + args[0] + ".canEditFiles", user.canEditFiles);
                saveConfig();

            } catch (Exception e) {
                e.printStackTrace();
                return true;
            }

            return true;
        }
        return false;
    }

    private void setupWS() {
        System.out.println("Starting WebSocket server...");
        try {
            socket = new ConsoleSocket( socketPort, new Draft_17(), this );
            socket.start();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        System.out.println("Started WebSocket server!");
    }

    private void setupSpark() {

        get("/player/:name/:action", (request, response) -> {
            if (!sessions.isLoggedIn(request.cookie("loggedin")))
                return 0;

            managePlayer(request.params(":name"), request.params(":action"));

            return "OK";
        });

        post("/file/*", (request, response) -> {
            if (!sessions.isLoggedIn(request.cookie("loggedin")))
                return 0;

            if (sessions.getAuthedUser(request.cookie("loggedin")).canEditFiles)
                return 0;

            String splat = "";
            for (String file : request.splat()) {
                splat = splat + file;
            }
            splat = splat + "/";

            File file = new File(new File(".").getAbsolutePath() + "/" + splat);

            if (!file.exists()) {
                return false;
            }

            if (!file.isDirectory()) {
                String text = request.body();
                file.delete();
                file.createNewFile();
                PrintWriter out = new PrintWriter(file);
                out.print(text);
                out.close();
            } else {
                return false;
            }

            return true;
        });
    }

    public synchronized void managePlayer(String name, String action) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (action.equalsIgnoreCase("kick"))
                    getServer().getPlayer(name).kickPlayer("Kicked!");

                if (action.equalsIgnoreCase("ban")) {
                    getServer().getPlayer(name).setBanned(true);
                    getServer().getPlayer(name).kickPlayer("Banned!");
                }
            }
        }.runTask(this);

    }

    public Logger getServerLogger() {
        return logger;
    }

    public int getWebSocketPort() {
        return socketPort;
    }
}
