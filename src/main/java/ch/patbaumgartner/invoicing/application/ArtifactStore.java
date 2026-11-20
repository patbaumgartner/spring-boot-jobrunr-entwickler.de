package ch.patbaumgartner.invoicing.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * Keeps the artifacts of each invoice run in its own directory below the data directory.
 */
@Component
public class ArtifactStore {

	private final Path root;

	public ArtifactStore(InvoiceRunProperties properties) {
		this.root = properties.dataDirectory().toAbsolutePath().normalize();
	}

	/**
	 * Writes to a temporary file and moves it into place. Readers therefore see either
	 * the complete artifact or none, even if the process dies while writing, and a
	 * repeated step replaces the file.
	 */
	public void write(UUID runId, Artifact artifact, byte[] content) {
		try {
			var directory = Files.createDirectories(directory(runId));
			var temporary = Files.createTempFile(directory, artifact.filename(), ".tmp");
			try {
				Files.write(temporary, content);
				Files.move(temporary, directory.resolve(artifact.filename()), StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			}
			finally {
				Files.deleteIfExists(temporary);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Artifact was not stored: " + artifact.filename(), e);
		}
	}

	public boolean exists(UUID runId, Artifact artifact) {
		return Files.isRegularFile(directory(runId).resolve(artifact.filename()));
	}

	public byte[] read(UUID runId, Artifact artifact) {
		try {
			return Files.readAllBytes(directory(runId).resolve(artifact.filename()));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Artifact is missing: " + artifact.filename(), e);
		}
	}

	public void remove(UUID runId) {
		try {
			FileSystemUtils.deleteRecursively(directory(runId));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Artifacts were not removed: " + runId, e);
		}
	}

	private Path directory(UUID runId) {
		return root.resolve(runId.toString());
	}

}
