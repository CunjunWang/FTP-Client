import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Scanner;

public class CSftp {

    private static int MAX_LEN = 255;
    private static Connection connection = new Connection();

    public static void main(String args[]) {
        /**
         * If there are insufficient command line arguments then the program is
         * to print a message and exit.
         */
        if (args.length != 1 && args.length != 2) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            System.exit(0);
        }
        Scanner scanner = new Scanner(new BufferedReader(new InputStreamReader(System.in)));
        connection.initConnection(args);
        handleUserInput(connection, scanner);
    }

    private static void handleUserInput(Connection connection, Scanner scanner) {
        while (true) {
            /**
             *  Whenever the program is awaiting user input it is to print the text 'csftp> '
             *  (Note the blank character after the >)
             */
            System.out.print("csftp> ");
            String userInput = scanner.nextLine();

            /**
             * You may assume that command lines do not have more than 255 characters.
             */
            if (userInput.length() > MAX_LEN) {
                System.out.println("Input too long! Input should be less than 255 characters.");
                continue;
            }

            /**
             * Empty lines and lines starting with the character '#' are to be silently
             * ignored, and a new prompt displayed
             */
            if (!userInput.isEmpty() && !userInput.substring(0, 1).equals("#")) {

                /**
                 * The command is separated from any parameters by one or more spaces and/or tabs.
                 * Tabs or spaces at the end of the line are to be ignored. Your FTP client may detect
                 * certain types of errors.
                 */
                String[] inputParts = userInput.trim().split("\\s+");
                String command = inputParts[0].toLowerCase().trim();
                int commandLength = inputParts.length;

                switch (command) {
                    case "user":
                        if (commandLength == 2) {
                            String userName = inputParts[1];
                            connection.handleRequest("USER", userName);
                            connection.handleResponse("");
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "pw":
                        if (commandLength == 2) {
                            String password = inputParts[1];
                            connection.handleRequest("PASS", password);
                            connection.handleResponse("");
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "quit":
                        if (commandLength == 1) {
                            connection.closeSocket();
                            System.exit(0);
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "get":
                        if (commandLength == 2) {
                            String filePath = inputParts[1];
                            connection.handleRequest("TYPE", "I");
                            connection.handleResponse("");
                            connection.handleRequest("PASV", "");
                            connection.handleResponse("");
                            connection.handleRequest("RETR", filePath);
                            if (connection.handleResponse(filePath)) {
                                connection.saveFile(filePath);
                                connection.handleResponse("");
                            }
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "features":
                        if (commandLength == 1) {
                            connection.handleRequest("FEAT", "");
                            connection.handleResponse("");
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "cd":
                        if (commandLength == 2) {
                            connection.handleRequest("CWD", inputParts[1]);
                            connection.handleResponse(inputParts[1]);
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    case "dir":
                        if (commandLength == 1) {
                            connection.handleRequest("PASV", "");
                            connection.handleResponse("");
                            connection.handleRequest("LIST", "");
                            connection.handleResponse("");
                            connection.readDirectory();
                        } else {
                            System.out.println("0x002 Incorrect number of arguments.");
                        }
                        break;
                    default:
                        System.out.println("0x001 Invalid command.");
                }
            }
        }
    }

    private static class Connection {

        private Socket controlSocket;
        private Socket dataTransferSocket;

        private BufferedReader std_in;
        private PrintWriter std_out;
        private BufferedReader data_in;
        private PrintWriter data_out;

        public Connection() {
            controlSocket = new Socket();
        }

        public void initConnection(String[] args) {
            /**
             The port number is optional. If the port number is not supplied the program is to use port 21.
             */
            String hostName = args[0];
            int portNumber = 0;
            // default port is 21
            if (args.length == 1) {
                portNumber = 21;
            } else if (args.length == 2) {
                try {
                    portNumber = Integer.parseInt(args[1]);
                } catch (NumberFormatException ne) {
                    System.out.println("Please input valid number as second parameter.");
                }
            } else {
                System.out.println("Invalid number of argument provided to start.");
            }
            openConnection(hostName, portNumber);
            handleResponse("");
        }

        public void openConnection(String hostName, int portNumber) {
            try {

                controlSocket = new Socket();
                controlSocket.connect(new InetSocketAddress(hostName, portNumber), 20000);
                std_out = new PrintWriter(controlSocket.getOutputStream());
                std_in = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            } catch (IOException e) {
                handleError("0xFFFC", hostName, Integer.toString(portNumber));
            }
        }

        public void openDataConnection(String hostName, int portNumber) {
            try {
                dataTransferSocket = new Socket();
                dataTransferSocket.connect(new InetSocketAddress(hostName, portNumber), 10000);
                data_out = new PrintWriter(dataTransferSocket.getOutputStream(), true);
                data_in = new BufferedReader(new InputStreamReader(dataTransferSocket.getInputStream()));

            } catch (IOException e) {
                handleError("0x3A2", hostName.toString(), Integer.toString(portNumber));
            }
        }

        public void handleRequest(String command, String parameter) {
            try {
                // https://stackoverflow.com/questions/15521352/bufferedreader-readline-blocks
                String request = command + " " + parameter;
                std_out.write(request + "\n");
                std_out.flush();
                System.out.println("--> " + request);
            } catch (Exception e) {
                handleError("0xFFFD", "", "");
            }
        }

        public boolean handleResponse(String str) {
            try {
                Thread.sleep(500);
                if (std_in != null) {
                    while (std_in.ready()) {
                        String response = std_in.readLine();
                        System.out.println("<-- " + response);

                        if (response.contains("550") && !str.isEmpty()) {
                            handleError("0x38E", str, "");
                            return false;
                        }

                        if (response.contains("Entering Passive Mode")) {
                            handlePassiveMode(response);
                        }
                    }
                }
            } catch (IOException e) {
                handleError("0xFFFD", "", "");
            } catch (InterruptedException e) {
                handleError("0xFFFF", "", "");
            } catch (Exception e) {
                handleError("0xFFFD", "", "");
            }
            return true;
        }

        public void handlePassiveMode(String response) {
            String parameters = response.split("\\(")[1].split("\\)")[0];
            String[] paras = parameters.split(",");
            String hostName = paras[0] + "." + paras[1] + "." + paras[2] + "." + paras[3];
            int portNumber = Integer.parseInt(paras[4]) * 256 + Integer.parseInt(paras[5]);
            openDataConnection(hostName, portNumber);
        }

        public void readDirectory() {
            try {
                Thread.sleep(500);
                if (data_in != null) {
                    while (data_in.ready()) {
                        String entry = data_in.readLine();
                        System.out.println(entry);
                    }
                }
            } catch (IOException e) {
                handleError("0x3A7", "", "");
            } catch (InterruptedException e) {
                handleError("0xFFFF", e.getMessage(), "");
            } catch (Exception e) {
                handleError("0x3A7", e.getMessage(), "");
            }

        }

        // Credit to: http://www.codejava.net/java-se/networking/ftp/java-ftp-file-download-tutorial-and-example
        public void saveFile(String filePath) {
            try {
                String[] pathParts = filePath.split("/");
                String fileName = pathParts[pathParts.length - 1];
                DataInputStream fileInput = new DataInputStream(dataTransferSocket.getInputStream());
                FileOutputStream fileOutputStream = new FileOutputStream(fileName);
                byte[] buffer = new byte[4096];
                int length;
                while ((length = fileInput.read(buffer)) > 0) {
                    fileOutputStream.write(buffer, 0, length);
                }
                fileOutputStream.close();
            } catch (IOException e) {
                handleError("0x3A7", "", "");
            } catch (Exception e) {
                handleError("0xFFFF", e.getMessage(), "");
            }
        }

        public void handleError(String errCode, String para1, String para2) {
            switch (errCode) {
                case "0x001":
                    System.out.println(errCode + " Invalid command.");
                    break;
                case "0x002":
                    System.out.println(errCode + " Incorrect number of arguments.");
                    break;
                case "0x38E":
                    System.out.println(errCode + " Access to local file " + para1 + " denied.");
                    break;
                case "0xFFFC":
                    System.out.println(errCode + " Control connection to " + para1 + " on port " + para2 + " failed to open.");
                    closeSocket();
                    System.exit(0);
                    break;
                case "0xFFFD":
                    System.out.println(errCode + " Control connection I/O error, closing control connection.");
                    closeSocket();
                    System.exit(0);
                    break;
                case "0x3A2":
                    System.out.println(errCode + " Data transfer connection to " + para1 + " on port " + para2 + " failed to open.");
                    break;
                case "0x3A7":
                    System.out.println(errCode + " Data transfer connection I/O error, closing data connection.");
                    break;
                case "0xFFFE":
                    System.out.println(errCode + " Input error while reading commands, terminating.");
                    closeSocket();
                    System.exit(0);
                    break;
                case "0xFFFF":
                    System.out.println(errCode + " Processing error. " + para1);
                    closeSocket();
                    System.exit(0);
                    break;
            }
            System.out.println("0x001 Invalid command");
        }

        public void closeSocket() {
            try {
                controlSocket.close();
            } catch (IOException ioe) {
                System.out.println("Socket already closed!");
            }
        }
    }
}