package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.domain.InvoiceCalculation;
import ch.patbaumgartner.invoicing.domain.InvoiceSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the self-contained HTML invoice report. The calculated data is embedded as
 * JSON, so the file works offline and shows exactly what the package contains.
 */
@Component
public class InvoiceReportRenderer {

	private static final String DATA_PLACEHOLDER = "__INVOICE_REPORT_DATA__";

	private final JsonMapper json;

	public InvoiceReportRenderer(JsonMapper json) {
		this.json = json;
	}

	public byte[] render(InvoiceRun run, InvoiceCalculation plan, InvoiceSummary analysis) {
		var data = json
			.writeValueAsString(new InvoiceReportData(run.id(), run.company(), run.revision(), plan, analysis))
			// The JSON is embedded in a script element and must not be able to close it.
			.replace("<", "\\u003c")
			.replace(">", "\\u003e")
			.replace("&", "\\u0026");
		return template().replace(DATA_PLACEHOLDER, data).getBytes(StandardCharsets.UTF_8);
	}

	private String template() {
		try {
			return new ClassPathResource("invoice-report/report.html").getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Invoice report template is missing", e);
		}
	}

	record InvoiceReportData(UUID runId, String company, int revision, InvoiceCalculation plan,
			InvoiceSummary analysis) {
	}

}
