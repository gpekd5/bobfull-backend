package com.bobfull.chat.application.port;

import java.util.Collection;
import java.util.Map;

public interface MemberNamePort {

    Map<Long, String> readNames(Collection<Long> memberIds);
}
