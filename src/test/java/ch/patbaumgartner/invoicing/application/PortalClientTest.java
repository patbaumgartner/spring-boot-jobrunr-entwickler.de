package ch.patbaumgartner.invoicing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.patbaumgartner.invoicing.application.PortalClient.PackageRejectedException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

class PortalClientTest {

	private HttpServer portal;

	private int status;

	private PortalClient client;

	@BeforeEach
	void startPortal() throws IOException {
		portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		portal.createContext("/", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		portal.start();
		var url = URI.create("http://127.0.0.1:" + portal.getAddress().getPort());
		client = new PortalClient(new InvoiceRunProperties(Path.of("."), url, Duration.ofSeconds(2), Duration.ZERO));
	}

	@AfterEach
	void stopPortal() {
		portal.stop(0);
	}

	@Test
	void conflictIsARejectionThatARetryCannotFix() {
		status = 409;

		assertThatThrownBy(this::deliver).isInstanceOf(PackageRejectedException.class);
	}

	@Test
	void tooManyRequestsIsLeftToTheRetry() {
		status = 429;

		assertThatThrownBy(this::deliver).isInstanceOf(RestClientException.class);
	}

	private void deliver() {
		client.deliver(UUID.randomUUID(), new byte[] { 1 }, "checksum");
	}

}
