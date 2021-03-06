package org.kie.kogito.homeautomation.util;

import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.multipart.MultipartForm;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Utility class to execute REST calls in a not-blocking way
 */
@ApplicationScoped
public class RestService {

    protected final Logger LOGGER = Logger.getLogger(this.getClass().getName());

    @Inject
    protected ThreadContext threadContext;
    @Inject
    protected ManagedExecutor managedExecutor;
    @Inject
    protected Vertx vertx;

    public void GET(RestRequest restRequest, Consumer<HttpResponse<Buffer>> callback) {
        LOGGER.info("GET " + restRequest.toString());
        var client = initWebClient(restRequest);

        var get = client.get(restRequest.getEndpoint());
        restRequest.getQueryParams()
                .forEach(entry -> get.addQueryParam(entry.getKey(), entry.getValue()));

        threadContext.withContextCapture(get
                .send()
                .subscribeAsCompletionStage())
                .thenAcceptAsync(callback, managedExecutor);
    }

    public void POSTForm(RestRequest restRequest, PostData postData, Consumer<HttpResponse<Buffer>> callback) {
        LOGGER.info("POSTForm " + restRequest.toString());

        Path tmpFile = createTempFile((String) postData.getContent());
        var form = MultipartForm.create()
                .binaryFileUpload(postData.getName(), postData.getFilename(), tmpFile.toString(), postData.getContentType());

        var client = initWebClient(restRequest);

        var post = client.post(restRequest.getEndpoint());
        restRequest.getQueryParams()
                .forEach(entry -> post.addQueryParam(entry.getKey(), entry.getValue()));

        threadContext.withContextCapture(post
                .sendMultipartForm(form)
                .subscribeAsCompletionStage())
                .thenAcceptAsync(deleteTempFileAndApplyCallback(tmpFile, callback), managedExecutor);
    }

    public void POSTRawBody(RestRequest restRequest, PostData postData, Consumer<HttpResponse<Buffer>> callback) {
        LOGGER.info("POSTRawBody " + restRequest.toString());

        var client = initWebClient(restRequest);

        var post = client.post(restRequest.getEndpoint());
        restRequest.getQueryParams()
                .forEach(entry -> post.addQueryParam(entry.getKey(), entry.getValue()));

        var bufferContent = postData.getContent() instanceof String ? 
            Buffer.buffer((String) postData.getContent()) :
            Buffer.buffer((byte[]) postData.getContent());

        threadContext.withContextCapture(post
                .sendBuffer(bufferContent)
                .subscribeAsCompletionStage())
                .thenAcceptAsync(callback, managedExecutor);
    }

    protected WebClient initWebClient(RestRequest restRequest) {
        return WebClient.create(
                vertx,
                new WebClientOptions()
                        .setDefaultHost(restRequest.getHost())
                        .setDefaultPort(restRequest.getPort())
                        .setSsl(restRequest.isSsl()));

    }

    private Path createTempFile(String content) {
        try {
            Path tmpPath = Paths.get("/tmp/" + UUID.randomUUID().toString());
            Files.write(tmpPath, Base64.getDecoder().decode(content));
            return tmpPath;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Consumer<HttpResponse<Buffer>> deleteTempFileAndApplyCallback(Path tmpPath, Consumer<HttpResponse<Buffer>> callback) {
        return response -> {
            try {
                Files.delete(tmpPath);
                callback.accept(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}