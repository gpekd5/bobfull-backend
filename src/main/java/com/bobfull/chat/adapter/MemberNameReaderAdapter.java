package com.bobfull.chat.adapter;
import com.bobfull.chat.port.MemberNameReader;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
@Component
public class MemberNameReaderAdapter implements MemberNameReader {
    private final MemberRepository repository;
    public MemberNameReaderAdapter(MemberRepository repository) { this.repository = repository; }
    public Map<Long, String> readNames(Collection<Long> ids) { return repository.findAllById(ids).stream().collect(Collectors.toMap(m -> m.getId(), m -> m.getName())); }
}
