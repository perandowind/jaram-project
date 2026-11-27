package com.jaram.be.study;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StudyApplicationRepository extends JpaRepository<StudyApplication, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StudyApplication> findForUpdateById(String id);

    Optional<StudyApplication> findByStudyIdAndApplicantId(String studyId, String applicantId);
    List<StudyApplication> findByApplicantIdOrderByCreatedAtDesc(String applicantId);
    List<StudyApplication> findByStatusOrderByCreatedAtDesc(ApplicationStatus status);
    int countByStudyIdAndStatus(String studyId, ApplicationStatus status);
    List<StudyApplication> findByStudyIdAndStatus(String studyId, ApplicationStatus status);
    List<StudyApplication> findByStudyId(String studyId);
    List<StudyApplication> findByStudyIdAndStatusIn(String studyId, Collection<ApplicationStatus> statuses);
}
