package ok.dht.test.dergunov;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.dergunov.database.BaseEntry;
import ok.dht.test.dergunov.database.Config;
import ok.dht.test.dergunov.database.Entry;
import ok.dht.test.dergunov.database.MemorySegmentDao;
import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import one.nio.util.Utf8;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ServiceImpl implements Service {

    private static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 4194304; // 4 MB
    private HttpServer server;
    private final ServiceConfig config;
    private MemorySegmentDao database;

    private final long flushThresholdBytes;

    private static final Set<Integer> ALLOWED_METHODS = new HashSet<>(List.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE));

    ServiceImpl(ServiceConfig config, long flushThresholdBytes) {
        this.config = config;
        this.flushThresholdBytes = flushThresholdBytes;
    }

    ServiceImpl(ServiceConfig config) {
        this(config, DEFAULT_FLUSH_THRESHOLD_BYTES);
    }

    private static byte[] toBytes(MemorySegment data) {
        return data == null ? null : data.toByteArray();
    }

    private static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(Utf8.toBytes(data));
    }

    private static MemorySegment fromBytes(byte[] data) {
        return data == null ? null : MemorySegment.ofArray(data);
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        database = new MemorySegmentDao(new Config(config.workingDir(), flushThresholdBytes));
        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                Response response;
                if (!ALLOWED_METHODS.contains(request.getMethod())) {
                    response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
                } else {
                    response = new Response(Response.BAD_REQUEST, Response.EMPTY);
                }
                session.sendResponse(response);
            }
        };
        server.addRequestHandlers(this);
        server.start();

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        database.close();
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id", required = true) String entityId) {
        if (entityId.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> result = database.get(fromString(entityId));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return new Response(Response.OK, toBytes(result.value()));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(@Param(value = "id", required = true) String entityId, Request request) {
        if (entityId.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        database.upsert(new BaseEntry<>(fromString(entityId), fromBytes(request.getBody())));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id", required = true) String entityId) {
        if (entityId.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        database.upsert(new BaseEntry<>(fromString(entityId), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @ServiceFactory(stage = 1, week = 1)
    public static class ServiceFactoryImpl implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
