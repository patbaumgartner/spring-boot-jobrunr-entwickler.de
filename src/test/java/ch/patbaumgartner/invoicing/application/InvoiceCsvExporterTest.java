package ch.patbaumgartner.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.patbaumgartner.invoicing.domain.InvoiceCalculator;
import ch.patbaumgartner.invoicing.domain.InvoiceSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceCsvExporterTest {

	@Test
	void writesOneRowPerLineAndQuotesFieldsThatContainSeparators() {
		var line = new InvoiceSnapshot.LineItem("line-1", "Hosting, \"managed\"", 2, 12550, 10, 8);
		var customer = new InvoiceSnapshot.Customer("C-1", "Example GmbH", List.of(line));
		var snapshot = new InvoiceSnapshot("test", "Alpine Software GmbH", LocalDate.of(2026, 11, 30),
				InvoiceCalculator.RULE_VERSION, "EUR", List.of(customer));

		var csv = new String(InvoiceCsvExporter.export(new InvoiceCalculator().calculate(snapshot)),
				StandardCharsets.UTF_8);

		assertThat(csv.split("\r\n")).containsExactly(
				"invoiceNumber,customerId,customerName,lineId,description,quantity,unitPrice,discountPercent,net,"
						+ "vatPercent,vat,gross,currency",
				"INV-2026-11-C-1,C-1,Example GmbH,line-1,\"Hosting, \"\"managed\"\"\",2,125.50,10,225.90,8,18.07,243.97,EUR");
	}

}
