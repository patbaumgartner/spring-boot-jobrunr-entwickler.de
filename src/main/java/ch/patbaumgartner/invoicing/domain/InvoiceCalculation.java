package ch.patbaumgartner.invoicing.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The calculated invoices of one run: the invoice plan that is handed to the receiver.
 * All amounts are integer cents.
 */
public record InvoiceCalculation(String ruleVersion, LocalDate billingDate, String currency, List<Invoice> invoices) {

	public InvoiceCalculation {
		invoices = List.copyOf(invoices);
	}

	/**
	 * @param invoiceNumber derived from billing month and customer, so a repeated
	 * calculation yields the same number
	 */
	public record Invoice(String invoiceNumber, String customerId, String customerName, List<Line> lines, long netCents,
			long vatCents, long grossCents) {

		public Invoice {
			lines = List.copyOf(lines);
		}

	}

	/**
	 * @param discountCents list price minus net
	 * @param netCents quantity times unit price, minus discount
	 * @param vatCents VAT on the net amount, rounded per line
	 */
	public record Line(String lineId, String description, int quantity, long unitPriceCents, int discountPercent,
			long discountCents, long netCents, int vatPercent, long vatCents, long grossCents) {
	}

}
