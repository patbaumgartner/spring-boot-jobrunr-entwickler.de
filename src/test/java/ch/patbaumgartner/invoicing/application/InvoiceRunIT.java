package ch.patbaumgartner.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.patbaumgartner.invoicing.Sha256;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.zip.ZipInputStream;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs InvoiceRun with its real JobRunr worker and portal endpoint against PostgreSQL.
 * The portal loses its first HTTP response for every transfer id.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT,
		properties = { "invoicing.http-timeout=250ms", "jobrunr.dashboard.enabled=false" })
class InvoiceRunIT {

	private static final int PORT = TestSocketUtils.findAvailableTcpPort();

	/**
	 * Started once and removed by Testcontainers when the JVM exits, so the database
	 * outlives the Spring context. JobRunr needs that for a clean shutdown.
	 */
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			DockerImageName.parse("postgres@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42")
				.asCompatibleSubstituteFor("postgres"));

	static {
		postgres.start();
	}

	@TempDir
	static Path dataDirectory;

	/** The run of the lost-response scenario; several tests inspect its outcome. */
	private static Delivery delivery;

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private InvoiceRunService service;

	@Autowired
	private InvoiceRunRepository runs;

	@Autowired
	private ArtifactStore files;

	@Autowired
	private InvoicePackageBuilder packages;

	@Autowired
	private InvoiceRunJob job;

	@Autowired
	private InvoiceRunSchedule schedule;

	@Autowired
	private SnapshotCatalog snapshots;

	@Autowired
	private StorageProvider jobs;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JsonMapper json;

	private RestClient invoiceRun;

	private RestClient portal;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("server.port", () -> PORT);
		registry.add("invoicing.portal-url", () -> "http://127.0.0.1:" + PORT);
		registry.add("portal.delay-first-response", () -> "800ms");
		registry.add("invoicing.data-directory", () -> dataDirectory.toString());
	}

	@BeforeEach
	void createClients() {
		invoiceRun = client(port);
		portal = invoiceRun;
	}

	@Test
	void retryAfterLostResponseSkipsTheCompletedSteps() {
		var status = lostResponseDelivery().status();

		assertThat(status.path("deliveryStatus").asString()).isEqualTo("DELIVERED");
		assertThat(status.path("jobState").asString()).isEqualTo("SUCCEEDED");
		assertThat(strings(status.path("executedSteps"))).containsExactly("import-snapshot", "calculate-invoices",
				"create-package", "deliver-package", "deliver-package");
	}

	@Test
	void portalStoresThePackageOnceDespiteTwoAttempts() {
		var delivered = lostResponseDelivery();

		assertThat(delivered.portalReceipts()).singleElement().satisfies(receipt -> {
			assertThat(receipt.path("attempts").asInt()).isEqualTo(2);
			assertThat(receipt.path("receipt").asString()).isEqualTo(delivered.status().path("receipt").asString());
		});
	}

	@Test
	void packageContainsInvoiceDataExportsReportAndManifestWithTheirChecksums() throws IOException {
		var id = lostResponseDelivery().id();

		var download = get("/api/invoice-runs/" + id + "/package", byte[].class);
		var files = unzip(download.getBody());

		assertThat(download.getHeaders().getContentDisposition().getFilename()).endsWith("package.zip");
		assertThat(files.keySet()).containsExactlyInAnyOrder("plan.json", "analysis.json", "invoices.csv",
				"report.html", "manifest.json");
		var manifest = json.readTree(files.get("manifest.json"));
		assertThat(manifest.path("transferId").asString()).isEqualTo(id.toString());
		for (var name : List.of("plan.json", "analysis.json", "invoices.csv", "report.html")) {
			assertThat(manifest.path("files").path(name).asString()).isEqualTo(Sha256.hex(files.get(name)));
		}
		var plan = json.readTree(files.get("plan.json"));
		var analysis = json.readTree(files.get("analysis.json"));
		assertThat(plan.path("invoices")).hasSize(3);
		assertThat(analysis.path("totalGrossCents").asLong()).isEqualTo(
				plan.path("invoices").valueStream().mapToLong(invoice -> invoice.path("grossCents").asLong()).sum());
		assertThat(new String(files.get("invoices.csv"), StandardCharsets.UTF_8).lines()).hasSize(5)
			.first()
			.asString()
			.startsWith("invoiceNumber,customerId");
	}

	@Test
	void reportIsServedAsHtmlOnceThePackageIsReady() {
		var report = get("/api/invoice-runs/" + lostResponseDelivery().id() + "/report", String.class);

		assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(report.getHeaders().getContentType()).satisfies(MediaType.TEXT_HTML::isCompatibleWith);
		assertThat(report.getBody()).contains("Monthly invoice report").doesNotContain("__INVOICE_REPORT_DATA__");
	}

	/**
	 * The process dies after the portal committed the package but before InvoiceRun
	 * stored the receipt. Running the step again must confirm the same receipt.
	 */
	@Test
	void repeatedDeliveryAfterLostLocalStatusConfirmsTheSameReceipt() {
		var delivered = lostResponseDelivery();
		var id = delivered.id();
		jdbc.sql("UPDATE invoice_run SET delivery_status = 'UNCONFIRMED', receipt = NULL WHERE id = ?")
			.param(id)
			.update();

		var receipt = job.deliverPackage(id, Sha256.hex(files.read(id, Artifact.PACKAGE)));

		assertThat(receipt).isEqualTo(delivered.status().path("receipt").asString());
		assertThat(runs.get(id).deliveryStatus()).isEqualTo(InvoiceRun.DeliveryStatus.DELIVERED);
		assertThat(portalReceipts(id)).hasSize(1);
	}

	/**
	 * A crash can repeat the package step. The rebuilt package must be the one the portal
	 * may already know.
	 */
	@Test
	void rebuiltPackageKeepsItsChecksum() {
		var id = lostResponseDelivery().id();
		var checksum = Sha256.hex(files.read(id, Artifact.PACKAGE));

		assertThat(packages.create(runs.get(id))).isEqualTo(checksum);
	}

	@Test
	void repeatedRequestReturnsTheSameRunAndDifferentInputIsAConflict() {
		var runAt = Instant.now().plus(Duration.ofHours(1));
		var first = requestInvoiceRun("standard", 110, runAt);
		var statusUrl = "/api/invoice-runs/" + id(first);

		var repeated = requestInvoiceRun("standard", 110, runAt);
		var conflicting = requestInvoiceRun("correction", 110, runAt);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(first.getHeaders().getLocation()).hasPath(statusUrl);
		assertThat(first.getBody().path("links").path("self").asString()).isEqualTo(statusUrl);
		assertThat(first.getBody().path("jobState").asString()).isEqualTo("SCHEDULED");
		assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(id(repeated)).isEqualTo(id(first));
		assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	/**
	 * A crash between storing the run and creating the job leaves a run that JobRunr does
	 * not know.
	 */
	@Test
	void repeatedRequestEnqueuesARunThatWasStoredWithoutAJob() {
		var snapshot = snapshots.load("standard");
		var runAt = Instant.now().plus(Duration.ofHours(1));
		var stored = InvoiceRun.create(snapshot, json.writeValueAsString(snapshot), 120, runAt);
		runs.insertIfAbsent(stored);

		var repeated = requestInvoiceRun("standard", 120, runAt);

		assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(repeated.getBody().path("jobId").asString()).isEqualTo(stored.id().toString());
		assertThat(jobs.getJobById(stored.id()).hasState(StateName.SCHEDULED)).isTrue();
	}

	/**
	 * After a crash between creating the job and noting its id, the repeated request must
	 * not create a second job.
	 */
	@Test
	void repeatedRequestAdoptsAJobThatAlreadyExists() {
		var runAt = Instant.now().plus(Duration.ofHours(1));
		var id = id(requestInvoiceRun("standard", 130, runAt));
		var jobsBefore = jobs.countJobs(StateName.SCHEDULED);
		jdbc.sql("UPDATE invoice_run SET job_id = NULL WHERE id = ?").param(id).update();

		var repeated = requestInvoiceRun("standard", 130, runAt);

		assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(repeated.getBody().path("jobId").asString()).isEqualTo(id.toString());
		assertThat(jobs.countJobs(StateName.SCHEDULED)).isEqualTo(jobsBefore);
	}

	@Test
	void unknownInvoiceSnapshotIsABadRequest() {
		assertThat(requestInvoiceRun("unknown", 140, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void monthlyTriggerCreatesOneRunPerMonthEndAndANewRevisionIsANewRun() {
		var month = YearMonth.of(2032, 2);
		var billingDate = month.atEndOfMonth();

		var first = schedule.runFor(month);
		var repeated = schedule.runFor(month);
		service.submit(snapshots.load("standard").forBillingDate(billingDate), InvoiceRunSchedule.MONTHLY_REVISION + 1,
				Instant.now().plusSeconds(3600));

		assertThat(billingDate).hasDayOfMonth(29);
		assertThat(repeated.id()).isEqualTo(first.id());
		assertThat(runs.all()).filteredOn(run -> run.billingDate().equals(billingDate))
			.extracting(InvoiceRun::revision)
			.containsExactlyInAnyOrder(1, 2);
	}

	@Test
	void rejectedPackageFailsTheJobWithoutRetry() {
		var id = id(requestInvoiceRun("standard", 150, Instant.now().plus(Duration.ofHours(1))));
		assertThat(deliver(id, "another package").getStatusCode()).isEqualTo(HttpStatus.OK);

		runNow(id);

		await().atMost(Duration.ofSeconds(60)).until(() -> jobs.getJobById(id).hasState(StateName.FAILED));
		assertThat(jobs.getJobById(id).getJobStatesOfType(FailedState.class)).hasSize(1);
		assertThat(runs.get(id).deliveryStatus()).isEqualTo(InvoiceRun.DeliveryStatus.REJECTED);
		assertThat(runs.get(id).packageStatus()).isEqualTo(InvoiceRun.PackageStatus.READY);
	}

	@Test
	void cleanupRemovesOnlyPackagesWhoseDeliveryIsOlderThanTheRetention() {
		var recent = lostResponseDelivery().id();
		var expired = id(requestInvoiceRun("standard", 170, Instant.now().plus(Duration.ofHours(1))));
		var undelivered = id(requestInvoiceRun("standard", 171, Instant.now().plus(Duration.ofHours(1))));
		for (var id : List.of(expired, undelivered)) {
			files.write(id, Artifact.REPORT, "invoice report".getBytes(StandardCharsets.UTF_8));
			runs.packageReady(id);
		}
		jdbc.sql("""
				UPDATE invoice_run
				SET delivery_status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
				WHERE id = ?
				""").param(expired).update();

		schedule.removeExpiredPackages();

		assertThat(get("/api/invoice-runs/" + expired + "/report", String.class).getStatusCode())
			.isEqualTo(HttpStatus.GONE);
		assertThat(status(expired).path("links").propertyNames()).containsExactly("self");
		assertThat(get("/api/invoice-runs/" + undelivered + "/report", String.class).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		// Only the report was written for this run: other artifacts are neither linked
		// nor served.
		assertThat(status(undelivered).path("links").propertyNames()).containsExactlyInAnyOrder("self", "report");
		assertThat(get("/api/invoice-runs/" + undelivered + "/csv", String.class).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(get("/api/invoice-runs/" + recent + "/report", String.class).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void portalAnswersConcurrentDeliveriesWithOneReceiptAndRejectsDifferentContent() throws Exception {
		var transferId = UUID.randomUUID();
		Callable<ResponseEntity<JsonNode>> delivery = () -> deliver(transferId, "package");

		var receipts = new HashSet<String>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var response : executor.invokeAll(IntStream.range(0, 8).mapToObj(i -> delivery).toList())) {
				assertThat(response.get().getStatusCode()).isEqualTo(HttpStatus.OK);
				receipts.add(response.get().getBody().path("receipt").asString());
			}
		}

		assertThat(receipts).hasSize(1);
		assertThat(portalReceipts(transferId)).singleElement()
			.satisfies(receipt -> assertThat(receipt.path("attempts").asInt()).isEqualTo(8));
		assertThat(deliver(transferId, "different package").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void portalRejectsAPackageThatDoesNotMatchItsChecksum() {
		var response = portal.put()
			.uri("/api/invoice-packages/{id}", UUID.randomUUID())
			.contentType(Artifact.PACKAGE.mediaType())
			.header("X-Package-SHA256", Sha256.hex("announced".getBytes(StandardCharsets.UTF_8)))
			.body("sent".getBytes(StandardCharsets.UTF_8))
			.retrieve()
			.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Requests the correction fixture once and waits until the retry confirmed the
	 * delivery and the job succeeded. The outcome is captured at that moment, so tests
	 * that use it stay independent of each other.
	 */
	private Delivery lostResponseDelivery() {
		if (delivery == null) {
			var id = id(requestInvoiceRun("correction", 100, null));
			var status = awaitStatus(id, current -> current.path("deliveryStatus").asString().equals("DELIVERED")
					&& current.path("jobState").asString().equals("SUCCEEDED"));
			delivery = new Delivery(id, status, portalReceipts(id));
		}
		return delivery;
	}

	private record Delivery(UUID id, JsonNode status, List<JsonNode> portalReceipts) {
	}

	private ResponseEntity<JsonNode> requestInvoiceRun(String snapshotId, int revision, Instant runAt) {
		var request = new LinkedHashMap<String, Object>();
		request.put("snapshotId", snapshotId);
		request.put("revision", revision);
		if (runAt != null) {
			request.put("runAt", runAt.toString());
		}
		return invoiceRun.post()
			.uri("/api/invoice-runs")
			.contentType(MediaType.APPLICATION_JSON)
			.body(request)
			.retrieve()
			.toEntity(JsonNode.class);
	}

	private JsonNode status(UUID id) {
		return get("/api/invoice-runs/" + id, JsonNode.class).getBody();
	}

	private JsonNode awaitStatus(UUID id, Predicate<JsonNode> condition) {
		return await().atMost(Duration.ofSeconds(60)).until(() -> status(id), condition);
	}

	private <T> ResponseEntity<T> get(String path, Class<T> bodyType) {
		return invoiceRun.get().uri(path).retrieve().toEntity(bodyType);
	}

	private ResponseEntity<JsonNode> deliver(UUID transferId, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		return portal.put()
			.uri("/api/invoice-packages/{id}", transferId)
			.contentType(Artifact.PACKAGE.mediaType())
			.header("X-Package-SHA256", Sha256.hex(bytes))
			.body(bytes)
			.retrieve()
			.toEntity(JsonNode.class);
	}

	private List<JsonNode> portalReceipts(UUID transferId) {
		var all = portal.get().uri("/api/receipts").retrieve().body(JsonNode.class);
		return all.valueStream()
			.filter(receipt -> receipt.path("transferId").asString().equals(transferId.toString()))
			.toList();
	}

	/** Makes a job that is scheduled for later due immediately. */
	private void runNow(UUID id) {
		var scheduled = jobs.getJobById(id);
		scheduled.enqueue();
		jobs.save(scheduled);
	}

	private static UUID id(ResponseEntity<JsonNode> response) {
		return UUID.fromString(response.getBody().path("id").asString());
	}

	private static List<String> strings(JsonNode array) {
		return array.valueStream().map(JsonNode::asString).toList();
	}

	private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		var files = new HashMap<String, byte[]>();
		try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
				files.put(entry.getName(), input.readAllBytes());
			}
		}
		return files;
	}

	/** A client that returns error responses instead of throwing. */
	private static RestClient client(int port) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + port)
			.defaultStatusHandler(status -> true, (request, response) -> {
			})
			.build();
	}

}
