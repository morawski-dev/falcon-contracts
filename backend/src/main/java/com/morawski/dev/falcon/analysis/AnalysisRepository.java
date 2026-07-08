package com.morawski.dev.falcon.analysis;

import com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	Optional<Analysis> findByIdAndOwnerId(Long id, Long ownerId);

	@Query("select new com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse(a.id, a.title, a.status, a.createdAt) "
			+ "from Analysis a where a.ownerId = :ownerId order by a.createdAt desc")
	List<AnalysisSummaryResponse> findSummariesByOwnerId(@Param("ownerId") Long ownerId);

}
