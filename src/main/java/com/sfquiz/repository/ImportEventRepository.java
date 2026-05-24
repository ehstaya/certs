package com.sfquiz.repository;

import com.sfquiz.entity.ImportEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportEventRepository extends JpaRepository<ImportEvent, Long> {
    List<ImportEvent> findTop20ByOrderByOccurredAtDesc();
}
