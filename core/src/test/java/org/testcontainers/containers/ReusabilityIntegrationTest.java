package org.testcontainers.containers;

import lombok.Cleanup;
import org.assertj.core.util.Files;
import org.junit.*;
import org.junit.rules.ExternalResource;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.rnorth.visibleassertions.VisibleAssertions.assertEquals;
import static org.rnorth.visibleassertions.VisibleAssertions.assertTrue;

/**
 * An integration test to demonstrate how to workaround issues with reuse and container networks
 * test assumes that "testcontainers.reuse.enable=true" is set in ~/.testcontainers.properties
 */
public class ReusabilityIntegrationTest {
    private File contentFolder;
    @Rule
    public Network network = Network.newNetwork();

    // TODO: This hack is required in order to make reuse work
    class ReusableNetwork extends ExternalResource implements Network {
        private final String id;

        ReusableNetwork(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void close() {

        }
    }

    @Test
    public void testReusingContainerWithNetworkWithoutReuseHacks() {
        doTestContainerReuse(this::createReusableNginxContainerWithoutReuseHacks);
    }

    @Test
    public void testReusingContainerWithNetworkWithReuseHacks() {
        doTestContainerReuse(this::createReusableNginxContainerWithReuseHacks);
    }

    private void doTestContainerReuse(Supplier<GenericContainer<?>> containerSupplier) {
        Assume.assumeTrue("this test assumes that \"testcontainers.reuse.enable=true\" is set in ~/.testcontainers.properties", TestcontainersConfiguration.getInstance().environmentSupportsReuse());
        try (GenericContainer<?> nginx = containerSupplier.get()) {
            nginx.start();
            assertTrue("should be able to reach nginx container", canReachNginxViaNetwork());
            try (GenericContainer<?> nginxReused = containerSupplier.get()) {
                nginxReused.start();
                assertEquals("containerid should not change when reused", nginx.getContainerId(), nginxReused.getContainerId());
            }
        }
    }

    private GenericContainer<?> createReusableNginxContainerWithReuseHacks() {
        GenericContainer<?> nginx = new GenericContainer<>("nginx:1.9.4")
            .withCommand("nginx", "-g", "daemon off;")
            .withReuse(true)
            // TODO: This hack is required in order to make reuse work
            .withNetwork(new ReusableNetwork(network.getId()));
        // TODO: This hack is required in order to make reuse work
        nginx.setNetworkAliases(Collections.singletonList("nginx"));
        nginx.addExposedPort(80);
        nginx.addFileSystemBind(contentFolder.getAbsolutePath(), "/usr/share/nginx/html", BindMode.READ_ONLY);
        return nginx;
    }

    private GenericContainer<?> createReusableNginxContainerWithoutReuseHacks() {
        GenericContainer<?> nginx = new GenericContainer<>("nginx:1.9.4")
            .withCommand("nginx", "-g", "daemon off;")
            .withReuse(true)
            .withNetwork(network)
            .withNetworkAliases("nginx");
        nginx.addExposedPort(80);
        nginx.addFileSystemBind(contentFolder.getAbsolutePath(), "/usr/share/nginx/html", BindMode.READ_ONLY);
        return nginx;
    }

    private boolean canReachNginxViaNetwork() {
        GenericContainer<?> curl = new GenericContainer<>("curlimages/curl:7.65.3")
            .withNetwork(network)
            .withCommand("curl http://nginx/");
        curl.setStartupCheckStrategy(new OneShotStartupCheckStrategy());
        AtomicBoolean itWorked = new AtomicBoolean();
        curl.withLogConsumer(outputFrame -> {
            if (outputFrame.getUtf8String().contains("This worked")) {
                itWorked.set(true);
            }
        });
        curl.start();
        return itWorked.get();
    }

    @Before
    public void createIndexHtml() throws IOException {
        contentFolder = File.createTempFile("testcontainers.tests", ".tmp");
        contentFolder.delete();
        contentFolder.mkdir();
        contentFolder.setReadable(true, false);
        contentFolder.setWritable(true, false);
        contentFolder.setExecutable(true, false);

        File indexFile = new File(contentFolder, "index.html");
        indexFile.setReadable(true, false);
        indexFile.setWritable(true, false);
        indexFile.setExecutable(true, false);

        @Cleanup PrintStream printStream = new PrintStream(new FileOutputStream(indexFile));
        printStream.println("<html><body>This worked</body></html>");
    }

    @After
    public void removeContentFolder() {
        Files.delete(contentFolder);
    }

}
