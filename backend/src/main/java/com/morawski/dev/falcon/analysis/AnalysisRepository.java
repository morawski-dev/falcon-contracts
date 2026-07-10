package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	Optional<Analysis> findByIdAndOwnerId(Long id, Long ownerId);

	@Query("select new com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse(a.id, a.title, a.status, a.createdAt) "
			+ "from Analysis a where a.ownerId = :ownerId order by a.createdAt desc")
	List<AnalysisSummaryResponse> findSummariesByOwnerId(@Param("ownerId") Long ownerId);

	// A bulk JPQL delete never enters the persistence context, so Analysis's
	// cascade = ALL / orphanRemoval never fires for this operation. Child cleanup instead
	// depends entirely on the DB-level ON DELETE CASCADE on clauses.analysis_id and
	// negotiation_points.analysis_id (changeset 002). Do not "simplify" this to
	// findByIdAndOwnerId(...).map(this::delete) — that reintroduces Hibernate's per-collection
	// entity cascade: loads the full aggregate, issues N+1 deletes, and depends on Hibernate
	// emitting them in a safe order instead of one atomic statement.
	@Modifying
	@Query("delete from Analysis a where a.id = :id and a.ownerId = :ownerId")
	int deleteOwned(@Param("id") Long id, @Param("ownerId") Long ownerId);

}
