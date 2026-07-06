package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.TestcontainersConfiguration;
import com.morawski.dev.falcon.user.User;
import com.morawski.dev.falcon.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisRepositoryTest {

	@Autowired
	private AnalysisRepository analysisRepository;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void cleanUp() {
		analysisRepository.deleteAll();
		userRepository.deleteAll();
	}

	private Long persistUser(String email) {
		return userRepository.save(new User(email, "hashed-password", Instant.now())).getId();
	}

	@Test
	void cascadeSavesClausesAndNegotiationPoints() {
		Long ownerId = persistUser("owner@example.com");

		Analysis analysis = new Analysis(ownerId, "Umowa najmu", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
		new Clause(analysis, "Automatyczne przedluzenie na 12 miesiecy.", RiskLevel.HIGH, RiskType.AUTO_RENEWAL,
				"Dlugi okres wypowiedzenia utrudnia wyjscie z umowy.");
		new NegotiationPoint(analysis, "Skrocic okres wypowiedzenia do 30 dni.", RiskLevel.HIGH);

		Analysis saved = analysisRepository.save(analysis);

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getClauses()).hasSize(1);
		assertThat(saved.getClauses().get(0).getId()).isNotNull();
		assertThat(saved.getClauses().get(0).getUserDecision()).isEqualTo(ClauseDecision.PENDING);
		assertThat(saved.getNegotiationPoints()).hasSize(1);
		assertThat(saved.getNegotiationPoints().get(0).getId()).isNotNull();
	}

	@Test
	void findByIdAndOwnerIdScopesToOwner() {
		Long ownerId = persistUser("scoped-owner@example.com");
		Long otherOwnerId = persistUser("other-owner@example.com");

		Analysis analysis = new Analysis(ownerId, "Umowa B2B", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
		Analysis saved = analysisRepository.save(analysis);

		Optional<Analysis> foundByOwner = analysisRepository.findByIdAndOwnerId(saved.getId(), ownerId);
		Optional<Analysis> foundByOtherOwner = analysisRepository.findByIdAndOwnerId(saved.getId(), otherOwnerId);

		assertThat(foundByOwner).isPresent();
		assertThat(foundByOtherOwner).isEmpty();
	}

}
