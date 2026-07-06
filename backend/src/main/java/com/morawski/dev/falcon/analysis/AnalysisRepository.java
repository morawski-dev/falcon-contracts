package com.morawski.dev.falcon.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	Optional<Analysis> findByIdAndOwnerId(Long id, Long ownerId);

}
