package io.reconguard.e2e;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One real Spring Boot jar running as a child process, with its log written under build/e2e-logs. */
final class ServiceProcess implements AutoCloseable {

    private final String name;
    private final int port;
    private final Process process;
    private final File log;

    private ServiceProcess(String name, int port, Process process, File log) {
        this.name = name;
        this.port = port;
        this.process = process;
        this.log = log;
    }

    static ServiceProcess start(String name, String jar, int port, Map<String, String> props) throws IOException {
        File logDir = new File(System.getProperty("e2e.logs"));
        logDir.mkdirs();
        File log = new File(logDir, name + ".log");

        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        cmd.add("-jar");
        cmd.add(jar);
        cmd.add("--server.port=" + port);
        props.forEach((k, v) -> cmd.add("--" + k + "=" + v));

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        return new ServiceProcess(name, port, p, log);
    }

    /** Blocks until /actuator/health reports UP, or fails pointing at the service log. */
    void awaitHealthy(Duration timeout) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with " + process.exitValue() + "; see " + log);
            }
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200 && res.body().contains("\"status\":\"UP\"")) {
                    return;
                }
            } catch (IOException notUpYet) {
                // still starting
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(name + " not healthy within " + timeout + "; see " + log);
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
