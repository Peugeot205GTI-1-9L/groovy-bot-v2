package io.groovybot.bot;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import io.groovybot.bot.commands.general.*;
import io.groovybot.bot.commands.music.*;
import io.groovybot.bot.commands.owner.CloseCommand;
import io.groovybot.bot.commands.owner.EvalCommand;
import io.groovybot.bot.commands.owner.UpdateCommand;
import io.groovybot.bot.commands.settings.AnnounceCommand;
import io.groovybot.bot.commands.settings.DjModeCommand;
import io.groovybot.bot.commands.settings.LanguageCommand;
import io.groovybot.bot.commands.settings.PrefixCommand;
import io.groovybot.bot.core.GameAnimator;
import io.groovybot.bot.core.KeyManager;
import io.groovybot.bot.core.audio.LavalinkManager;
import io.groovybot.bot.core.audio.MusicPlayerManager;
import io.groovybot.bot.core.audio.PlaylistManager;
import io.groovybot.bot.core.cache.Cache;
import io.groovybot.bot.core.command.CommandManager;
import io.groovybot.bot.core.command.interaction.InteractionManager;
import io.groovybot.bot.core.entity.Guild;
import io.groovybot.bot.core.entity.User;
import io.groovybot.bot.core.events.bot.AllShardsLoadedEvent;
import io.groovybot.bot.core.lyrics.GeniusClient;
import io.groovybot.bot.core.statistics.ServerCountStatistics;
import io.groovybot.bot.core.statistics.StatusPage;
import io.groovybot.bot.core.statistics.WebsiteStats;
import io.groovybot.bot.core.translation.TranslationManager;
import io.groovybot.bot.io.ErrorReporter;
import io.groovybot.bot.io.FileManager;
import io.groovybot.bot.io.config.Configuration;
import io.groovybot.bot.io.database.PostgreSQL;
import io.groovybot.bot.listeners.*;
import io.groovybot.bot.util.JDASUCKSFILTER;
import io.groovybot.bot.util.YoutubeUtil;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.dv8tion.jda.bot.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.bot.sharding.ShardManager;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.hooks.AnnotatedEventManager;
import net.dv8tion.jda.core.hooks.IEventManager;
import net.dv8tion.jda.core.hooks.SubscribeEvent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.log4j.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;

@Log4j
public class GroovyBot {

    @Getter
    private static GroovyBot instance;
    @Getter
    private final OkHttpClient httpClient;
    @Getter
    private final CommandManager commandManager;
    @Getter
    private final boolean debugMode;
    @Getter
    private final TranslationManager translationManager;
    @Getter
    private final LavalinkManager lavalinkManager;
    private final StatusPage statusPage;
    private final ServerCountStatistics serverCountStatistics;
    @Getter
    private final MusicPlayerManager musicPlayerManager;
    @Getter
    private final InteractionManager interactionManager;
    private final JDASUCKSFILTER errorResponseFilter = new JDASUCKSFILTER();
    @Getter
    private final EventWaiter eventWaiter;
    @Getter
    private final KeyManager keyManager;
    @Getter
    private Configuration config;
    @Getter
    private PostgreSQL postgreSQL;
    @Getter
    private ShardManager shardManager;
    @Getter
    private IEventManager eventManager;
    @Getter
    private Cache<Guild> guildCache;
    @Getter
    private Cache<User> userCache;
    @Getter
    private PlaylistManager playlistManager;
    @Getter
    private final YoutubeUtil youtubeClient;
    @Getter
    private boolean allShardsInitialized = false;
    @Getter
    private final GeniusClient geniusClient;


