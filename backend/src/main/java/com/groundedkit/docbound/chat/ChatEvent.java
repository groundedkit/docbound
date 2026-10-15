package com.groundedkit.docbound.chat;

import java.util.List;
import java.util.UUID;

/**
 * Events streamed by {@code POST /api/chat}, in order: one {@link Sources}, any number of {@link Token}s,
 * then either {@link Done} or {@link Error}. Each is sent as a server-sent event named by {@link #name()}.
 */
public sealed interface ChatEvent {

	String name();

	/** The chunks given to the model, numbered as the model cites them: {@code [1]} is {@code index == 1}. */
	record Sources(List<Citation> sources) implements ChatEvent {

		@Override
		public String name() {
			return "sources";
		}

	}

	/** A piece of the answer text. */
	record Token(String text) implements ChatEvent {

		@Override
		public String name() {
			return "token";
		}

	}

	/**
	 * The answer is complete.
	 *
	 * @param citedSources source indexes the answer actually cites, in order of first use
	 * @param notFound true when the answer says the documents don't contain what was asked
	 * @param promptTokens tokens sent to the model, if the provider reports it
	 * @param completionTokens tokens generated, if the provider reports it
	 */
	record Done(List<Integer> citedSources, boolean notFound, Integer promptTokens, Integer completionTokens,
			long durationMs) implements ChatEvent {

		@Override
		public String name() {
			return "done";
		}

	}

	/** Generation failed part-way. Any tokens already sent should be treated as incomplete. */
	record Error(String message) implements ChatEvent {

		@Override
		public String name() {
			return "error";
		}

	}

	/** A source passage. {@code title} is a readable name derived from the filename. */
	record Citation(int index, UUID documentId, String filename, String title, int chunkIndex, double score,
			String text) {
	}

}
