package com.bobfull.chat.application.port;
import java.util.Collection;
import java.util.Map;
public interface MemberNameReader { Map<Long, String> readNames(Collection<Long> memberIds); }
