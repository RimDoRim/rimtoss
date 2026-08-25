package com.rim.toss.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    List<Execution> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    // "사용자별 체결 로그" 기능에서 그대로 재사용할 수 있도록 order->account->member 경로로 조인해둔다.
    @Query("select e from Execution e where e.order.account.member.id = :memberId order by e.createdAt desc")
    List<Execution> findByMemberId(Long memberId);
}
