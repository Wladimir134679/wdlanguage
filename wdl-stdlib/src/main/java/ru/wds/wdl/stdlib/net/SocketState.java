package ru.wds.wdl.stdlib.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Состояние открытого TCP-сокета.
 */
public record SocketState(Socket socket, BufferedReader reader, PrintWriter writer) {
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
