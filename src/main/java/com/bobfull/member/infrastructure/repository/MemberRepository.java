package com.bobfull.member.infrastructure.repository;

import com.bobfull.admin.repository.AdminMemberRepository;
import com.bobfull.member.domain.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long>, AdminMemberRepository {

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByBusinessNumber(String businessNumber);

    Optional<Member> findByEmail(String email);
}
