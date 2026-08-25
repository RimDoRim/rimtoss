package com.rim.toss.member;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rim.toss.member.dto.MemberResponse;

import lombok.RequiredArgsConstructor;

/** 관리자 전용 - 가입 신청 승인/거절. SecurityConfig에서 /api/admin/** 는 ROLE_ADMIN만 접근 가능. */
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final MemberService memberService;

    @GetMapping("/pending")
    public ResponseEntity<List<MemberResponse>> pending() {
        return ResponseEntity.ok(memberService.getPendingMembers().stream().map(MemberResponse::from).toList());
    }

    @PostMapping("/{memberId}/approve")
    public ResponseEntity<Void> approve(@PathVariable Long memberId) {
        memberService.approve(memberId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{memberId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long memberId) {
        memberService.reject(memberId);
        return ResponseEntity.ok().build();
    }
}
