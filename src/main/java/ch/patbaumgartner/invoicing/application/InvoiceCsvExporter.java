package ch.patbaumgartner.invoicing.application;

import ch.patbaumgartner.invoicing.domain.InvoiceCalculation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Exports the invoice lines as CSV (RFC 4180) for systems that do not read JSON, for
 * example an accounting import. One row per invoice line, amounts with two decimals.
 */
final class InvoiceCsvExporter {

	private static final List<String> HEADER = List.of("invoiceNumber", "customerId", "customerName", "lineId",
			"description", "quantity", "unitPrice", "discountPercent", "net", "vatPercent", "vat", "gross", "currency");

	private InvoiceCsvExporter() {
	}

	static byte[] export(InvoiceCalculation calculation) {
		var csv = new StringBuilder(row(HEADER));
		for (var invoice : calculation.invoices()) {
			for (var line : invoice.lines()) {
				csv.append(row(List.of(invoice.invoiceNumber(), invoice.customerId(), invoice.customerName(),
						line.lineId(), line.description(), String.valueOf(line.quantity()),
						amount(line.unitPriceCents()), String.valueOf(line.discountPercent()), amount(line.netCents()),
						String.valueOf(line.vatPercent()), amount(line.vatCents()), amount(line.grossCents()),
						calculation.currency())));
			}
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String row(List<String> fields) {
		return fields.stream().map(InvoiceCsvExporter::escape).collect(Collectors.joining(",", "", "\r\n"));
	}

	private static String escape(String field) {
		boolean quote = field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r");
		return quote ? '"' + field.replace("\"", "\"\"") + '"' : field;
	}

	private static String amount(long cents) {
		return BigDecimal.valueOf(cents, 2).toPlainString();
	}

}
