package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.Sha256;
import ch.patbaumgartner.invoicing.domain.InvoiceCalculation;
import ch.patbaumgartner.invoicing.domain.InvoiceSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the versioned invoice package: plan, analysis, CSV export, report and a manifest
 * with their checksums, bundled into one ZIP.
 *
 * <p>
 * The same invoice run always yields the same bytes. A package that is rebuilt after a
 * crash therefore keeps its checksum, and the portal recognises it as the same delivery.
 */
@Component
public class InvoicePackageBuilder {

	/** ZIP entries carry no build time; this is the earliest date the format allows. */
	private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0);

	private final ArtifactStore files;

	private final InvoiceReportRenderer renderer;

	private final JsonMapper json;

	public InvoicePackageBuilder(ArtifactStore files, InvoiceReportRenderer renderer, JsonMapper json) {
		this.files = files;
		this.renderer = renderer;
		this.json = json;
	}

	/**
	 * Expects the plan and analysis of the invoice run in the artifact store.
	 * @return the SHA-256 checksum of the package
	 */
	public String create(InvoiceRun run) {
		var id = run.id();
		var plan = json.readValue(files.read(id, Artifact.PLAN), InvoiceCalculation.class);
		var analysis = json.readValue(files.read(id, Artifact.ANALYSIS), InvoiceSummary.class);

		var content = new LinkedHashMap<Artifact, byte[]>();
		content.put(Artifact.PLAN, files.read(id, Artifact.PLAN));
		content.put(Artifact.ANALYSIS, files.read(id, Artifact.ANALYSIS));
		content.put(Artifact.CSV, InvoiceCsvExporter.export(plan));
		content.put(Artifact.REPORT, renderer.render(run, plan, analysis));
		content.put(Artifact.MANIFEST, json.writeValueAsBytes(manifest(run, plan, content)));

		for (var derived : List.of(Artifact.CSV, Artifact.REPORT, Artifact.MANIFEST)) {
			files.write(id, derived, content.get(derived));
		}
		byte[] zip = zip(content);
		files.write(id, Artifact.PACKAGE, zip);
		return Sha256.hex(zip);
	}

	private Manifest manifest(InvoiceRun run, InvoiceCalculation plan, Map<Artifact, byte[]> content) {
		var checksums = new LinkedHashMap<String, String>();
		content.forEach((artifact, bytes) -> checksums.put(artifact.filename(), Sha256.hex(bytes)));
		return new Manifest(1, run.id(), run.company(), run.billingDate(), run.revision(), run.inputHash(),
				plan.ruleVersion(), checksums);
	}

	private byte[] zip(Map<Artifact, byte[]> content) {
		var bytes = new ByteArrayOutputStream();
		try (var zip = new ZipOutputStream(bytes)) {
			for (var file : content.entrySet()) {
				var entry = new ZipEntry(file.getKey().filename());
				entry.setTimeLocal(ZIP_EPOCH);
				zip.putNextEntry(entry);
				zip.write(file.getValue());
				zip.closeEntry();
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Package was not created", e);
		}
		return bytes.toByteArray();
	}

	/**
	 * Describes the package for the receiver.
	 *
	 * @param transferId the stable id under which the package is delivered
	 * @param files SHA-256 checksum per file name
	 */
	record Manifest(int schemaVersion, UUID transferId, String company, LocalDate billingDate, int revision,
			String inputSha256, String ruleVersion, Map<String, String> files) {
	}

}
