package com.groundedkit.docbound.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Readable titles for documents, so answers can say "the Policy Workplace Concerns document" instead of a filename. */
final class DocumentTitles {

	private DocumentTitles() {
	}

	/**
	 * {@code policy_workplace_concerns.pdf} → {@code Policy Workplace Concerns}. Words that are already mixed or upper
	 * case (e.g. {@code API}, {@code iOS}) are kept as they are.
	 */
	static String fromFilename(String filename) {
		String name = filename == null ? "" : filename.strip();
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			name = name.substring(0, dot);
		}
		List<String> words = new ArrayList<>();
		for (String word : name.split("[\\s_\\-.]+")) {
			if (word.isEmpty()) {
				continue;
			}
			boolean lowerCase = word.equals(word.toLowerCase(Locale.ROOT));
			words.add(lowerCase ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word);
		}
		return words.isEmpty() ? (filename == null ? "Untitled" : filename) : String.join(" ", words);
	}

}
