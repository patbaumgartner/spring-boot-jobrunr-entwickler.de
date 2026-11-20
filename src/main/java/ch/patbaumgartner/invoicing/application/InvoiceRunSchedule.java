package ch.patbaumgartner.invoicing.application;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.jobs.context.JobContext;
import org.springframework.stereotype.Component;

/**
 * The recurring jobs. Their ids are stable, so a restart updates the existing schedule
 * instead of adding a second one.
 */
@Component
public class InvoiceRunSchedule {

	/**
	 * The automatic monthly run always uses this revision; a correction uses a higher
	 * one.
	 */
	static final int MONTHLY_REVISION = 1;

	private static final String ZONE = "Europe/Zurich";

	private final InvoiceRunService service;

	private final SnapshotCatalog snapshots;

	private final InvoiceRunRepository runs;

	private final ArtifactStore files;

	private final InvoiceRunProperties properties;

	public InvoiceRunSchedule(InvoiceRunService service, SnapshotCatalog snapshots, InvoiceRunRepository runs,
			ArtifactStore files, InvoiceRunProperties properties) {
		this.service = service;
		this.snapshots = snapshots;
		this.runs = runs;
		this.files = files;
		this.properties = properties;
	}

	/**
	 * Closes the previous month on the first day of the next one. The month comes from
	 * the creation time of the job and not from the clock, so a retry on a later day
	 * still bills the same month.
	 */
	@Recurring(id = "invoicing-monthly", cron = "0 6 1 * *", zoneId = ZONE)
	@Job(name = "Monthly invoice run", labels = "invoicing")
	public void runMonthlyInvoices(JobContext context) {
		var run = runFor(monthBefore(context.getCreatedAt()));
		context.logger().info("Invoice run %s bills %s".formatted(run.id(), run.billingDate()));
	}

	static YearMonth monthBefore(Instant instant) {
		return YearMonth.from(instant.atZone(ZoneId.of(ZONE))).minusMonths(1);
	}

	/**
	 * The run's unique key, not the recurring id, prevents duplicate business results: a
	 * second trigger for the same month reuses the first run.
	 */
	InvoiceRun runFor(YearMonth month) {
		var snapshot = snapshots.load(SnapshotCatalog.STANDARD).forBillingDate(month.atEndOfMonth());
		return service.submit(snapshot, MONTHLY_REVISION, null);
	}

	/**
	 * Removes the files of runs whose delivery was confirmed longer ago than the
	 * retention period. Runs that are still open keep everything a retry needs. The
	 * portal keeps its receipts: without them it could not detect a repeated delivery.
	 */
	@Recurring(id = "invoicing-cleanup", cron = "0 4 * * *", zoneId = ZONE)
	@Job(name = "Remove expired invoice packages", labels = "invoicing")
	public void removeExpiredPackages() {
		var cutoff = Instant.now().minus(properties.retention());
		for (var runId : runs.deliveredBefore(cutoff)) {
			files.remove(runId);
			runs.expired(runId);
		}
	}

}
