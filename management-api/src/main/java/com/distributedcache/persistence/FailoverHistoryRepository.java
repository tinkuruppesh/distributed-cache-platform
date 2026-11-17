package com.distributedcache.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FailoverHistoryRepository extends JpaRepository<FailoverHistoryEntity, String> {
    List<FailoverHistoryEntity> findTop100ByOrderByDetectedAtDesc();
}
