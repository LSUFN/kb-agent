package com.example.kbagent.report.repository;

import com.example.kbagent.report.domain.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Page<Report> findByUserId(long userId, Pageable pageable);
}
