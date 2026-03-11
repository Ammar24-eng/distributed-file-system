import java.io.*;
import java.net.*;
import java.util.*;

public class TestClient {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: java TestClient <controllerPort> <filename> <filesize>");
            return;
        }

        int controllerPort = Integer.parseInt(args[0]);
        String filename = args[1];
        int filesize = Integer.parseInt(args[2]);
        byte[] fileData = new byte[filesize];
        new Random().nextBytes(fileData);

        // Step 1: connect to Controller + STORE
        Socket controllerSocket = new Socket("localhost", controllerPort);
        BufferedReader controllerIn = new BufferedReader(new InputStreamReader(controllerSocket.getInputStream()));
        PrintWriter controllerOut = new PrintWriter(controllerSocket.getOutputStream(), true);

        controllerOut.println(Protocol.STORE_TOKEN + " " + filename + " " + filesize);
        String response = controllerIn.readLine();
        System.out.println("Controller replied: " + response);

        if (!response.startsWith(Protocol.STORE_TO_TOKEN)) {
            System.out.println("Unexpected Controller response. Exiting.");
            controllerSocket.close();
            return;
        }

        String[] parts = response.split(" ");
        List<Integer> dstorePorts = new ArrayList<>();
        for (int i = 1; i < parts.length; i++)
            dstorePorts.add(Integer.parseInt(parts[i]));

        // Step 2: send file to Dstores
        for (int port : dstorePorts) {
            Socket dstoreSocket = new Socket("localhost", port);
            BufferedReader dstoreIn = new BufferedReader(new InputStreamReader(dstoreSocket.getInputStream()));
            PrintWriter dstoreOut = new PrintWriter(dstoreSocket.getOutputStream(), true);

            dstoreOut.println(Protocol.STORE_TOKEN + " " + filename + " " + filesize);
            String ack = dstoreIn.readLine();
            System.out.println("Dstore " + port + " replied: " + ack);

            OutputStream dstoreOutStream = dstoreSocket.getOutputStream();
            dstoreOutStream.write(fileData);
            dstoreOutStream.flush();
            dstoreSocket.close();
        }

        // Step 3: wait for STORE_COMPLETE
        String finalResponse = controllerIn.readLine();
        System.out.println("Controller final response: " + finalResponse);

        // Step 4: test LOAD
        controllerOut.println(Protocol.LOAD_TOKEN + " " + filename);
        String loadResponse = controllerIn.readLine();
        System.out.println("Controller LOAD reply: " + loadResponse);

        if (!loadResponse.startsWith(Protocol.LOAD_FROM_TOKEN)) {
            System.out.println("Unexpected LOAD response. Exiting.");
            controllerSocket.close();
            return;
        }

        int loadDstorePort = Integer.parseInt(loadResponse.split(" ")[1]);
        Socket loadDstoreSocket = new Socket("localhost", loadDstorePort);
        BufferedReader loadIn = new BufferedReader(new InputStreamReader(loadDstoreSocket.getInputStream()));
        PrintWriter loadOut = new PrintWriter(loadDstoreSocket.getOutputStream(), true);

        loadOut.println(Protocol.LOAD_TOKEN + " " + filename);
        String loadDataResponse = loadIn.readLine();
        System.out.println("Dstore " + loadDstorePort + " LOAD response: " + loadDataResponse);

        InputStream fileIn = loadDstoreSocket.getInputStream();
        byte[] receivedData = fileIn.readNBytes(filesize);
        loadDstoreSocket.close();

        // Step 5: verify file contents
        if (Arrays.equals(fileData, receivedData))
            System.out.println("LOAD verification passed ");
        else
            System.out.println("LOAD verification FAILED ");

        controllerSocket.close();
    }
}
