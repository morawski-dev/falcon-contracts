package com.morawski.dev.falcon.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisDecideTest {

	@Test
	void decideSetsTheMatchingClauseAndLeavesSiblingsUntouched() {
		Analysis analysis = new Analysis(1L, "Umowa", "Tresc...", AnalysisStatus.ANALYZED, Instant.now());
		Clause first = new Clause(analysis, "Klauzula pierwsza.", RiskLevel.HIGH, RiskType.AUTO_RENEWAL, "Uzasadnienie.");
		Clause second = new Clause(analysis, "Klauzula druga.", RiskLevel.LOW, RiskType.OTHER, "Uzasadnienie.");
		setId(first, 1L);
		setId(second, 2L);

		Clause returned = analysis.decide(2L, ClauseDecision.ACCEPTED);

		assertThat(returned).isSameAs(second);
		assertThat(second.getUserDecision()).isEqualTo(ClauseDecision.ACCEPTED);
		assertThat(first.getUserDecision()).isEqualTo(ClauseDecision.PENDING);
	}

	@Test
	void decideThrowsForAClauseIdNotInTheAggregate() {
		Analysis analysis = new Analysis(1L, "Umowa", "Tresc...", AnalysisStatus.ANALYZED, Instant.now());
		Clause only = new Clause(analysis, "Jedyna klauzula.", RiskLevel.HIGH, RiskType.PENALTY, "Uzasadnienie.");
		setId(only, 1L);

		assertThatThrownBy(() -> analysis.decide(999L, ClauseDecision.REJECTED))
				.isInstanceOf(NoSuchElementException.class);
	}

	private static void setId(Clause clause, Long id) {
		try {
			var field = Clause.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(clause, id);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

}
