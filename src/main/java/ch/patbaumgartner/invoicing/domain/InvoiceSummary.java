package ch.patbaumgartner.invoicing.domain;

import ch.patbaumgartner.invoicing.domain.InvoiceCalculation.Invoice;
import ch.patbaumgartner.invoicing.domain.InvoiceCalculation.Line;
import java.util.List;
import java.util.TreeMap;

/**
 * The analysis of one run: totals for controlling, without the individual lines.
 *
 * @param customers one total per invoice, in the order of the snapshot
 * @param vatRates net and VAT amount per VAT rate, ascending
 */
public record InvoiceSummary(String currency, int invoiceCount, long totalDiscountCents, long totalNetCents,
		long totalVatCents, long totalGrossCents, List<CustomerTotal> customers, List<VatRateTotal> vatRates) {

	public record CustomerTotal(String customerId, String customerName, String invoiceNumber, long netCents,
			long vatCents, long grossCents) {
	}

	public record VatRateTotal(int vatPercent, long netCents, long vatCents) {
	}

	public static InvoiceSummary of(InvoiceCalculation calculation) {
		var invoices = calculation.invoices();
		var lines = invoices.stream().flatMap(invoice -> invoice.lines().stream()).toList();

		var customers = invoices.stream()
			.map(invoice -> new CustomerTotal(invoice.customerId(), invoice.customerName(), invoice.invoiceNumber(),
					invoice.netCents(), invoice.vatCents(), invoice.grossCents()))
			.toList();
		var vatRates = new TreeMap<Integer, VatRateTotal>();
		for (var line : lines) {
			vatRates.merge(line.vatPercent(), new VatRateTotal(line.vatPercent(), line.netCents(), line.vatCents()),
					(total, added) -> new VatRateTotal(total.vatPercent(), total.netCents() + added.netCents(),
							total.vatCents() + added.vatCents()));
		}
		return new InvoiceSummary(calculation.currency(), invoices.size(),
				lines.stream().mapToLong(Line::discountCents).sum(),
				invoices.stream().mapToLong(Invoice::netCents).sum(),
				invoices.stream().mapToLong(Invoice::vatCents).sum(),
				invoices.stream().mapToLong(Invoice::grossCents).sum(), customers, List.copyOf(vatRates.values()));
	}

}
