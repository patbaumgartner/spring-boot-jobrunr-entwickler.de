package ch.patbaumgartner.invoicing.domain;

import ch.patbaumgartner.invoicing.domain.InvoiceCalculation.Invoice;
import ch.patbaumgartner.invoicing.domain.InvoiceCalculation.Line;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * Pure invoice calculation rules, independent of Spring and JobRunr. The same snapshot
 * always yields the same result, so repeating a step is harmless.
 */
public final class InvoiceCalculator {

	public static final String RULE_VERSION = "invoice-v1";

	private static final DateTimeFormatter BILLING_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	public InvoiceCalculation calculate(InvoiceSnapshot snapshot) {
		var invoices = new ArrayList<Invoice>();
		for (var customer : snapshot.customers()) {
			var lines = customer.lines().stream().map(InvoiceCalculator::calculate).toList();
			long netCents = lines.stream().mapToLong(Line::netCents).sum();
			long vatCents = lines.stream().mapToLong(Line::vatCents).sum();
			var invoiceNumber = "INV-%s-%s".formatted(BILLING_MONTH.format(snapshot.billingDate()), customer.id());
			invoices.add(new Invoice(invoiceNumber, customer.id(), customer.name(), lines, netCents, vatCents,
					netCents + vatCents));
		}
		return new InvoiceCalculation(snapshot.ruleVersion(), snapshot.billingDate(), snapshot.currency(), invoices);
	}

	private static Line calculate(InvoiceSnapshot.LineItem item) {
		long listCents = item.quantity() * item.unitPriceCents();
		long discountCents = percentOf(listCents, item.discountPercent());
		long netCents = listCents - discountCents;
		long vatCents = percentOf(netCents, item.vatPercent());
		return new Line(item.id(), item.description(), item.quantity(), item.unitPriceCents(), item.discountPercent(),
				discountCents, netCents, item.vatPercent(), vatCents, netCents + vatCents);
	}

	/** Rounds half a cent up; the amounts are never negative. */
	private static long percentOf(long cents, int percent) {
		return (cents * percent + 50) / 100;
	}

}
