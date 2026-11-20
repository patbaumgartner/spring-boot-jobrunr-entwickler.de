package ch.patbaumgartner.invoicing.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/**
 * Fixed input of one monthly invoice run. An instance is always valid: the constructor
 * rejects incomplete or contradictory data.
 */
public record InvoiceSnapshot(String id, String company, LocalDate billingDate, String ruleVersion, String currency,
		List<Customer> customers) {

	/**
	 * The same customers and line items for another billing date. For its own billing
	 * date the snapshot stays the same input, so a run that already exists for it is
	 * reused instead of being a conflict.
	 */
	public InvoiceSnapshot forBillingDate(LocalDate billingDate) {
		if (billingDate.equals(billingDate())) {
			return this;
		}
		return new InvoiceSnapshot("monthly-" + billingDate, company(), billingDate, ruleVersion(), currency(),
				customers());
	}

	public InvoiceSnapshot {
		require(company != null && !company.isBlank(), "Company is missing");
		require(billingDate != null, "Billing date is missing");
		require(currency != null && !currency.isBlank(), "Currency is missing");
		require(InvoiceCalculator.RULE_VERSION.equals(ruleVersion), "Unknown rule version");
		customers = requireElements(customers, "Customers are missing");

		var customerIds = new HashSet<String>();
		for (var customer : customers) {
			require(customerIds.add(customer.id()), "Duplicate customer ID");
			require(customer.lines() != null && !customer.lines().isEmpty(), "Invoice lines are missing");
			var lineIds = new HashSet<String>();
			for (var line : customer.lines()) {
				require(lineIds.add(line.id()), "Duplicate invoice line ID");
				require(line.quantity() > 0, "Quantity must be positive");
				require(line.unitPriceCents() >= 0, "Unit price must not be negative");
				require(line.discountPercent() >= 0 && line.discountPercent() <= 100,
						"Discount must be between 0 and 100");
				require(line.vatPercent() >= 0 && line.vatPercent() <= 100, "VAT must be between 0 and 100");
			}
		}
	}

	public record Customer(String id, String name, List<LineItem> lines) {
		public Customer {
			require(name != null && !name.isBlank(), "Customer name is missing");
			lines = requireElements(lines, "Invoice lines are missing");
		}
	}

	public record LineItem(String id, String description, int quantity, long unitPriceCents, int discountPercent,
			int vatPercent) {
		public LineItem {
			require(description != null && !description.isBlank(), "Line description is missing");
		}
	}

	private static <T> List<T> requireElements(List<T> elements, String message) {
		require(elements != null && !elements.isEmpty(), message);
		return List.copyOf(elements);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}
}
