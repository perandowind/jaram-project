package com.jaram.be.member;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {
    Optional<Member> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByStudentId(String studentId);
    List<Member> findByApproval(MemberApproval approval);
    List<Member> findByApprovalAndStatus(MemberApproval approval, MemberStatus status);

    @Query("""
            select distinct m.id from Member m join m.terms t
            where t.endGen is null and t.department = LEADERSHIP
              and t.title in (PRESIDENT, VICE_PRESIDENT)
              and m.approval = APPROVED and m.status <> WITHDRAWN
            """)
    List<String> findSignupNotificationRecipientIds();

    @Query("select m.id from Member m where m.status = :status")
    List<String> findIdsByStatus(@Param("status") MemberStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id in :ids order by m.id")
    List<Member> findNotificationRecipientsForUpdate(@Param("ids") Collection<String> ids);
}
