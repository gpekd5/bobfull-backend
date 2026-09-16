package com.bobfull.member.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 사용자와 역할(MEMBER, OWNER, ADMIN)을 보관하는 Entity다.
 * businessNumber는 OWNER만 값을 가지며 MEMBER는 NULL이다(docs/030-data/erd.md 4.1).
 * deletedAt은 ERD에 이미 확정된 회원 탈퇴(소프트 삭제) 컬럼이지만, 실제 탈퇴 액션은 아직
 * 별도 Issue로 구현되지 않아 이 필드는 현재 항상 NULL이다(Issue #49는 조회만 담당).
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "business_number", unique = true)
    private String businessNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Member(
            String email,
            String passwordHash,
            String name,
            String phoneNumber,
            String businessNumber,
            MemberRole role
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.businessNumber = businessNumber;
        this.role = role;
    }

    public static Member createMember(String email, String passwordHash, String name, String phoneNumber) {
        return new Member(email, passwordHash, name, phoneNumber, null, MemberRole.MEMBER);
    }

    public static Member createOwner(
            String email,
            String passwordHash,
            String name,
            String phoneNumber,
            String businessNumber
    ) {
        return new Member(email, passwordHash, name, phoneNumber, businessNumber, MemberRole.OWNER);
    }

    public void updateProfile(String name, String phoneNumber) {
        this.name = name;
        this.phoneNumber = phoneNumber;
    }

}
