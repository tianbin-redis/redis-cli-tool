package com.moilioncircle.redis.cli.tool.net;

import com.moilioncircle.redis.cli.tool.io.BufferedOutputStream;
import com.moilioncircle.redis.cli.tool.util.CloseableThread;
import com.moilioncircle.redis.cli.tool.util.Sockets;
import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.cmd.RedisCodec;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.util.ByteBuilder;
import com.moilioncircle.redis.replicator.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import static com.moilioncircle.redis.replicator.Constants.COLON;
import static com.moilioncircle.redis.replicator.Constants.DOLLAR;
import static com.moilioncircle.redis.replicator.Constants.MINUS;
import static com.moilioncircle.redis.replicator.Constants.PLUS;
import static com.moilioncircle.redis.replicator.Constants.STAR;

/**
 * @author Baoyi Chen
 */
public class Endpoint implements Closeable, Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(Endpoint.class);
    
    private static final byte[] AUTH = "auth".getBytes();
    private static final byte[] PING = "ping".getBytes();
    private static final byte[] SELECT = "select".getBytes();
    private static final byte[] RESTORE = "restore".getBytes();
    private static final byte[] REPLACE = "replace".getBytes();
    
    private final Socket socket;
    private final OutputStream out;
    private final RedisInputStream in;
    private final CloseableThread reader;
    private final RedisCodec codec = new RedisCodec();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    
    public Endpoint(String host, int port, int db, Configuration conf) {
        try {
            CliSocketFactory factory = new CliSocketFactory(conf);
            this.socket = factory.createSocket(host, port, conf.getConnectionTimeout());
            this.in = new RedisInputStream(this.socket.getInputStream(), 64 * 1024);
            this.out = new BufferedOutputStream(this.socket.getOutputStream(), 64 * 1024);
        } catch (IOException e) {
            throw new AssertionError(e.getMessage(), e);
        }
        if (conf.getAuthPassword() != null) {
            String r = syncAuth(conf.getAuthPassword());
            if (r != null) throw new AssertionError(r);
        } else {
            String r = syncPing();
            if (r != null) throw new AssertionError(r);
        }
        String r = syncSelect(db);
        if (r != null) throw new AssertionError(r);
        reader = CloseableThread.open("reply reader", this);
    }
    
    @Override
    public void run() {
        try {
            String prefix;
            if ((prefix = queue.poll()) != null) {
                String r = parse();
                if (r != null) logger.error("{} failed. reason : {}", prefix, r);
            }
        } catch (Throwable e) {
            close();
            throw new RuntimeException(e);
        }
    }
    
    private String syncAuth(String password) {
        return syncSend(out -> {
            try {
                emit(out, AUTH, password.getBytes());
            } catch (IOException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });
    }
    
    private String syncPing() {
        return syncSend(out -> {
            try {
                emit(out, PING);
            } catch (IOException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });
    }
    
    private String syncSelect(int db) {
        return syncSend(out -> {
            try {
                emit(out, SELECT, String.valueOf(db).getBytes());
            } catch (IOException e) {
                throw new AssertionError(e.getMessage(), e);
            }
        });
    }
    
    private String syncSend(Consumer<OutputStream> consumer) {
        try {
            consumer.accept(out);
            out.flush();
            return parse();
        } catch (Throwable e) {
            close();
            throw new AssertionError(e.getMessage(), e);
        }
    }
    
    public void select(int db) {
        try {
            emit(out, SELECT, String.valueOf(db).getBytes());
        } catch (Throwable e) {
            close();
            throw new AssertionError(e.getMessage(), e);
        }
        queue.offer("select " + db);
    }
    
    public void restore(byte[] key, byte[] ttl, byte[] dump, boolean replace) {
        try {
            if (replace) {
                emit(out, RESTORE, key, ttl, dump, REPLACE);
            } else {
                emit(out, RESTORE, key, ttl, dump);
            }
        } catch (Throwable e) {
            close();
            throw new AssertionError(e.getMessage(), e);
        }
        queue.offer("restore " + Strings.toString(key));
    }
    
    protected void emit(OutputStream out, byte[] command) throws IOException {
        emit(out, command, new byte[0][]);
    }
    
    protected void emit(OutputStream out, byte[] command, byte[]... ary) throws IOException {
        out.write(STAR);
        out.write(String.valueOf(ary.length + 1).getBytes());
        out.write('\r');
        out.write('\n');
        out.write(DOLLAR);
        out.write(String.valueOf(command.length).getBytes());
        out.write('\r');
        out.write('\n');
        out.write(command);
        out.write('\r');
        out.write('\n');
        for (final byte[] arg : ary) {
            out.write(DOLLAR);
            out.write(String.valueOf(arg.length).getBytes());
            out.write('\r');
            out.write('\n');
            out.write(arg);
            out.write('\r');
            out.write('\n');
        }
    }
    
    @Override
    public void close() {
        CloseableThread.close(reader);
        Sockets.closeQuietly(in);
        Sockets.closeQuietly(out);
        Sockets.closeQuietly(socket);
        queue.clear();
    }
    
    public String parse() throws IOException {
        while (true) {
            int c = in.read();
            switch (c) {
                case DOLLAR:
                    // RESP Bulk Strings
                    ByteBuilder builder = ByteBuilder.allocate(128);
                    while (true) {
                        while ((c = in.read()) != '\r') {
                            builder.put((byte) c);
                        }
                        if ((c = in.read()) == '\n') {
                            break;
                        } else {
                            builder.put((byte) c);
                        }
                    }
                    long len = Long.parseLong(builder.toString());
                    if (len == -1) return null;
                    in.skip(len);
                    return null;
                case COLON:
                    // RESP Integers
                    while (true) {
                        while (in.read() != '\r') {
                        }
                        if (in.read() == '\n') {
                            break;
                        }
                    }
                    // As integer
                    return null;
                case STAR:
                    // RESP Arrays
                    builder = ByteBuilder.allocate(128);
                    while (true) {
                        while ((c = in.read()) != '\r') {
                            builder.put((byte) c);
                        }
                        if ((c = in.read()) == '\n') {
                            break;
                        } else {
                            builder.put((byte) c);
                        }
                    }
                    len = Long.parseLong(builder.toString());
                    if (len == -1) return null;
                    for (int i = 0; i < len; i++) {
                        parse();
                    }
                    return null;
                case PLUS:
                    // RESP Simple Strings
                    while (true) {
                        while (in.read() != '\r') {
                        }
                        if (in.read() == '\n') {
                            return null;
                        }
                    }
                case MINUS:
                    // RESP Errors
                    builder = ByteBuilder.allocate(128);
                    while (true) {
                        while ((c = in.read()) != '\r') {
                            builder.put((byte) c);
                        }
                        if ((c = in.read()) == '\n') {
                            return Strings.toString(codec.decode(builder.array()));
                        } else {
                            builder.put((byte) c);
                        }
                    }
                default:
                    throw new AssertionError("expect [$,:,*,+,-] but: " + (char) c);
    
            }
        }
    }
    
    public static void close(Endpoint endpoint) {
        if (endpoint == null) return;
        try {
            endpoint.close();
        } catch (Throwable txt) {
            throw new RuntimeException(txt);
        }
    }
    
    public static void closeQuietly(Endpoint endpoint) {
        if (endpoint == null) return;
        try {
            endpoint.close();
        } catch (Throwable t) {
        }
    }
}