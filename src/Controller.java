import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Controller {
    private static int cport, R, timeout, rebalancePeriod;

    private static final ConcurrentMap<Integer, Socket> dstoreSockets = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, FileRecord> fileIndex = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Integer> storeAckCount    = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, PrintWriter> storeClientOut = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Integer> removeAckCount    = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, PrintWriter> removeClientOut = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 4) return;
        cport           = Integer.parseInt(args[0]);
        R               = Integer.parseInt(args[1]);
        timeout         = Integer.parseInt(args[2]);
        rebalancePeriod = Integer.parseInt(args[3]);

        ServerSocket server = new ServerSocket(cport);
        while (true) {
            Socket sock = server.accept();
            new Thread(() -> handleConnection(sock)).start();
        }
    }

    private static void handleConnection(Socket sock) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()))) {
            String msg;
            while ((msg = in.readLine()) != null) {
                if (msg.startsWith(Protocol.JOIN_TOKEN + " ")) {
                    int port = Integer.parseInt(msg.split(" ")[1]);
                    dstoreSockets.put(port, sock);
                } else if (msg.startsWith(Protocol.STORE_TOKEN + " ")) {
                    handleStore(msg, sock);
                } else if (msg.startsWith(Protocol.STORE_ACK_TOKEN + " ")) {
                    handleStoreAck(msg);
                } else if (msg.equals(Protocol.LIST_TOKEN)) {
                    handleList(sock);
                } else if (msg.startsWith(Protocol.LOAD_TOKEN + " ")) {
                    handleLoad(msg, sock, false);
                } else if (msg.startsWith(Protocol.RELOAD_TOKEN + " ")) {
                    handleLoad(
                            msg.replaceFirst(Protocol.RELOAD_TOKEN, Protocol.LOAD_TOKEN),
                            sock, true
                    );
                } else if (msg.startsWith(Protocol.REMOVE_TOKEN + " ")) {
                    handleRemove(msg, sock);
                } else if (msg.startsWith(Protocol.REMOVE_ACK_TOKEN + " ")) {
                    handleRemoveAck(msg);
                }
            }
        } catch (IOException ignored) {}
    }

    private static void handleStore(String msg, Socket clientSock) {
        String[] p = msg.split(" ");
        String fn = p[1];
        long fs = Long.parseLong(p[2]);
        PrintWriter out = writerFor(clientSock);

        if (fileIndex.containsKey(fn)) {
            out.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
            return;
        }
        if (dstoreSockets.size() < R) {
            out.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
            return;
        }

        List<Integer> ports = new ArrayList<>(dstoreSockets.keySet());
        Collections.shuffle(ports);
        List<Integer> chosen = ports.subList(0, R);
        fileIndex.put(fn, new FileRecord(fn, fs, new LinkedHashSet<>(chosen)));

        storeAckCount.put(fn, 0);
        storeClientOut.put(fn, out);

        StringBuilder sb = new StringBuilder(Protocol.STORE_TO_TOKEN);
        chosen.forEach(dp -> sb.append(" ").append(dp));
        out.println(sb.toString());
    }

    private static void handleStoreAck(String msg) {
        String fn = msg.split(" ")[1];
        int c = storeAckCount.computeIfPresent(fn, (k,v) -> v+1);
        if (c == R) {
            FileRecord fr = fileIndex.get(fn);
            if (fr != null) {
                fr.state = FileState.STORE_COMPLETE;
                storeClientOut.remove(fn).println(Protocol.STORE_COMPLETE_TOKEN);
            }
        }
    }

    private static void handleList(Socket sock) {
        StringBuilder sb = new StringBuilder(Protocol.LIST_TOKEN);
        fileIndex.values().forEach(fr -> {
            if (fr.state == FileState.STORE_COMPLETE) {
                sb.append(" ").append(fr.filename);
            }
        });
        writerFor(sock).println(sb.toString());
    }

    private static void handleLoad(String msg, Socket sock, boolean isReload) {
        String fn = msg.split(" ")[1];
        PrintWriter out = writerFor(sock);
        FileRecord fr = fileIndex.get(fn);

        if (fr == null || fr.state != FileState.STORE_COMPLETE) {
            out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            return;
        }

        List<Integer> alive = new ArrayList<>();
        fr.dstorePorts.forEach(dp -> {
            if (dstoreSockets.containsKey(dp)) alive.add(dp);
        });
        if (alive.isEmpty()) {
            out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            return;
        }

        int idx = fr.nextLoadIndex.getAndIncrement() % alive.size();
        int chosen = alive.get(idx);
        out.println(Protocol.LOAD_FROM_TOKEN + " " + chosen + " " + fr.filesize);
    }

    private static void handleRemove(String msg, Socket sock) {
        String fn = msg.split(" ")[1];
        PrintWriter out = writerFor(sock);
        FileRecord fr = fileIndex.get(fn);

        if (fr == null || fr.state != FileState.STORE_COMPLETE) {
            out.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
            return;
        }

        fr.state = FileState.REMOVE_IN_PROGRESS;
        removeAckCount.put(fn, 0);
        removeClientOut.put(fn, out);

        fr.dstorePorts.forEach(dp -> {
            Socket ds = dstoreSockets.get(dp);
            if (ds != null) {
                try {
                    new PrintWriter(ds.getOutputStream(), true)
                            .println(Protocol.REMOVE_TOKEN + " " + fn);
                } catch (IOException ignored) {
                    dstoreSockets.remove(dp);
                }
            }
        });
    }

    private static void handleRemoveAck(String msg) {
        String fn = msg.split(" ")[1];
        FileRecord fr = fileIndex.get(fn);
        int needed = fr == null ? 0 : fr.dstorePorts.size();
        int c = removeAckCount.computeIfPresent(fn, (k,v) -> v+1);
        if (c == needed) {
            fileIndex.remove(fn);
            removeClientOut.remove(fn).println(Protocol.REMOVE_COMPLETE_TOKEN);
        }
    }

    private static PrintWriter writerFor(Socket s) {
        try {
            return new PrintWriter(s.getOutputStream(), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class FileRecord {
        final String filename;
        final long filesize;
        final Set<Integer> dstorePorts;
        volatile FileState state = FileState.STORE_IN_PROGRESS;
        final AtomicInteger nextLoadIndex = new AtomicInteger();

        FileRecord(String fn, long fs, Set<Integer> ports) {
            filename = fn;
            filesize = fs;
            dstorePorts = ports;
        }
    }

    private enum FileState {
        STORE_IN_PROGRESS,
        STORE_COMPLETE,
        REMOVE_IN_PROGRESS
    }
}
