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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Risk #1 (test-plan.md §2): ownership must not leak existence. AnalysisFlowTest already proves
 * a cross-user GET returns 404; this test proves that 404 is byte-identical to a truly missing
 * id, so a wrong-owner id can't be distinguished from one that was never created. List-isolation
 * (an owner-scoped list never leaking another user's rows) is deferred to S-03 — there is no
 * list endpoint yet.
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

	@AfterEach
	void cleanUp() {
		analysisRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User persistUser(String email) {
		return userRepository.save(new User(email, "hashed-password", Instant.now()));
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
		assertThat(crossUserResult.getResponse().getContentAsString())
				.as("no distinguishing signal in the response body either")
				.isEqualTo(missingIdResult.getResponse().getContentAsString());
	}

}
