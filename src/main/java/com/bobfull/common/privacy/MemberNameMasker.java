package com.bobfull.common.privacy;

// 회원 이름을 통계·이력 응답에 노출할 때 가운데 글자를 마스킹한다.
public final class MemberNameMasker {

    private MemberNameMasker() {
    }

    public static String mask(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "○";
        }
        return name.charAt(0) + "○".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
