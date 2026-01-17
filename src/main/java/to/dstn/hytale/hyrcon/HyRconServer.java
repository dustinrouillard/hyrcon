package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class HyRconServer implements AutoCloseable {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final HyRconConfiguration configuration;
    private final CommandExecutor commandExecutor;
    private final Optional<String> requiredPassword;
    private final ExecutorService clientExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public HyRconServer(
        HyRconConfiguration configuration,
        CommandExecutor commandExecutor
    ) {
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration"
        );
        this.commandExecutor = Objects.requireNonNull(
            commandExecutor,
            "commandExecutor"
        );
        this.requiredPassword = this.configuration.password();
        this.clientExecutor = createClientExecutor();
    }

    public void start() {
        if (!configuration.enabled()) {
            LOGGER.atInfo().log(
                "HyRCON server disabled via environment variable %s",
                HyRconConfiguration.ENV_ENABLED
            );
            shutdownExecutor();
            return;
        }

        if (!running.compareAndSet(false, true)) {
            LOGGER.atInfo().log("HyRCON server already running");
            return;
        }

        ServerSocket localSocket = null;
        try {
            localSocket = new ServerSocket();
            localSocket.setReuseAddress(true);
            localSocket.bind(
                new InetSocketAddress(
                    configuration.host(),
                    configuration.port()
                )
            );
            serverSocket = localSocket;
        } catch (IOException ex) {
            running.set(false);
            quietlyClose(localSocket);
            LOGGER.atInfo().log(
                "Unable to bind HyRCON server to %s:%d - %s",
                configuration.host(),
                configuration.port(),
                ex.toString()
            );
            shutdownExecutor();
            return;
        }

        acceptThread = createAcceptThread();
        acceptThread.start();

        LOGGER.atInfo().log(
            "HyRCON server listening on %s:%d (password %s)",
            configuration.host(),
            configuration.port(),
            requiredPassword.isPresent() ? "required" : "disabled"
        );
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            shutdownExecutor();
            return;
        }

        ServerSocket localSocket = serverSocket;
        serverSocket = null;
        if (localSocket != null && !localSocket.isClosed()) {
            quietlyClose(localSocket);
        }

        Thread localAcceptThread = acceptThread;
        acceptThread = null;
        if (localAcceptThread != null) {
            localAcceptThread.interrupt();
            try {
                localAcceptThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        shutdownExecutor();
        LOGGER.atInfo().log("HyRCON server stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
    }

    private Thread createAcceptThread() {
        Thread thread = new Thread(this::acceptLoop, "hyrcon-accept");
        thread.setDaemon(true);
        return thread;
    }

    private void acceptLoop() {
        LOGGER.atInfo().log("HyRCON accept loop started");

        while (running.get()) {
            ServerSocket localSocket = serverSocket;
            if (localSocket == null) {
                break;
            }

            try {
                Socket clientSocket = localSocket.accept();
                configureSocket(clientSocket);
                submitClient(clientSocket);
            } catch (SocketException ex) {
                if (running.get()) {
                    LOGGER.atInfo().log(
                        "HyRCON accept loop socket error: %s",
                        ex.toString()
                    );
                }
                break;
            } catch (IOException ex) {
                LOGGER.atInfo().log(
                    "HyRCON accept loop I/O error: %s",
                    ex.toString()
                );
            }
        }

        LOGGER.atInfo().log("HyRCON accept loop terminated");
    }

    private void submitClient(Socket socket) {
        try {
            clientExecutor.execute(() -> handleClient(socket));
        } catch (RejectedExecutionException ex) {
            LOGGER.atInfo().log(
                "Rejecting HyRCON client %s - executor shutting down",
                safeRemoteAddress(socket)
            );
            quietlyClose(socket);
        }
    }

    private void handleClient(Socket socket) {
        String remote = safeRemoteAddress(socket);
        LOGGER.atInfo().log("HyRCON client connected: %s", remote);

        try (
            socket;
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            greetClient(writer);

            boolean authenticated = !requiredPassword.isPresent();
            String line;

            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }

                if (!authenticated) {
                    authenticated = processAuthentication(command, writer);
                    continue;
                }

                if (isTerminateCommand(command)) {
                    sendAndFlush(writer, "BYE");
                    break;
                }

                if ("PING".equalsIgnoreCase(command)) {
                    sendResponse(writer, CommandResponse.success("PONG"));
                    continue;
                }

                executeCommand(command, writer);
            }
        } catch (IOException ex) {
            if (running.get()) {
                LOGGER.atInfo().log(
                    "HyRCON client %s disconnected due to I/O error: %s",
                    remote,
                    ex.toString()
                );
            }
        } finally {
            LOGGER.atInfo().log("HyRCON client disconnected: %s", remote);
        }
    }

    private void greetClient(BufferedWriter writer) throws IOException {
        writer.write("HYRCON READY");
        writer.newLine();
        writer.write(
            requiredPassword.isPresent() ? "AUTH REQUIRED" : "AUTH OPTIONAL"
        );
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();
    }

    private boolean processAuthentication(String command, BufferedWriter writer)
        throws IOException {
        if (!command.regionMatches(true, 0, "AUTH", 0, 4)) {
            writer.write("ERR Not authenticated");
            writer.newLine();
            writer.write(".");
            writer.newLine();
            writer.flush();
            return false;
        }

        String candidate =
            command.length() > 4 ? command.substring(4).trim() : "";
        boolean success = requiredPassword.map(candidate::equals).orElse(false);

        if (success) {
            writer.write("AUTH OK");
        } else {
            writer.write("AUTH FAIL");
        }
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();

        if (!success) {
            LOGGER.atInfo().log("HyRCON authentication failed");
        }

        return success;
    }

    private void executeCommand(String command, BufferedWriter writer)
        throws IOException {
        try {
            CommandResponse response = commandExecutor.executeValidated(
                command
            );
            sendResponse(writer, response);
        } catch (RuntimeException ex) {
            LOGGER.atInfo().log(
                "Exception while executing command \"%s\": %s",
                command,
                ex.toString()
            );
            sendResponse(
                writer,
                CommandResponse.failure(
                    "Command execution failed: " + ex.getMessage()
                )
            );
        }
    }

    private void sendResponse(BufferedWriter writer, CommandResponse response)
        throws IOException {
        writer.write(response.isSuccess() ? "OK" : "ERR");
        writer.newLine();

        for (String line : response.lines()) {
            writer.write(line);
            writer.newLine();
        }

        if (response.isFailure() && response.hasErrorMessage()) {
            writer.write("ERROR " + response.errorMessage());
            writer.newLine();
        }

        writer.write(".");
        writer.newLine();
        writer.flush();
    }

    private void sendAndFlush(BufferedWriter writer, String line)
        throws IOException {
        writer.write(line);
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();
    }

    private static boolean isTerminateCommand(String command) {
        return (
            "QUIT".equalsIgnoreCase(command) || "EXIT".equalsIgnoreCase(command)
        );
    }

    private static void configureSocket(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (SocketException ignored) {}
    }

    private static String safeRemoteAddress(Socket socket) {
        return socket.getRemoteSocketAddress() == null
            ? "<unknown>"
            : socket.getRemoteSocketAddress().toString();
    }

    private void shutdownExecutor() {
        clientExecutor.shutdownNow();
        try {
            if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.atInfo().log(
                    "Timed out waiting for HyRCON client executor to terminate"
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void quietlyClose(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static void quietlyClose(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static ExecutorService createClientExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(
                    runnable,
                    "hyrcon-client-" + sequence.getAndIncrement()
                );
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newCachedThreadPool(factory);
    }
}
