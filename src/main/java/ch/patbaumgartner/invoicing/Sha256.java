package ch.patbaumgartner.invoicing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checksums that tie an invoice run to its input and a transfer to its package content.
 */
public final class Sha256 {

	private Sha256() {
	}

	public static String hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
		}
	}

}
