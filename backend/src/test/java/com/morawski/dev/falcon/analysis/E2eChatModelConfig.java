package com.morawski.dev.falcon.analysis;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * Deterministic {@link ChatModel} for the {@code e2e} Spring profile, letting a locally-run
 * app (not just the test suite) serve fixed classifications with no OpenRouter network call.
 * Reached at runtime via
 * {@code -Dspring-boot.run.profiles=e2e -Dspring-boot.run.additional-classpath-elements=target/test-classes}
 * and never packaged — {@code repackage} does not add test classes to the jar.
 *
 * <p><b>Not</b> {@code -Dspring-boot.run.useTestClasspath=true}: that flag is documented for
 * exactly this purpose, but empirically does not add {@code target/test-classes} to the
 * runtime classpath on spring-boot-maven-plugin 4.0.7 (verified by inspecting the forked
 * process's classpath argfile). {@code additional-classpath-elements} — kebab-case; the
 * camelCase property key silently no-ops — does.
 *
 * <p>Deliberately a plain {@link Configuration}, not {@code @TestConfiguration}: the latter is
 * annotated {@code @TestComponent}, which the default component filter excludes from scanning.
 * A stub written as {@code @TestConfiguration} would be silently ignored under
 * {@code useTestClasspath} — the app boots, the profile is active, and the real OpenRouter
 * model still answers.
 *
 * <p>A real lambda, never a Mockito mock: an unstubbed mock NPEs on
 * {@code chatModel.getOptions().mutate()}, per the same pattern proven in
 * {@link AnalysisFlowTest.MockChatModelConfig}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
class E2eChatModelConfig {

	@Bean
	@Primary
	ChatModel e2eChatModel() {
		ChatResponse response = new ChatResponse(
				List.of(new Generation(new AssistantMessage(ClauseAnalysisFixtures.MULTI_CLAUSE_JSON))));
		return prompt -> response;
	}

}
