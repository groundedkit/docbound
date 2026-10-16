package com.groundedkit.docbound.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the chat endpoint ({@code app.chat.*}).
 *
 * @param topK                how many chunks to retrieve and give the model as sources
 * @param similarityThreshold chunks scoring below this are not used as sources. The default was chosen with
 *                            {@code RetrievalEval}.
 * @param historyChars        how much of the conversation (characters of earlier questions and answers) is given to the
 *                            model for follow-up questions. The newest exchanges that fit are used, so a typical
 *                            conversation is kept whole; the limit only keeps very long ones within the model's context
 *                            window. 0 turns follow-ups off.
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(
		@DefaultValue("5") int topK,
		@DefaultValue("0.45") double similarityThreshold,
		@DefaultValue("12000") int historyChars) {
}
