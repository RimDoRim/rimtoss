package com.rim.toss.member.dto;

import com.rim.toss.member.Member;
import com.rim.toss.member.MemberStatus;

public record MemberResponse(Long id, String name, String phoneNumber, MemberStatus status) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId(), member.getName(), member.getPhoneNumber(), member.getStatus());
    }
}