    private GroovyBot(String[] args) {
        instance = this;
        debugMode = String.join(" ", args).contains("debug");
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        initLogger(args);
        log.info("Starting Groovy ...");
        new FileManager();
        initConfig();
        httpClient = new OkHttpClient();
        postgreSQL = new PostgreSQL();
        lavalinkManager = new LavalinkManager(this);
        statusPage = new StatusPage(httpClient, config.getJSONObject("statuspage"));
        createDefaultDatabase();
        commandManager = new CommandManager(debugMode ? config.getJSONObject("settings").getString("test_prefix") : config.getJSONObject("settings").getString("prefix"), this);
        serverCountStatistics = new ServerCountStatistics(config.getJSONObject("botlists"));
        keyManager = new KeyManager(postgreSQL.getConnection());
        interactionManager = new InteractionManager();
        eventWaiter = new EventWaiter();
        initShardManager();
        musicPlayerManager = new MusicPlayerManager();
        translationManager = new TranslationManager();
        playlistManager = new PlaylistManager(postgreSQL.getConnection());
        youtubeClient = YoutubeUtil.create(this);
        geniusClient = new GeniusClient(config.getJSONObject("genius").getString("token"));
        registerCommands();
    }

    public static void main(String[] args) {
        if (instance != null)
            throw new RuntimeException("Groovy was already initialized in this VM!");
        new GroovyBot(args);
    }

