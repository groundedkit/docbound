package com.groundedkit.docbound.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the chat endpoint ({@code app.chat.*}).
 *
 * @param topK                how many chunks to retrieve and give the model as sources
 * @param similarityThreshold chunks scoring below this are not used as sources. The default was chosen with
 *                            {@code RetrievalEval}.
 * @param historyTurns        how many earlier question-and-answer exchanges are used to understand follow-up questions;
 *                            older ones are ignored. 0 turns follow-ups off.
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(
		@DefaultValue("5") int topK,
		@DefaultValue("0.45") double similarityThreshold,
		@DefaultValue("3") int historyTurns) {
}
