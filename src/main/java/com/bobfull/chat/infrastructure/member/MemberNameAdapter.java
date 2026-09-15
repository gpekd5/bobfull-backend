package com.bobfull.chat.infrastructure.member;

import com.bobfull.chat.application.port.MemberNamePort;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberNameAdapter implements MemberNamePort {

    private final MemberRepository repository;

    @Override
    public Map<Long, String> readNames(Collection<Long> ids) {
        return repository.findAllById(ids).stream()
                .collect(Collectors.toMap(member -> member.getId(), member -> member.getName()));
    }
}
