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
	 * @param promptTokens tokens sent to the model, if the provider reports it
	 * @param completionTokens tokens generated, if the provider reports it
	 */
	record Done(List<Integer> citedSources, Integer promptTokens, Integer completionTokens, long durationMs)
			implements ChatEvent {

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

	record Citation(int index, UUID documentId, String filename, int chunkIndex, double score, String text) {
	}

}
