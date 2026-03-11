import java.io.*;
import java.net.*;
import java.util.*;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private Map<Integer, Socket> dstoreSockets;
    private int R;
    private Map<String, FileRecord> fileIndex;

    public ClientHandler(Socket clientSocket, Map<Integer, Socket> dstoreSockets, int R, Map<String, FileRecord> fileIndex) {
        this.clientSocket = clientSocket;
        this.dstoreSockets = dstoreSockets;
        this.R = R;
        this.fileIndex = fileIndex;
    }

    @Override
    public void run() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);

            if (dstoreSockets.size() < R) {
                writer.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                clientSocket.close();
                return;
            }

            String command = reader.readLine();
            if (command == null) return;

            // ====================
            // Stage 4: STORE logic
            // ====================
            if (command.startsWith(Protocol.STORE_TOKEN)) {
                String[] parts = command.split(" ");
                if (parts.length != 3) {
                    writer.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
                    return;
                }

                String filename = parts[1];
                long filesize = Long.parseLong(parts[2]);

                synchronized (fileIndex) {
                    if (fileIndex.containsKey(filename) && fileIndex.get(filename).state.equals("store complete")) {
                        writer.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
                        return;
                    }

                    FileRecord file = new FileRecord(filename, filesize);
                    List<Integer> allDstores = new ArrayList<>(dstoreSockets.keySet());
                    Collections.shuffle(allDstores);

                    if (allDstores.size() < R) {
                        writer.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                        return;
                    }

                    List<Integer> selected = allDstores.subList(0, R);
                    for (int port : selected)
                        file.dstorePorts.add(port);

                    fileIndex.put(filename, file);

                    StringBuilder response = new StringBuilder(Protocol.STORE_TO_TOKEN);
                    for (int port : selected)
                        response.append(" ").append(port);

                    writer.println(response.toString());
                    System.out.println("Told client to STORE to: " + selected);
                }

                int acksReceived = 0;
                long startTime = System.currentTimeMillis();

                while (acksReceived < R && (System.currentTimeMillis() - startTime) < 2000) {
                    for (Socket dstoreSocket : dstoreSockets.values()) {
                        try {
                            BufferedReader dstoreReader = new BufferedReader(new InputStreamReader(dstoreSocket.getInputStream()));
                            if (dstoreReader.ready()) {
                                String ack = dstoreReader.readLine();
                                if (ack != null && ack.startsWith(Protocol.STORE_ACK_TOKEN) && ack.contains(filename)) {
                                    acksReceived++;
                                }
                            }
                        } catch (IOException ignored) {}
                    }
                }

                if (acksReceived == R) {
                    fileIndex.get(filename).state = "store complete";
                    writer.println(Protocol.STORE_COMPLETE_TOKEN);
                    System.out.println("STORE_COMPLETE sent to client.");
                } else {
                    fileIndex.remove(filename);
                }
            }

            // ====================
            // Stage 5: LOAD logic
            // ====================
            else if (command.startsWith(Protocol.LOAD_TOKEN)) {
                String[] parts = command.split(" ");
                if (parts.length != 2) {
                    writer.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                    return;
                }

                String filename = parts[1];

                synchronized (fileIndex) {
                    if (!fileIndex.containsKey(filename) ||
                            !fileIndex.get(filename).state.equals("store complete")) {
                        writer.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                        return;
                    }

                    FileRecord file = fileIndex.get(filename);
                    if (file.dstorePorts.isEmpty()) {
                        writer.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                        return;
                    }

                    int selectedPort = file.dstorePorts.iterator().next();
                    writer.println(Protocol.LOAD_FROM_TOKEN + " " + selectedPort);
                }
            }

            // ====================
            // Stage 6: REMOVE logic
            // ====================
            else if (command.startsWith(Protocol.REMOVE_TOKEN)) {
                String[] parts = command.split(" ");
                if (parts.length != 2) {
                    writer.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                    return;
                }

                String filename = parts[1];

                synchronized (fileIndex) {
                    if (!fileIndex.containsKey(filename) ||
                            !fileIndex.get(filename).state.equals("store complete")) {
                        writer.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                        return;
                    }

                    FileRecord file = fileIndex.get(filename);
                    Set<Integer> dstorePorts = new HashSet<>(file.dstorePorts);

                    for (int port : dstorePorts) {
                        Socket dstoreSocket = dstoreSockets.get(port);
                        if (dstoreSocket != null) {
                            PrintWriter dstoreOut = new PrintWriter(dstoreSocket.getOutputStream(), true);
                            dstoreOut.println(Protocol.REMOVE_TOKEN + " " + filename);
                        }
                    }

                    int acksReceived = 0;
                    long startTime = System.currentTimeMillis();

                    while (acksReceived < dstorePorts.size() && (System.currentTimeMillis() - startTime) < 2000) {
                        for (Socket dstoreSocket : dstoreSockets.values()) {
                            try {
                                BufferedReader dstoreReader = new BufferedReader(new InputStreamReader(dstoreSocket.getInputStream()));
                                if (dstoreReader.ready()) {
                                    String ack = dstoreReader.readLine();
                                    if (ack != null && ack.startsWith(Protocol.REMOVE_ACK_TOKEN) && ack.contains(filename)) {
                                        acksReceived++;
                                    }
                                }
                            } catch (IOException ignored) {}
                        }
                    }

                    if (acksReceived == dstorePorts.size()) {
                        fileIndex.remove(filename);
                        writer.println(Protocol.REMOVE_COMPLETE_TOKEN);
                    }
                }
            }

            // ====================
            // Stage 7: LIST logic
            // ====================
            else if (command.equals(Protocol.LIST_TOKEN)) {
                StringBuilder response = new StringBuilder(Protocol.LIST_TOKEN);

                synchronized (fileIndex) {
                    for (String filename : fileIndex.keySet()) {
                        FileRecord file = fileIndex.get(filename);
                        if (file.state.equals("store complete")) {
                            response.append(" ").append(filename);
                        }
                    }
                }

                writer.println(response.toString());
            }

            // ====================
            // Fallback
            // ====================
            else {
                writer.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
            }

            clientSocket.close();

        } catch (IOException ignored) {}
    }
}
