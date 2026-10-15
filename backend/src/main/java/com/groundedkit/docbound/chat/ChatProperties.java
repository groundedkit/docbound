package com.groundedkit.docbound.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the chat endpoint ({@code app.chat.*}).
 *
 * @param topK                how many chunks to retrieve and give the model as sources
 * @param similarityThreshold chunks scoring below this are ignored; if none are left, the endpoint answers
 *                            "not found" without calling the LLM. The default was chosen with {@code RetrievalEval}.
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(
		@DefaultValue("5") int topK,
		@DefaultValue("0.45") double similarityThreshold) {
}
