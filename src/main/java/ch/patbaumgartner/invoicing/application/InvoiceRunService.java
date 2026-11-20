package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.domain.InvoiceSnapshot;
import java.time.Instant;
import java.util.UUID;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts invoice runs and hands them over to JobRunr.
 */
@Service
public class InvoiceRunService {

	private final InvoiceRunRepository runs;

	private final JobScheduler scheduler;

	private final JsonMapper json;

	public InvoiceRunService(InvoiceRunRepository runs, JobScheduler scheduler, JsonMapper json) {
		this.runs = runs;
		this.scheduler = scheduler;
		this.json = json;
	}

	/**
	 * Stores the invoice run with its fixed input and enqueues the job. Repeating a
	 * request is safe: it returns the existing run and enqueues it if that has not
	 * happened yet.
	 * @param runAt when to process the run, or {@code null} for immediately
	 * @throws InvoiceRunConflictException if company, billing date and revision already
	 * belong to a run with different input
	 * @throws InvoiceRunEnqueueFailedException if the run is stored but JobRunr did not
	 * accept the job
	 */
	public InvoiceRun submit(InvoiceSnapshot snapshot, int revision, Instant runAt) {
		var candidate = InvoiceRun.create(snapshot, json.writeValueAsString(snapshot), revision, runAt);
		runs.insertIfAbsent(candidate);
		var run = runs.get(snapshot.company(), snapshot.billingDate(), revision);
		if (!run.inputHash().equals(candidate.inputHash())) {
			throw new InvoiceRunConflictException(run);
		}
		return run.jobId() == null ? enqueue(run) : run;
	}

	/**
	 * The invoice run is already committed when the job is created. JobRunr OSS cannot
	 * join that transaction, so a crash between the two leaves a run without a job. The
	 * run id is also the job id: JobRunr does not save a job twice under the same id,
	 * which makes repeating the request the repair path.
	 */
	private InvoiceRun enqueue(InvoiceRun run) {
		UUID id = run.id();
		try {
			var jobId = run.runAt() == null ? scheduler.<InvoiceRunJob>enqueue(id, job -> job.run(id, JobContext.Null))
					: scheduler.<InvoiceRunJob>schedule(id, run.runAt(), job -> job.run(id, JobContext.Null));
			runs.enqueued(id, jobId.asUUID());
		}
		catch (RuntimeException failure) {
			runs.enqueueFailed(id, "Not enqueued: " + failure.getMessage());
			throw new InvoiceRunEnqueueFailedException(id, failure);
		}
		return runs.get(id);
	}

	public static class InvoiceRunConflictException extends RuntimeException {

		InvoiceRunConflictException(InvoiceRun existing) {
			super("Revision %d for %s on %s already belongs to invoice run %s with different input"
				.formatted(existing.revision(), existing.company(), existing.billingDate(), existing.id()));
		}

	}

	public static class InvoiceRunEnqueueFailedException extends RuntimeException {

		InvoiceRunEnqueueFailedException(UUID id, Throwable cause) {
			super("Invoice run %s is stored but not enqueued. Repeat the request.".formatted(id), cause);
		}

	}

}
