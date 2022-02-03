package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Server {
    private static final int serverVersion = 2;
    private static Map<String, Connection> connectionMap = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        ConsoleHelper.writeMessage("Введите порт сервера:");
        int port = ConsoleHelper.readInt();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            ConsoleHelper.writeMessage("Чат сервер запущен.");
            Admin admin = new Admin();
            admin.setDaemon(true);
            admin.start();
            while (true) {
                Socket socket = serverSocket.accept();
                new Handler(socket).start();
            }
        } catch (Exception e) {
            ConsoleHelper.writeMessage("Произошла ошибка при запуске или работе сервера.");
        }
    }

    public static void sendBroadcastMessage(Message message){
        for (Map.Entry<String, Connection> pair : connectionMap.entrySet()){
            try {
                pair.getValue().send(message);
            } catch (IOException e){
                ConsoleHelper.writeMessage("Не смогли отправить сообщение " + pair.getValue().getRemoteSocketAddress());
            }
        }
    }

    public static class Admin extends Thread{
        @Override
        public void run() {
            while (true){
                String[] command = ConsoleHelper.readString().split(" ");
                switch (command[0]){
                    case "/kick":{
                        try {
                            connectionMap.get(command[1]).close();
                            connectionMap.remove(command[1]);
                            ConsoleHelper.writeMessage(String.format("Пользователь %s был удалён из чата.", command[1]));
                        } catch (IOException e) {
                            ConsoleHelper.writeMessage("Произошла ошибка при попытки кикнуть пользователя.");
                            break;
                        } catch (NullPointerException e){
                            ConsoleHelper.writeMessage("Пользователь не найден.");
                            break;
                        }
                        sendBroadcastMessage(new Message(MessageType.TEXT, String.format("Пользователь %s был удалён из чата.", command[1])));
                        break;
                    } case "/kickAll":{
                        for (Connection c : connectionMap.values()){
                            try {
                                c.close();
                            } catch (IOException e) {
                                ConsoleHelper.writeMessage("Произошла ошибка при попытки кикнуть пользователя.");
                                break;
                            }
                        }
                        ConsoleHelper.writeMessage(String.format("Все пользователи (%d) были удалены из чата.", connectionMap.size()));
                        connectionMap.clear();
                        break;
                    } case "/list":{
                        for (Map.Entry<String, Connection> pair : connectionMap.entrySet()){
                            System.out.printf("%s - %s%n", pair.getKey(), pair.getValue().getRemoteSocketAddress().toString());
                        }
                    }
                }
            }
        }
    }

    private static class Handler extends Thread{
        private Socket socket;

        public Handler(Socket socket){
            this.socket = socket;
        }

        private String serverHandshake(Connection connection) throws IOException, ClassNotFoundException{
            loop1:
            while (true) {
                connection.send(new Message(MessageType.NAME_REQUEST));
                Message answer = connection.receive();
                if (answer.getType() == MessageType.USER_NAME) {
                    if (!answer.getData().equals("")) {
                        for (String name : connectionMap.keySet()) {
                            if (name.equals(answer.getData())) {
                                continue loop1;
                            }
                        }
                        connectionMap.put(answer.getData(), connection);
                        connection.send(new Message(MessageType.NAME_ACCEPTED));
                        ConsoleHelper.writeMessage(String.format("адрес %s зашёл под именем %s", connection.getRemoteSocketAddress(), answer.getData()));
                        return answer.getData();
                    }
                }
            }
        }

        private void serverMainLoop(Connection connection, String userName) throws IOException, ClassNotFoundException{
            while (true){
                Message in = connection.receive();
                if (in.getType() == MessageType.TEXT){
                    Message out = new Message(MessageType.TEXT, String.format("%s: %s", userName, in.getData()));
                    sendBroadcastMessage(out);
                } else {
                    ConsoleHelper.writeMessage("Получено сообщение от " + socket.getRemoteSocketAddress() + ". Тип сообщения не соответствует протоколу.");
                }
            }
        }

        private void notifyUsers(Connection connection, String userName) throws IOException{
            for (Map.Entry<String, Connection> pair : connectionMap.entrySet()){
                if (!pair.getKey().equals(userName)){
                    connection.send(new Message(MessageType.USER_ADDED, pair.getKey()));
                }
            }
        }

        private boolean serverCheckVersion(Connection connection) throws IOException, ClassNotFoundException{
            Message message = connection.receive();
            if (message.getType() == MessageType.VERSION_REQUEST && Integer.parseInt(message.getData()) == serverVersion){
                connection.send(new Message(MessageType.VERSION_ACCEPTED));
                return true;
            } else {
                connection.send(new Message(MessageType.VERSION_FAILED));
                return false;
            }
        }

        @Override
        public void run() {
            ConsoleHelper.writeMessage("Установленно соединение с удалённым адресом: " + this.socket.getRemoteSocketAddress());
            String name = null;
            try (Connection connection = new Connection(this.socket)) {
                if (!serverCheckVersion(connection)) throw new IOException();
                name = serverHandshake(connection);
                sendBroadcastMessage(new Message(MessageType.USER_ADDED, name));
                notifyUsers(connection, name);
                serverMainLoop(connection, name);
            } catch (IOException | ClassNotFoundException e){
                ConsoleHelper.writeMessage("Произошла ошибка при обмене данными с удалённым адресом: " + socket.getRemoteSocketAddress());
            } finally {
                if (name != null){
                    connectionMap.remove(name);
                    sendBroadcastMessage(new Message(MessageType.USER_REMOVED, name));
                }
                ConsoleHelper.writeMessage("Соеденение с адресом " + socket.getRemoteSocketAddress() + " прервано.");
            }
        }
    }
}
