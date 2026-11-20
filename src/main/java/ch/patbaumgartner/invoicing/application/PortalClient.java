package ch.patbaumgartner.invoicing.application;

import java.net.http.HttpClient;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers invoice packages to the operations portal over HTTP.
 */
@Component
public class PortalClient {

	private final RestClient portal;

	public PortalClient(InvoiceRunProperties properties) {
		var timeout = properties.httpTimeout();
		var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
		requestFactory.setReadTimeout(timeout);
		this.portal = RestClient.builder()
			.baseUrl(properties.portalUrl().toString())
			.requestFactory(requestFactory)
			.build();
	}

	/**
	 * Sends the package under a stable transfer id. The portal stores a package only once
	 * per id, so repeating the call after a timeout is safe and returns the same receipt.
	 * @return the receipt of the portal
	 * @throws PackageRejectedException if the portal refuses the package (HTTP 4xx)
	 * @throws org.springframework.web.client.RestClientException on a timeout, network or
	 * server error; the portal may have stored the package nevertheless
	 */
	public String deliver(UUID transferId, byte[] zip, String checksum) {
		return portal.put()
			.uri("/api/invoice-packages/{transferId}", transferId)
			.contentType(Artifact.PACKAGE.mediaType())
			.header("X-Package-SHA256", checksum)
			.body(zip)
			.retrieve()
			.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
				throw new PackageRejectedException(response.getStatusCode());
			})
			.body(Receipt.class)
			.receipt();
	}

	record Receipt(String receipt) {
	}

	public static class PackageRejectedException extends RuntimeException {

		PackageRejectedException(HttpStatusCode status) {
			super("Portal rejected package: HTTP " + status.value());
		}

	}

}
