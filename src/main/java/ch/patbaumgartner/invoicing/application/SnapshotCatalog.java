package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.domain.InvoiceSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the source system, for example an ERP: it hands out the billing data of a
 * month. The demo reads fixed JSON fixtures, so every run is reproducible.
 */
@Component
public class SnapshotCatalog {

	/** The fixture of the regular monthly run. */
	public static final String STANDARD = "standard";

	/** The same month with a revised quantity; used for a correction run. */
	public static final String CORRECTION = "correction";

	private final JsonMapper json;

	public SnapshotCatalog(JsonMapper json) {
		this.json = json;
	}

	public InvoiceSnapshot load(String snapshotId) {
		var fixture = new ClassPathResource("fixtures/" + snapshotId + ".json");
		try (var input = fixture.getInputStream()) {
			return json.readValue(input, InvoiceSnapshot.class);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Snapshot could not be loaded: " + snapshotId, e);
		}
	}

}
