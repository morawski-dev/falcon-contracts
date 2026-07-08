package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.user.AppUserDetails;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Risk #1 (test-plan.md §2): ownership must not leak existence. AnalysisFlowTest already proves
 * a cross-user GET returns 404; this test proves that 404 is byte-identical to a truly missing
 * id, so a wrong-owner id can't be distinguished from one that was never created. It also proves
 * the owner-scoped list (GET /api/analyses, S-03) never leaks another user's rows.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisIsolationTest {

	private static final long MISSING_ANALYSIS_ID = 999_999_999L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AnalysisRepository analysisRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@AfterEach
	void cleanUp() {
		analysisRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User persistUser(String email) {
		return userRepository.save(new User(email, passwordEncoder.encode("password"), Instant.now()));
	}

	@Test
	void crossUserGetAndMissingIdGetAreIndistinguishable() throws Exception {
		User ownerA = persistUser("isolation-owner-a@example.com");
		User ownerB = persistUser("isolation-owner-b@example.com");

		Analysis analysis = new Analysis(ownerA.getId(), "Umowa A", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
		new Clause(analysis, "Automatyczne przedluzenie na 12 miesiecy.", RiskLevel.HIGH, RiskType.AUTO_RENEWAL,
				"Dlugi okres wypowiedzenia utrudnia wyjscie z umowy.");
		Analysis saved = analysisRepository.save(analysis);

		MvcResult crossUserResult = mockMvc.perform(get("/api/analyses/" + saved.getId()).with(user(new AppUserDetails(ownerB))))
				.andReturn();
		MvcResult missingIdResult = mockMvc.perform(get("/api/analyses/" + MISSING_ANALYSIS_ID).with(user(new AppUserDetails(ownerB))))
				.andReturn();

		assertThat(crossUserResult.getResponse().getStatus())
				.as("wrong-owner id must be indistinguishable from a missing id")
				.isEqualTo(404)
				.isEqualTo(missingIdResult.getResponse().getStatus());
		// Both bodies are empty today (ResponseStatusException never reaches BasicErrorController
		// under MockMvc) — this assertion is defense-in-depth against a future error body that
		// might otherwise distinguish "exists but not yours" from "doesn't exist".
		assertThat(crossUserResult.getResponse().getContentAsString())
				.as("no distinguishing signal in the response body either")
				.isEqualTo(missingIdResult.getResponse().getContentAsString());
	}

	@Test
	void ownerScopedListNeverLeaksAnotherUsersAnalyses() throws Exception {
		User ownerA = persistUser("list-owner-a@example.com");
		User ownerB = persistUser("list-owner-b@example.com");

		Analysis analysisA = new Analysis(ownerA.getId(), "Umowa A", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
		new Clause(analysisA, "Automatyczne przedluzenie na 12 miesiecy.", RiskLevel.HIGH, RiskType.AUTO_RENEWAL,
				"Dlugi okres wypowiedzenia utrudnia wyjscie z umowy.");
		analysisRepository.save(analysisA);

		Analysis analysisB = new Analysis(ownerB.getId(), "Umowa B", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
		new Clause(analysisB, "Kara umowna 50000 zl.", RiskLevel.HIGH, RiskType.PENALTY,
				"Wysoka kara moze byc nieproporcjonalna.");
		analysisRepository.save(analysisB);

		MvcResult listResult = mockMvc.perform(get("/api/analyses").with(user(new AppUserDetails(ownerB)))).andReturn();

		assertThat(listResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(listResult.getResponse().getContentAsString())
				.as("owner B's list must contain only owner B's analyses")
				.contains("Umowa B")
				.doesNotContain("Umowa A");
	}

}
