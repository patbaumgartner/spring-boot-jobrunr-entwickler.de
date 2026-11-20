package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.application.InvoiceRun.DeliveryStatus;
import ch.patbaumgartner.invoicing.application.InvoiceRun.PackageStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores invoice runs. Every status update is a single idempotent statement, because a
 * durable step may run again after a crash.
 */
@Repository
public class InvoiceRunRepository {

	private final JdbcClient jdbc;

	public InvoiceRunRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Company, billing date and revision identify a run. A second run with the same key
	 * is not stored.
	 */
	public void insertIfAbsent(InvoiceRun run) {
		jdbc.sql("""
				INSERT INTO invoice_run (id, company, billing_date, revision, snapshot, input_hash, run_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (company, billing_date, revision) DO NOTHING
				""")
			.params(run.id(), run.company(), run.billingDate(), run.revision(), run.snapshot(), run.inputHash(),
					timestamp(run.runAt()))
			.update();
	}

	public InvoiceRun get(UUID id) {
		return jdbc.sql("SELECT * FROM invoice_run WHERE id = ?")
			.param(id)
			.query(this::toRun)
			.optional()
			.orElseThrow(() -> new InvoiceRunNotFoundException(id));
	}

	public InvoiceRun get(String company, LocalDate billingDate, int revision) {
		return jdbc.sql("SELECT * FROM invoice_run WHERE company = ? AND billing_date = ? AND revision = ?")
			.params(company, billingDate, revision)
			.query(this::toRun)
			.single();
	}

	public List<InvoiceRun> all() {
		return jdbc.sql("SELECT * FROM invoice_run ORDER BY created_at DESC").query(this::toRun).list();
	}

	public void enqueued(UUID id, UUID jobId) {
		jdbc.sql("UPDATE invoice_run SET job_id = ?, last_error = NULL WHERE id = ?").params(jobId, id).update();
	}

	public void enqueueFailed(UUID id, String error) {
		jdbc.sql("UPDATE invoice_run SET last_error = ? WHERE id = ?").params(error, id).update();
	}

	public void packageReady(UUID id) {
		jdbc.sql("UPDATE invoice_run SET package_status = 'READY' WHERE id = ?").param(id).update();
	}

	public void delivered(UUID id, String receipt) {
		jdbc.sql("""
				UPDATE invoice_run
				SET delivery_status = 'DELIVERED', receipt = ?, last_error = NULL,
				    delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP)
				WHERE id = ?
				""").params(receipt, id).update();
	}

	/**
	 * A confirmed delivery is never downgraded by a later failure.
	 */
	public void deliveryFailed(UUID id, DeliveryStatus status, String error) {
		jdbc.sql("""
				UPDATE invoice_run SET delivery_status = ?, last_error = ?
				WHERE id = ? AND delivery_status <> 'DELIVERED'
				""").params(status.name(), error, id).update();
	}

	public List<UUID> deliveredBefore(Instant cutoff) {
		return jdbc.sql("""
				SELECT id FROM invoice_run
				WHERE delivery_status = 'DELIVERED' AND package_status = 'READY' AND delivered_at < ?
				""").param(timestamp(cutoff)).query(UUID.class).list();
	}

	public void expired(UUID id) {
		jdbc.sql("UPDATE invoice_run SET package_status = 'EXPIRED' WHERE id = ?").param(id).update();
	}

	/**
	 * Demo evidence: every execution of a step body is recorded, so the status shows
	 * which steps a retry repeated and which ones it skipped.
	 */
	public void recordStep(UUID id, String step) {
		jdbc.sql("INSERT INTO step_execution (run_id, step) VALUES (?, ?)").params(id, step).update();
	}

	public List<String> executedSteps(UUID id) {
		return jdbc.sql("SELECT step FROM step_execution WHERE run_id = ? ORDER BY id")
			.param(id)
			.query(String.class)
			.list();
	}

	private InvoiceRun toRun(ResultSet row, int rowNumber) throws SQLException {
		var runAt = row.getTimestamp("run_at");
		return new InvoiceRun(row.getObject("id", UUID.class), row.getString("company"),
				row.getObject("billing_date", LocalDate.class), row.getInt("revision"), row.getString("snapshot"),
				row.getString("input_hash"), runAt == null ? null : runAt.toInstant(),
				row.getObject("job_id", UUID.class), PackageStatus.valueOf(row.getString("package_status")),
				DeliveryStatus.valueOf(row.getString("delivery_status")), row.getString("receipt"),
				row.getString("last_error"));
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	public static class InvoiceRunNotFoundException extends RuntimeException {

		InvoiceRunNotFoundException(UUID id) {
			super("Unknown invoice run: " + id);
		}

	}

}
