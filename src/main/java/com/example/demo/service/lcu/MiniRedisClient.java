package com.example.demo.service.lcu;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 Redis 客户端（零依赖，仅支持 SET 覆盖写）
 *
 * 只实现本工具需要的 RESP 命令：SET key value。
 * 本地 Redis 无密码，不需要 AUTH。
 */
public class MiniRedisClient implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;

    public MiniRedisClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(3000);
        this.out = socket.getOutputStream();
        this.in = socket.getInputStream();
    }

    /** SET key value（覆盖写），成功返回 true */
    public synchronized boolean set(String key, String value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder cmd = new StringBuilder();
        cmd.append("*3\r\n");
        cmd.append("$3\r\nSET\r\n");
        cmd.append("$").append(kb.length).append("\r\n").append(key).append("\r\n");
        cmd.append("$").append(vb.length).append("\r\n").append(value).append("\r\n");
        out.write(cmd.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        // 读一行响应：+OK
        int ch = in.read();
        return ch == '+';
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
