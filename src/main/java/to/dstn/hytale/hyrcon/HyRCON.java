package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.jetbrains.annotations.NotNull;

public final class HyRCON extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final PlaceholderCommandExecutor commandExecutor =
        new PlaceholderCommandExecutor();
    private HyRconConfiguration configuration;
    private HyRconServer hyRconServer;
    private Thread shutdownHook;
    private boolean shutdownHookRegistered;

    public HyRCON(@NotNull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("HyRCON plugin initialized");
    }

    @Override
    protected void start() {
        super.start();
        commandExecutor.reset();
        try {
            configuration = HyRconConfiguration.fromEnvironment();
        } catch (IllegalArgumentException ex) {
            LOGGER.atInfo().log(
                "Invalid HyRCON configuration: %s",
                ex.getMessage()
            );
            configuration = null;
            return;
        }
        LOGGER.atInfo().log(
            "HyRCON configuration: enabled=%s host=%s port=%d password=%s",
            configuration.enabled(),
            configuration.host(),
            configuration.port(),
            configuration.isPasswordRequired() ? "required" : "optional"
        );

        if (shutdownHookRegistered && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }
        shutdownHook = null;
        shutdownHookRegistered = false;

        if (hyRconServer != null) {
            hyRconServer.stop();
            hyRconServer = null;
        }

        if (!configuration.enabled()) {
            LOGGER.atInfo().log(
                "HyRCON listener disabled via environment setting"
            );
            return;
        }

        hyRconServer = new HyRconServer(configuration, commandExecutor);
        bootstrapCommandExecutor();
        hyRconServer.start();

        Thread hook = new Thread(
            () -> {
                HyRconServer server = hyRconServer;
                if (server != null) {
                    server.stop();
                }
            },
            "hyrcon-shutdown"
        );
        shutdownHook = hook;

        if (hyRconServer.isRunning()) {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHookRegistered = true;
            LOGGER.atInfo().log("HyRCON server started");
            if (!commandExecutor.isReady()) {
                LOGGER.atInfo().log(
                    "Command dispatcher delegate not installed yet; HyRCON commands will fail until wired"
                );
            }
        } else {
            LOGGER.atInfo().log(
                "HyRCON server failed to start; see previous logs for details"
            );
        }
    }

    private void bootstrapCommandExecutor() {
        if (commandExecutor.isReady()) {
            return;
        }

        try {
            DispatcherCommandExecutor dispatcher =
                new DispatcherCommandExecutor();
            commandExecutor.install(dispatcher);
            LOGGER.atInfo().log(
                "Command dispatcher delegate installed: %s",
                dispatcher.getClass().getName()
            );
        } catch (RuntimeException ex) {
            LOGGER.atInfo().log(
                "Failed to initialize command dispatcher delegate: %s",
                ex.toString()
            );
        }
    }

    @Override
    protected void shutdown() {
        if (shutdownHookRegistered && shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {}
        }

        shutdownHook = null;
        shutdownHookRegistered = false;

        if (hyRconServer != null) {
            hyRconServer.stop();
            hyRconServer = null;
        }

        commandExecutor.reset();
        configuration = null;
        LOGGER.atInfo().log("HyRCON plugin disabled");

        super.shutdown();
    }
}
