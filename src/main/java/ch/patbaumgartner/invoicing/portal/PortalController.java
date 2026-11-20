package ch.patbaumgartner.invoicing.portal;

import ch.patbaumgartner.invoicing.Sha256;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A stand-in for the receiving system. It shows the contract a receiver has to offer so
 * that a sender may safely repeat a delivery: the same transfer id with the same content
 * returns the same receipt, the same id with different content is a conflict.
 */
@RestController
public class PortalController {

	private final JdbcClient jdbc;

	private final Duration delayFirstResponse;

	public PortalController(JdbcClient jdbc, @Value("${portal.delay-first-response}") Duration delayFirstResponse) {
		this.jdbc = jdbc;
		this.delayFirstResponse = delayFirstResponse;
	}

	@PutMapping(path = "/api/invoice-packages/{transferId}", consumes = "application/zip")
	public Receipt receive(@PathVariable UUID transferId, @RequestHeader("X-Package-SHA256") String checksum,
			@RequestBody byte[] zip) throws InterruptedException {
		if (!Sha256.hex(zip).equals(checksum)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checksum does not match package");
		}
		// One statement stores the package or counts the repeated attempt. It is atomic,
		// also for concurrent requests, and returns no row if the content differs.
		var receipt = jdbc.sql("""
				INSERT INTO portal_receipt (transfer_id, checksum, package_bytes, receipt)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (transfer_id) DO UPDATE SET attempts = portal_receipt.attempts + 1
				WHERE portal_receipt.checksum = EXCLUDED.checksum
				RETURNING transfer_id, checksum, receipt, attempts
				""")
			.params(transferId, checksum, zip, "receipt-" + UUID.randomUUID())
			.query(Receipt.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
					"Transfer ID already belongs to different package content"));
		// The package is committed at this point. Delaying the first answer simulates a
		// lost response, not a failed write. The attempt counter is stored, so the
		// simulation survives a restart of the portal.
		if (receipt.attempts() == 1) {
			Thread.sleep(delayFirstResponse);
		}
		return receipt;
	}

	@GetMapping("/api/receipts")
	public List<Receipt> receipts() {
		return jdbc.sql("SELECT transfer_id, checksum, receipt, attempts FROM portal_receipt ORDER BY received_at")
			.query(Receipt.class)
			.list();
	}

	/**
	 * @param receipt the reference the portal issued when it first stored the package
	 * @param attempts how often the package was sent
	 */
	public record Receipt(UUID transferId, String checksum, String receipt, int attempts) {
	}

}
