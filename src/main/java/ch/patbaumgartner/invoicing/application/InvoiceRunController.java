package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.application.InvoiceRun.DeliveryStatus;
import ch.patbaumgartner.invoicing.application.InvoiceRun.PackageStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The REST API: request an invoice run, poll its status and fetch the results under a
 * stable id.
 */
@RestController
@RequestMapping("/api/invoice-runs")
public class InvoiceRunController {

	private final InvoiceRunService service;

	private final InvoiceRunRepository runs;

	private final ArtifactStore files;

	private final SnapshotCatalog snapshots;

	private final StorageProvider jobs;

	public InvoiceRunController(InvoiceRunService service, InvoiceRunRepository runs, ArtifactStore files,
			SnapshotCatalog snapshots, StorageProvider jobs) {
		this.service = service;
		this.runs = runs;
		this.files = files;
		this.snapshots = snapshots;
		this.jobs = jobs;
	}

	/**
	 * Answers {@code 202 Accepted} once the run is stored and the job is enqueued. The
	 * result follows later under the returned location.
	 */
	@PostMapping
	public ResponseEntity<InvoiceRunStatus> request(@Valid @RequestBody InvoiceRunRequest request) {
		var run = service.submit(snapshots.load(request.snapshotId()), request.revision(), request.runAt());
		return ResponseEntity.accepted().location(URI.create(statusUrl(run.id()))).body(toStatus(run));
	}

	@GetMapping
	public List<InvoiceRunStatus> list() {
		return runs.all().stream().map(this::toStatus).toList();
	}

	@GetMapping("/{id}")
	public InvoiceRunStatus status(@PathVariable UUID id) {
		return toStatus(runs.get(id));
	}

	/**
	 * Serves {@code report}, {@code plan}, {@code analysis}, {@code csv},
	 * {@code package}, {@code manifest} or {@code snapshot} once the package is complete.
	 */
	@GetMapping("/{id}/{artifactName}")
	public ResponseEntity<byte[]> artifact(@PathVariable UUID id, @PathVariable String artifactName) {
		var artifact = Artifact.ofUrlName(artifactName)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		switch (runs.get(id).packageStatus()) {
			case PENDING -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Package is not ready");
			case EXPIRED -> throw new ResponseStatusException(HttpStatus.GONE, "Package expired");
			case READY -> {
			}
		}
		if (!files.exists(id, artifact)) {
			// A run that an earlier version of the application created may lack an
			// artifact.
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This package has no " + artifact.filename());
		}
		var response = ResponseEntity.ok().contentType(artifact.mediaType());
		if (artifact.isDownload()) {
			var filename = "invoice-run-" + id + "-" + artifact.filename();
			response.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(filename).build().toString());
		}
		return response.body(files.read(id, artifact));
	}

	private InvoiceRunStatus toStatus(InvoiceRun run) {
		var links = new TreeMap<String, String>();
		links.put("self", statusUrl(run.id()));
		if (run.packageStatus() == PackageStatus.READY) {
			for (var artifact : Artifact.values()) {
				if (files.exists(run.id(), artifact)) {
					links.put(artifact.urlName(), statusUrl(run.id()) + "/" + artifact.urlName());
				}
			}
		}
		return new InvoiceRunStatus(run.id(), run.company(), run.billingDate(), run.revision(), run.runAt(),
				run.jobId(), jobState(run), run.packageStatus(), run.deliveryStatus(), run.receipt(), run.lastError(),
				runs.executedSteps(run.id()), links);
	}

	/**
	 * The technical view next to the business view. JobRunr removes a succeeded job after
	 * its retention period; the invoice run and its delivery status remain.
	 */
	private StateName jobState(InvoiceRun run) {
		if (run.jobId() == null) {
			return null;
		}
		try {
			return jobs.getJobById(run.jobId()).getState();
		}
		catch (JobNotFoundException removed) {
			return null;
		}
	}

	private static String statusUrl(UUID id) {
		return "/api/invoice-runs/" + id;
	}

	/**
	 * @param snapshotId {@code standard} or {@code correction}
	 * @param revision a new revision of the same month is a new invoice run
	 * @param runAt when to process the run; omitted for immediate processing
	 */
	record InvoiceRunRequest(
			@NotNull @Pattern(regexp = SnapshotCatalog.STANDARD + "|" + SnapshotCatalog.CORRECTION) String snapshotId,
			@Min(1) int revision, Instant runAt) {
	}

	/**
	 * The status of an invoice run. Absent values are omitted: no {@code jobId} means the
	 * run is not enqueued.
	 *
	 * @param jobState the state of the JobRunr job; a {@code SUCCEEDED} job and a
	 * {@code DELIVERED} package are different facts
	 * @param executedSteps every execution of a step body in order; a step that a retry
	 * skipped appears only once
	 * @param links {@code self} is the status URL; the artifacts follow once the package
	 * is ready
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InvoiceRunStatus(UUID id, String company, LocalDate billingDate, int revision, Instant runAt, UUID jobId,
			StateName jobState, PackageStatus packageStatus, DeliveryStatus deliveryStatus, String receipt,
			String lastError, List<String> executedSteps, Map<String, String> links) {
	}

}
