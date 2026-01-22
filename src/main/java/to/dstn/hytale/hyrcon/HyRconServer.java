package to.dstn.hytale.hyrcon;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final Charset SOURCE_CHARSET = StandardCharsets.ISO_8859_1;
    private static final int SOURCE_MAX_PAYLOAD = 4096 - 2;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final HyRconConfiguration configuration;
    private final CommandExecutor commandExecutor;
    private final Optional<String> requiredPassword;
    private final HyRconProtocol protocol;
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
        this.protocol = this.configuration.protocol();
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
            "HyRCON server listening on %s:%d using %s protocol (password %s)",
            configuration.host(),
            configuration.port(),
            protocol.name(),
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
        LOGGER.atInfo().log(
            "HyRCON[%s] client connected: %s",
            protocol.configToken(),
            remote
        );

        try (socket) {
            if (!running.get()) {
                return;
            }
            switch (protocol) {
                case HYRCON -> handleLegacyClient(socket);
                case SOURCE_RCON -> handleSourceClient(socket);
                default -> throw new IllegalStateException(
                    "Unhandled protocol: " + protocol
                );
            }
        } catch (IOException ex) {
            if (running.get()) {
                LOGGER.atInfo().log(
                    "HyRCON[%s] client %s disconnected due to I/O error: %s",
                    protocol.configToken(),
                    remote,
                    ex.toString()
                );
            }
        } finally {
            LOGGER.atInfo().log(
                "HyRCON[%s] client disconnected: %s",
                protocol.configToken(),
                remote
            );
        }
    }

    private void handleLegacyClient(Socket socket) throws IOException {
        try (
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
            greetLegacyClient(writer);

            boolean authenticated = !requiredPassword.isPresent();
            String line;

            while (running.get() && (line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }

                if (!authenticated) {
                    authenticated = processLegacyAuthentication(
                        command,
                        writer
                    );
                    continue;
                }

                if (isTerminateCommand(command)) {
                    sendLegacyAndFlush(writer, "BYE");
                    break;
                }

                if ("PING".equalsIgnoreCase(command)) {
                    sendLegacyResponse(
                        writer,
                        CommandResponse.success("PONG"),
                        command
                    );
                    continue;
                }

                executeLegacyCommand(command, writer);
            }
        }
    }

    private void handleSourceClient(Socket socket) throws IOException {
        try (
            InputStream input = new BufferedInputStream(
                socket.getInputStream()
            );
            OutputStream output = new BufferedOutputStream(
                socket.getOutputStream()
            )
        ) {
            boolean authenticated = !requiredPassword.isPresent();

            while (running.get()) {
                SourceRconPacket packet = readSourcePacket(input);
                if (packet == null) {
                    break;
                }

                switch (packet.type()) {
                    case 3 -> {
                        if (authenticated) {
                            // Already authenticated: acknowledge immediately.
                            writeSourcePacket(
                                output,
                                packet.requestId(),
                                2,
                                EMPTY_BYTES,
                                0,
                                0
                            );
                            output.flush();
                            continue;
                        }
                        boolean success = requiredPassword
                            .map(packet.payload()::equals)
                            .orElse(true);
                        if (success) {
                            authenticated = true;
                            writeSourcePacket(
                                output,
                                packet.requestId(),
                                2,
                                EMPTY_BYTES,
                                0,
                                0
                            );
                            output.flush();
                        } else {
                            LOGGER.atInfo().log(
                                "HyRCON[%s] authentication failed",
                                protocol.configToken()
                            );
                            writeSourcePacket(output, -1, 2, EMPTY_BYTES, 0, 0);
                            output.flush();
                        }
                    }
                    case 2 -> {
                        if (!authenticated) {
                            writeSourcePacket(output, -1, 2, EMPTY_BYTES, 0, 0);
                            output.flush();
                            continue;
                        }
                        String rawCommand =
                            packet.payload() == null ? "" : packet.payload();
                        String command = rawCommand.trim();
                        if (command.isEmpty()) {
                            CommandResponse response = CommandResponse.success(
                                List.of()
                            );
                            sendSourceResponse(
                                output,
                                packet.requestId(),
                                command,
                                response
                            );
                            continue;
                        }
                        CommandResponse response = executeCommand(command);
                        sendSourceResponse(
                            output,
                            packet.requestId(),
                            command,
                            response
                        );
                    }
                    default -> {
                        String message =
                            "Unknown request " +
                            Integer.toHexString(packet.type());
                        writeSourcePacket(
                            output,
                            packet.requestId(),
                            0,
                            message.getBytes(SOURCE_CHARSET),
                            0,
                            message.getBytes(SOURCE_CHARSET).length
                        );
                        writeSourcePacket(
                            output,
                            packet.requestId(),
                            0,
                            EMPTY_BYTES,
                            0,
                            0
                        );
                        output.flush();
                    }
                }
            }
        }
    }

    private void greetLegacyClient(BufferedWriter writer) throws IOException {
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

    private boolean processLegacyAuthentication(
        String command,
        BufferedWriter writer
    ) throws IOException {
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
        boolean success = requiredPassword.map(candidate::equals).orElse(true);

        writer.write(success ? "AUTH OK" : "AUTH FAIL");
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();

        if (!success) {
            LOGGER.atInfo().log(
                "HyRCON[%s] authentication failed",
                protocol.configToken()
            );
        }

        return success;
    }

    private void executeLegacyCommand(String command, BufferedWriter writer)
        throws IOException {
        CommandResponse response = executeCommand(command);
        sendLegacyResponse(writer, response, command);
    }

    private CommandResponse executeCommand(String command) {
        try {
            return commandExecutor.executeValidated(command);
        } catch (RuntimeException ex) {
            LOGGER.atInfo().log(
                "Exception while executing command \"%s\": %s",
                command,
                ex.toString()
            );
            return CommandResponse.failure(
                "Command execution failed: " + ex.getMessage()
            );
        }
    }

    private void sendLegacyResponse(
        BufferedWriter writer,
        CommandResponse response,
        String command
    ) throws IOException {
        writer.write(response.isSuccess() ? "OK" : "ERR");
        writer.newLine();

        for (String line : toDisplayLines(command, response, true)) {
            writer.write(line);
            writer.newLine();
        }

        writer.write(".");
        writer.newLine();
        writer.flush();
    }

    private void sendLegacyAndFlush(BufferedWriter writer, String line)
        throws IOException {
        writer.write(line);
        writer.newLine();
        writer.write(".");
        writer.newLine();
        writer.flush();
    }

    private void sendSourceResponse(
        OutputStream output,
        int requestId,
        String command,
        CommandResponse response
    ) throws IOException {
        List<String> lines = toDisplayLines(command, response, false);
        String payload = String.join("\n", lines);
        byte[] data = payload.getBytes(SOURCE_CHARSET);

        if (data.length == 0) {
            writeSourcePacket(output, requestId, 0, EMPTY_BYTES, 0, 0);
            writeSourcePacket(output, requestId, 0, EMPTY_BYTES, 0, 0);
            output.flush();
            return;
        }

        int offset = 0;
        while (offset < data.length) {
            int chunk = Math.min(SOURCE_MAX_PAYLOAD, data.length - offset);
            writeSourcePacket(output, requestId, 0, data, offset, chunk);
            offset += chunk;
        }

        writeSourcePacket(output, requestId, 0, EMPTY_BYTES, 0, 0);
        output.flush();
    }

    private SourceRconPacket readSourcePacket(InputStream input)
        throws IOException {
        Integer lengthValue = readLittleEndianInt(input);
        if (lengthValue == null) {
            return null;
        }

        int length = lengthValue;
        if (length < 10) {
            throw new IOException("Invalid RCON packet length: " + length);
        }

        byte[] buffer = new byte[length];
        readFully(input, buffer, 0, length);

        int requestId = decodeLittleEndianInt(buffer, 0);
        int type = decodeLittleEndianInt(buffer, 4);
        int payloadLength = length - 8;
        int stringLength = Math.max(0, payloadLength - 2);

        String payload =
            stringLength == 0
                ? ""
                : new String(buffer, 8, stringLength, SOURCE_CHARSET);

        return new SourceRconPacket(requestId, type, payload);
    }

    private static Integer readLittleEndianInt(InputStream input)
        throws IOException {
        int b0 = input.read();
        if (b0 == -1) {
            return null;
        }
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b1 | b2 | b3) < 0) {
            throw new EOFException(
                "Unexpected end of stream while reading 32-bit integer"
            );
        }
        return (
            (b0 & 0xFF) |
            ((b1 & 0xFF) << 8) |
            ((b2 & 0xFF) << 16) |
            ((b3 & 0xFF) << 24)
        );
    }

    private static void readFully(
        InputStream input,
        byte[] buffer,
        int offset,
        int length
    ) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, offset, remaining);
            if (read == -1) {
                throw new EOFException(
                    "Unexpected end of stream while reading RCON packet body"
                );
            }
            offset += read;
            remaining -= read;
        }
    }

    private void writeSourcePacket(
        OutputStream output,
        int requestId,
        int type,
        byte[] payload,
        int offset,
        int length
    ) throws IOException {
        int bodyLength = 4 + 4 + length + 2;
        writeLittleEndianInt(output, bodyLength);
        writeLittleEndianInt(output, requestId);
        writeLittleEndianInt(output, type);
        if (length > 0) {
            output.write(payload, offset, length);
        }
        output.write(0);
        output.write(0);
    }

    private static void writeLittleEndianInt(OutputStream output, int value)
        throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static int decodeLittleEndianInt(byte[] buffer, int offset) {
        return (
            (buffer[offset] & 0xFF) |
            ((buffer[offset + 1] & 0xFF) << 8) |
            ((buffer[offset + 2] & 0xFF) << 16) |
            ((buffer[offset + 3] & 0xFF) << 24)
        );
    }

    private List<String> toDisplayLines(
        String command,
        CommandResponse response,
        boolean includeSyntheticSuccess
    ) {
        List<String> lines = new ArrayList<>(response.lines());

        if (response.isFailure()) {
            if (response.hasErrorMessage()) {
                String errorLine = "ERROR " + response.errorMessage();
                if (!lines.contains(errorLine)) {
                    lines.add(errorLine);
                }
            }
            if (lines.isEmpty()) {
                lines.add("Command execution failed");
            }
        } else if (lines.isEmpty() && includeSyntheticSuccess) {
            lines.add("Command executed: " + command);
        }

        return lines;
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

    private record SourceRconPacket(int requestId, int type, String payload) {}
}