    private Integer retrieveShards() {
        Request request = new Request.Builder()
                .url("https://discordapp.com/api/gateway/bot")
                .addHeader("Authorization", config.getJSONObject("bot").getString("token"))
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            assert response.body() != null;
            return new JSONObject(response.body().string()).getInt("shards");
        } catch (IOException e) {
            log.warn("[JDA] Error while retrieving shards count");
            return config.getJSONObject("settings").getInt("maxShards");
        }
    }

    private void initShardManager() {
        eventManager = new AnnotatedEventManager();
        DefaultShardManagerBuilder shardManagerBuilder = new DefaultShardManagerBuilder()
                .setHttpClient(httpClient)
                .setEventManager(eventManager)
                .setToken(config.getJSONObject("bot").getString("token"))
                .setShardsTotal(retrieveShards())
                .addEventListeners(
                        new ShardsListener(),
                        new CommandLogger(),
                        new GuildLogger(),
                        new SelfMentionListener(),
                        new BetaListener(),
                        commandManager,
                        this,
                        lavalinkManager,
                        interactionManager,
                        eventWaiter
                )
                .setGame(Game.playing("Starting ..."))
                .setStatus(OnlineStatus.DO_NOT_DISTURB);
        try {
            shardManager = shardManagerBuilder.build();
            lavalinkManager.initialize();
        } catch (LoginException e) {
            log.error("[JDA] Could not initialize bot!", e);
            Runtime.getRuntime().exit(1);
        }
    }


    private void createDefaultDatabase() {
        postgreSQL.addDefault(() -> "create table if not exists guilds\n" +
                "(\n" +
                "  id      bigint                not null\n" +
                "    constraint guilds_pkey\n" +
                "    primary key,\n" +
                "  prefix  varchar,\n" +
                "  volume  integer,\n" +
                "  dj_mode boolean default false not null\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists queues\n" +
                "(\n" +
                "  guild_id         bigint not null\n" +
                "    constraint table_name_pkey\n" +
                "    primary key,\n" +
                "  current_track    varchar,\n" +
                "  current_position bigint,\n" +
                "  queue            varchar,\n" +
                "  channel_id       bigint,\n" +
                "  text_channel_id  bigint\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists premium\n" +
                "(\n" +
                "  user_id       bigint               not null\n" +
                "    constraint premium_pkey\n" +
                "    primary key,\n" +
                "  patreon_token varchar,\n" +
                "  type          integer              not null,\n" +
                "  \"check\"       boolean default true not null,\n" +
                "  refresh_token varchar,\n" +
                "  patreon_id    varchar\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists users\n" +
                "(\n" +
                "  id     bigint not null\n" +
                "    constraint users_pkey\n" +
                "    primary key,\n" +
                "  locale varchar(50)\n" +
                ");\n");
        postgreSQL.addDefault(() -> "create table if not exists stats\n" +
                "(\n" +
                "  playing integer,\n" +
                "  servers integer,\n" +
                "  users   integer,\n" +
                "  id      bigint not null\n" +
                "    constraint stats_pk\n" +
                "    primary key\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists lavalink_nodes\n" +
                "(\n" +
                "  uri      varchar not null\n" +
                "    constraint lavalink_nodes_pkey\n" +
                "    primary key,\n" +
                "  password varchar\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists keys\n" +
                "(\n" +
                "  id   serial not null\n" +
                "    constraint keys_pkey\n" +
                "    primary key,\n" +
                "  type varchar,\n" +
                "  key  varchar\n" +
                ");");
        postgreSQL.addDefault(() -> "create table if not exists playlists\n" +
                "(\n" +
                "  id       serial not null\n" +
                "    constraint playlists_pkey\n" +
                "    primary key,\n" +
                "  owner_id bigint,\n" +
                "  tracks   varchar,\n" +
                "  name     varchar\n" +
                ");\n");
        postgreSQL.createDatabases();
    }

    private void initConfig() {
        Configuration configuration = new Configuration("config/config.json");
        final JSONObject botObject = new JSONObject();
        botObject.put("token", "defaultvalue");
        configuration.addDefault("bot", botObject);
        final JSONObject dbObject = new JSONObject();
        dbObject.put("host", "127.0.0.1");
        dbObject.put("port", 5432);
        dbObject.put("database", "defaultvalue");
        dbObject.put("username", "defaultvalue");
        dbObject.put("password", "defaultvalue");
        configuration.addDefault("db", dbObject);
        final JSONArray gamesArray = new JSONArray();
        gamesArray.put("gamename");
        configuration.addDefault("games", gamesArray);
        final JSONObject settingsObject = new JSONObject();
        settingsObject.put("prefix", "g!");
        settingsObject.put("maxShards", 5);
        configuration.addDefault("settings", settingsObject);
        final JSONArray ownersArray = new JSONArray();
        ownersArray.put(264048760580079616L);
        ownersArray.put(254892085000405004L);
        configuration.addDefault("owners", ownersArray);
        final JSONObject webhookObject = new JSONObject();
        webhookObject.put("error_hook", "http://hook.com");
        configuration.addDefault("webhooks", webhookObject);
        final JSONObject youtubeObject = new JSONObject();
        youtubeObject.put("apikey", "defaultvalue");
        configuration.addDefault("youtube", youtubeObject);
        final JSONObject botlistObjects = new JSONObject()
                .put("botlist.space", "defaultvalue")
                .put("bots.ondiscord.xyz", "defaultvalue")
                .put("discordboats.xyz", "defaultvalue")
                .put("discordboats.club", "defaultvalue")
                .put("discordbotlist.com", "defaultvalue")
                .put("discordbot.world", "defaultvalue")
                .put("bots.discord.pw", "defaultvalue")
                .put("discordbotlist.xyz", "defaultvalue")
                .put("discordbots.group", "defaultvalue")
                .put("bots.discordlist.app", "defaultvalue")
                .put("discord.services", "defaultvalue")
                .put("discordsbestbots.xyz", "defaultvalue")
                .put("divinediscordbots.com", "defaultvalue");
        configuration.addDefault("botlists", botlistObjects);
        final JSONObject statusPageObject = new JSONObject();
        statusPageObject.put("page_id", "1337");
        statusPageObject.put("metric_id", "7331");
        statusPageObject.put("api_key", "defaultvalue");
        configuration.addDefault("statuspage", statusPageObject);
        final JSONObject geniusObject = new JSONObject();
        geniusObject.put("token", "PRETTY COOL TOKEN BRO");
        configuration.addDefault("genius", geniusObject);
        this.config = configuration.init();
    }

    private void initLogger(String[] args) {
        final ConsoleAppender consoleAppender = new ConsoleAppender();
        final PatternLayout consolePatternLayout = new PatternLayout("[%d{HH:mm:ss}] [%c] [%p] | %m%n");
        final FileAppender latestLogAppender = new FileAppender();
        final FileAppender dateLogAppender = new FileAppender();
        final PatternLayout filePatternLayout = new PatternLayout("[%d{dd.MMM.yyyy HH:mm:ss,SSS}] [%c] [%p] | %m%n");

        consoleAppender.setLayout(consolePatternLayout);
        consoleAppender.activateOptions();
        consoleAppender.addFilter(errorResponseFilter);
        latestLogAppender.setLayout(filePatternLayout);
        dateLogAppender.setLayout(filePatternLayout);
        dateLogAppender.addFilter(errorResponseFilter);
        latestLogAppender.setFile("logs/latest.log");
        dateLogAppender.setFile(String.format("logs/%s.log", new SimpleDateFormat("dd_MM_yyyy-HH_mm").format(new Date())));
        latestLogAppender.addFilter(errorResponseFilter);
        latestLogAppender.activateOptions();
        dateLogAppender.activateOptions();

        Logger.getRootLogger().addAppender(consoleAppender);
        Logger.getRootLogger().addAppender(latestLogAppender);
        Logger.getRootLogger().addAppender(dateLogAppender);
        Logger.getLogger("org.apache.http").setLevel(Level.OFF);
        Logger.getLogger("org.apache.http.headers").setLevel(Level.OFF);
        Logger.getLogger("org.apache.http.wire").setLevel(Level.OFF);
        Logger.getRootLogger().setLevel(args.length == 0 ? Level.INFO : Level.toLevel(args[0]));
    }

    @SubscribeEvent
    @SuppressWarnings("unused")
    private void onReady(AllShardsLoadedEvent event) {
        allShardsInitialized = true;
        final ErrorReporter errorReporter = new ErrorReporter();
        errorReporter.addFilter(errorResponseFilter);
        Logger.getRootLogger().addAppender(errorReporter);
        new GameAnimator(this);
        guildCache = new Cache<>(Guild.class);
        userCache = new Cache<>(User.class);
        try {
            musicPlayerManager.initPlayers();
        } catch (SQLException | IOException e) {
            log.error("Error while initializing players!", e);
        }
        if (!debugMode) {
            statusPage.start();
            serverCountStatistics.start();
            new WebsiteStats(this);
            //MusicPlayer groovyPlayer = this.musicPlayerManager.getPlayer(event.getJDA().getGuildById(403882830225997825L), event.getJDA().getTextChannelById(486765014976561159L));
            //groovyPlayer.connect(event.getJDA().getVoiceChannelById(486765249488224277L));
        }
    }

    private void registerCommands() {
        commandManager.registerCommands(
                new HelpCommand(),
                new PingCommand(),
                new InfoCommand(),
                new InviteCommand(),
                new SupportCommand(),
                new SponsorCommand(),
                new DonateCommand(),
                new VoteCommand(),
                new StatsCommand(),
                new ShardCommand(),
                new PrefixCommand(),
                new LanguageCommand(),
                new PlayCommand(),
                new PlayTopCommand(),
                new ForcePlayCommand(),
                new ForcePlayCommand(),
                new PauseCommand(),
                new ResumeCommand(),
                new SkipCommand(),
                new JoinCommand(),
                new LeaveCommand(),
                new VolumeCommand(),
                new NowPlayingCommand(),
                new QueueCommand(),
                new ControlCommand(),
                new LoopQueueCommand(),
                new SearchCommand(),
                new ResetCommand(),
                new ClearCommand(),
                new SeekCommand(),
                new DjModeCommand(),
                new LoopCommand(),
                new StopCommand(),
                new ShuffleCommand(),
                new MoveCommand(),
                new RemoveCommand(),
                new KeyCommand(),
                new ForcePlayCommand(),
                new AnnounceCommand(),
                new ForcePlayCommand(),
                new UpdateCommand(),
                new PlaylistCommand(),
                new AutoPlayCommand(),
                new CloseCommand(),
                new EvalCommand(),
                new LyricsCommand()
        );
    }

    public void close() {
        try {
            postgreSQL.getConnection().close();
            if (shardManager != null)
                shardManager.shutdown();
        } catch (Exception e) {
            log.error("Error while closing bot!", e);
        }
    }
}
