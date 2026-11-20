package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.Sha256;
import ch.patbaumgartner.invoicing.domain.InvoiceSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The business record of one invoice run. Its state is independent of the JobRunr job
 * state: a succeeded job and a confirmed delivery are different facts.
 *
 * @param id identifies the run, its JobRunr job and the transfer to the portal
 * @param snapshot the fixed input as JSON; every retry works on exactly this version
 * @param inputHash SHA-256 of {@code snapshot}; detects a conflicting request for the
 * same company, date and revision
 * @param runAt when the job is due, or {@code null} for immediate processing
 * @param jobId the JobRunr job, or {@code null} while the run is not enqueued
 */
public record InvoiceRun(UUID id, String company, LocalDate billingDate, int revision, String snapshot,
		String inputHash, Instant runAt, UUID jobId, PackageStatus packageStatus, DeliveryStatus deliveryStatus,
		String receipt, String lastError) {

	public enum PackageStatus {

		PENDING,

		/** The complete package is available for download. */
		READY,

		/** The retention period is over and the files are removed. */
		EXPIRED

	}

	public enum DeliveryStatus {

		PENDING,

		/**
		 * A delivery attempt failed; the portal may or may not have stored the package.
		 */
		UNCONFIRMED,

		/** The portal refused the package; a retry cannot help. */
		REJECTED,

		/** The portal's receipt is stored. */
		DELIVERED

	}

	static InvoiceRun create(InvoiceSnapshot snapshot, String snapshotJson, int revision, Instant runAt) {
		return new InvoiceRun(UUID.randomUUID(), snapshot.company(), snapshot.billingDate(), revision, snapshotJson,
				Sha256.hex(snapshotJson.getBytes(StandardCharsets.UTF_8)), runAt, null, PackageStatus.PENDING,
				DeliveryStatus.PENDING, null, null);
	}

}
