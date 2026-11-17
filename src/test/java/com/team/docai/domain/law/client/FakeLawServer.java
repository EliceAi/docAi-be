package com.team.docai.domain.law.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 법제처 DRF 를 흉내 내는 로컬 HTTP 서버. 요청 시각·주소를 기록하고, 동시에 처리 중인 요청 수의 최댓값을 잰다. */
public class FakeLawServer implements AutoCloseable {

    public record Reply(int status, String body) {
    }

    public record Received(long startNanos, long endNanos, String path, String query) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile Function<String, Reply> handler = q -> new Reply(500, "no handler");
    private volatile long delayMillis;

    public FakeLawServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/DRF/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/DRF";
    }

    /** handler 입력: "lawSearch.do?..." 처럼 파일명 + 디코딩한 쿼리 */
    public void handler(Function<String, Reply> handler) {
        this.handler = handler;
    }

    public void delayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public List<Received> received() {
        return received;
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    private void handle(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            String path = ex.getRequestURI().getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(),
                    StandardCharsets.UTF_8);
            Reply reply = handler.apply(file + "?" + query);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            ex.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            received.add(new Received(start, System.nanoTime(), file, query));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
