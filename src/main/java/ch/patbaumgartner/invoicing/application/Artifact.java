package ch.patbaumgartner.invoicing.application;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.MediaType;

/**
 * The files of one invoice run.
 */
public enum Artifact {

	/** The validated input, written by the first step. */
	SNAPSHOT("snapshot.json", MediaType.APPLICATION_JSON),

	/** The invoice plan: every calculated invoice with its lines. */
	PLAN("plan.json", MediaType.APPLICATION_JSON),

	/** Totals per run, customer and VAT rate. */
	ANALYSIS("analysis.json", MediaType.APPLICATION_JSON),

	/** The invoice lines as CSV, for example for an accounting import. */
	CSV("invoices.csv", new MediaType("text", "csv", StandardCharsets.UTF_8)),

	/** Self-contained HTML invoice report. */
	REPORT("report.html", new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)),

	/** Ids, versions and checksums of the package content. */
	MANIFEST("manifest.json", MediaType.APPLICATION_JSON),

	/** The immutable ZIP that is delivered to the portal. */
	PACKAGE("package.zip", MediaType.parseMediaType("application/zip"));

	private final String filename;

	private final MediaType mediaType;

	Artifact(String filename, MediaType mediaType) {
		this.filename = filename;
		this.mediaType = mediaType;
	}

	public String filename() {
		return filename;
	}

	public MediaType mediaType() {
		return mediaType;
	}

	/** The name used in URLs, for example {@code report}. */
	public String urlName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Whether a browser should save the artifact instead of displaying it. */
	public boolean isDownload() {
		return this == PACKAGE || this == CSV;
	}

	public static Optional<Artifact> ofUrlName(String urlName) {
		return Arrays.stream(values()).filter(artifact -> artifact.urlName().equals(urlName)).findFirst();
	}

}
