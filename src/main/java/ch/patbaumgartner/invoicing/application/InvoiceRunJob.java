package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.application.InvoiceRun.DeliveryStatus;
import ch.patbaumgartner.invoicing.application.PortalClient.PackageRejectedException;
import ch.patbaumgartner.invoicing.domain.InvoiceCalculator;
import ch.patbaumgartner.invoicing.domain.InvoiceSnapshot;
import ch.patbaumgartner.invoicing.domain.InvoiceSummary;
import java.util.UUID;
import org.jobrunr.JobRunrException;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes one invoice run as a single JobRunr job with four durable steps.
 *
 * <p>
 * When the job is retried after an exception, JobRunr skips the steps that already
 * completed. After a hard crash JobRunr OSS may have lost the progress since its last
 * save and runs a step again. Every step is therefore safe to repeat: it reads the fixed
 * input and replaces its own output.
 */
@Component
public class InvoiceRunJob {

	private final InvoiceRunRepository runs;

	private final ArtifactStore files;

	private final InvoicePackageBuilder packages;

	private final PortalClient portal;

	private final JsonMapper json;

	private final InvoiceCalculator calculator = new InvoiceCalculator();

	public InvoiceRunJob(InvoiceRunRepository runs, ArtifactStore files, InvoicePackageBuilder packages,
			PortalClient portal, JsonMapper json) {
		this.runs = runs;
		this.files = files;
		this.packages = packages;
		this.portal = portal;
		this.json = json;
	}

	/**
	 * The job argument is only the invoice-run id; the data stays in the database and the
	 * artifact store. The step names identify the stored progress and must stay stable
	 * across deployments. Step results are kept small for the same reason.
	 *
	 * <p>
	 * Log lines and the progress bar appear on the job page of the dashboard.
	 */
	@Job(name = "InvoiceRun %0", retries = 3, labels = "invoicing")
	public void run(UUID runId, JobContext context) {
		var progress = context.progressBar(4);
		context.runStepOnce("import-snapshot", () -> importSnapshot(runId));
		progress.setProgress(1);
		context.runStepOnce("calculate-invoices", () -> calculateInvoices(runId));
		progress.setProgress(2);
		String checksum = context.runStepOnce("create-package", () -> createPackage(runId));
		progress.setProgress(3);
		context.logger().info("Package ready, SHA-256 " + checksum);
		String receipt = context.runStepOnce("deliver-package", () -> deliverPackage(runId, checksum));
		progress.setProgress(4);
		context.logger().info("Delivery confirmed: " + receipt);
	}

	private void importSnapshot(UUID runId) {
		runs.recordStep(runId, "import-snapshot");
		var snapshot = json.readValue(runs.get(runId).snapshot(), InvoiceSnapshot.class);
		files.write(runId, Artifact.SNAPSHOT, json.writeValueAsBytes(snapshot));
	}

	private void calculateInvoices(UUID runId) {
		runs.recordStep(runId, "calculate-invoices");
		var snapshot = json.readValue(files.read(runId, Artifact.SNAPSHOT), InvoiceSnapshot.class);
		var plan = calculator.calculate(snapshot);
		files.write(runId, Artifact.PLAN, json.writeValueAsBytes(plan));
		files.write(runId, Artifact.ANALYSIS, json.writeValueAsBytes(InvoiceSummary.of(plan)));
	}

	private String createPackage(UUID runId) {
		runs.recordStep(runId, "create-package");
		String checksum = packages.create(runs.get(runId));
		runs.packageReady(runId);
		return checksum;
	}

	/**
	 * A timeout does not prove that the portal stored nothing. The retry sends the same
	 * bytes under the same id, and the portal answers with the same receipt.
	 */
	String deliverPackage(UUID runId, String checksum) {
		runs.recordStep(runId, "deliver-package");
		try {
			String receipt = portal.deliver(runId, files.read(runId, Artifact.PACKAGE), checksum);
			runs.delivered(runId, receipt);
			return receipt;
		}
		catch (PackageRejectedException rejected) {
			runs.deliveryFailed(runId, DeliveryStatus.REJECTED, rejected.getMessage());
			throw new JobRunrException(rejected.getMessage(), true, rejected);
		}
		catch (RestClientException unconfirmed) {
			runs.deliveryFailed(runId, DeliveryStatus.UNCONFIRMED, unconfirmed.getMessage());
			throw unconfirmed;
		}
	}

}
