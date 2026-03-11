import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Dstore {
    private static int port, cport, timeout;
    private static String fileFolder;

    public static void main(String[] args) throws IOException {
        if (args.length != 4) return;
        port       = Integer.parseInt(args[0]);
        cport      = Integer.parseInt(args[1]);
        timeout    = Integer.parseInt(args[2]);
        fileFolder = args[3];

        // prepare folder
        Files.createDirectories(Paths.get(fileFolder));
        File[] files = new File(fileFolder).listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }

        // join
        Socket ctrlSock = new Socket("localhost", cport);
        PrintWriter ctrlOut = new PrintWriter(ctrlSock.getOutputStream(), true);
        BufferedReader ctrlIn = new BufferedReader(new InputStreamReader(ctrlSock.getInputStream()));
        ctrlOut.println(Protocol.JOIN_TOKEN + " " + port);

        // handle REMOVE in background
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                String line;
                while ((line = ctrlIn.readLine()) != null) {
                    if (line.startsWith(Protocol.REMOVE_TOKEN + " ")) {
                        String fn = line.split(" ")[1];
                        Files.deleteIfExists(Paths.get(fileFolder, fn));
                        ctrlOut.println(Protocol.REMOVE_ACK_TOKEN + " " + fn);
                    }
                }
            } catch (IOException ignored) {}
        });

        // accept clients
        ServerSocket ss = new ServerSocket(port);
        while (true) {
            Socket client = ss.accept();
            new Thread(() -> handleClient(client, ctrlOut)).start();
        }
    }

    private static void handleClient(Socket client, PrintWriter ctrlOut) {
        try (
                BufferedReader in   = new BufferedReader(new InputStreamReader(client.getInputStream()));
                OutputStream  out   = client.getOutputStream();
                DataInputStream dis = new DataInputStream(client.getInputStream())
        ) {
            String cmd = in.readLine();
            if (cmd.startsWith(Protocol.STORE_TOKEN + " ")) {
                String[] p = cmd.split(" ");
                String fn = p[1];
                int size  = Integer.parseInt(p[2]);

                // ACK to client
                new PrintWriter(out, true).println(Protocol.ACK_TOKEN);

                // read bytes
                byte[] data = new byte[size];
                dis.readFully(data);
                Files.write(Paths.get(fileFolder, fn), data);

                // STORE_ACK
                ctrlOut.println(Protocol.STORE_ACK_TOKEN + " " + fn);
            } else if (cmd.startsWith(Protocol.LOAD_DATA_TOKEN + " ")
                    || cmd.startsWith(Protocol.LOAD_TOKEN + " ")) {
                String fn = cmd.split(" ")[1];
                Path path = Paths.get(fileFolder, fn);
                if (!Files.exists(path)) {
                    client.close();
                    return;
                }
                byte[] data = Files.readAllBytes(path);
                out.write(data);
                out.flush();
            }
            client.close();
        } catch (IOException ignored) {}
    }
}
