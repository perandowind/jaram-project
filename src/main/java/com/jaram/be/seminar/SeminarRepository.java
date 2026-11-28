package com.jaram.be.seminar;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SeminarRepository extends JpaRepository<Seminar, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seminar s where s.id = :id")
    Optional<Seminar> findForUpdate(@Param("id") String id);

    // Lock the seminar itself so approval, rejection and deletion serialize with dispatch.
    @Query(value = """
            select * from seminar
            where reminder_due_at <= :now or start_notification_due_at <= :now
            order by least(reminder_due_at, start_notification_due_at), id
            limit 1 for update skip locked
            """, nativeQuery = true)
    Optional<Seminar> lockNextDueNotification(@Param("now") Instant now);

    List<Seminar> findAllByOrderByStartsAtDesc();

    List<Seminar> findByApprovalStatusOrderByStartsAtDesc(ApprovalStatus approvalStatus);

    // 회원 삭제 시 개설자 역참조를 끊기 위해. 세미나 자체는 남긴다.
    List<Seminar> findByCreatedById(String createdById);
}
