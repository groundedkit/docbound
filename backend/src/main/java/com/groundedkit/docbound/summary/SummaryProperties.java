package com.groundedkit.docbound.summary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Document summaries ({@code app.summaries.*}).
 *
 * @param enabled      write a summary of each document after upload, so questions about a whole document ("what is
 *                     this about?") see all of it rather than the five most similar passages
 * @param sectionChars documents longer than this are summarized section by section first, so each call fits the
 *                     model's context window ({@code spring.ai.ollama.chat.num-ctx}); about 4 characters per token
 */
@ConfigurationProperties("app.summaries")
public record SummaryProperties(@DefaultValue("true") boolean enabled, @DefaultValue("12000") int sectionChars) {
}
