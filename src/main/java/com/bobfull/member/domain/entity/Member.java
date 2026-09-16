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

// 인증 사용자와 역할 정보를 보관한다.
// businessNumber는 OWNER만 값을 가지며 MEMBER는 null이다.
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
