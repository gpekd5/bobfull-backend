package com.bobfull.restaurantinsight.domain.policy;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// 외부 AI 전송 전 원문과 저장 전 추출값에서 개인정보·재식별 단서를 차단한다.
@Component
public class RestaurantInsightPrivacyValidator {
    // 일반 개체명 인식 없이 적용하는 정규식·키워드 기반 최소 방어선이며 모든 실명 탐지를 보장하지 않는다.
    private static final int MAX_ASPECT_CODE_POINTS = 40;

    private static final Pattern PHONE = Pattern.compile("(?:01[016789]|0[2-9][0-9]?)[ -]?\\d{3,4}[ -]?\\d{4}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern RESERVATION_NUMBER = Pattern.compile("(?i)(예약|주문)\\s*(번호|no\\.?)?\\s*[:#-]?\\s*\\d{4,}");
    private static final Pattern URL = Pattern.compile(
            "(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9][a-z0-9-]*\\.(com|net|org|io|co\\.kr|kr|me)(/\\S*)?\\b)");
    private static final Pattern ACCOUNT_IDENTIFIER = Pattern.compile("(?i)(계정|아이디|id)\\s*[:#-]?\\s*[a-z0-9_.]{3,}");

    // 계약이 허용한 문자만: Unicode 문자/숫자/공백 + 메뉴 표기용 기호 - · / & ( )
    private static final Pattern ALLOWED_ASPECT_CHARS = Pattern.compile("[\\p{L}\\p{N} \\-·/&()]+");

    // 직원 신체·복장·인상착의 묘사 + 역할 단어가 함께 나오면 "안경 쓴 남자 직원", "모자 쓴 직원"처럼
    // 특정 직원을 재식별할 수 있는 표현으로 간주한다. "직원 응대"처럼 일반화된 표현은 DESCRIPTOR가 없어 통과한다.
    private static final Pattern STAFF_ROLE = Pattern.compile("(직원|사장|매니저|알바|점장|기사)");
    private static final Pattern PERSON_DESCRIPTOR = Pattern.compile(
            "(안경|모자|수염|문신|흉터|파마|아저씨|아줌마|젊은|나이\\s*(많|든)|남자|여자|키\\s*(가|큰|작은)|머리\\s*(긴|짧은|묶은|염색))");

    // 역할 단어(직원/사장/매니저/알바/점장/기사) 앞뒤로 바로 붙은 2~4글자 한글 토큰은, 그 토큰이 "응대/친절/
    // 서비스/속도/태도" 같은 일반화된 피드백 표현이 아닌 한 임의의 실명으로 간주해 차단한다. 고정 인명
    // 목록에 의존하지 않고 "역할과 결합된 임의 이름"을 잡아내기 위한 최소 규칙이다.
    private static final Pattern ROLE_ADJACENT_TOKEN = Pattern.compile(
            "(?:(직원|사장|매니저|알바|점장|기사)\\s*([가-힣]{2,4}))|(?:([가-힣]{2,4})\\s*(직원|사장|매니저|알바|점장|기사))");
    private static final Pattern GENERIC_ROLE_FEEDBACK = Pattern.compile(
            "(응대|친절|불친절|서비스|속도|태도|매너|안내|설명|미소|배려|대응)");

    // 이름 뒤에 존칭이 붙은 형태("김철수님", "박OO씨", "이OO 기사님" 등)
    private static final Pattern HONORIFIC_NAME = Pattern.compile("[가-힣]{1,3}\\s*(씨|님|군|양|기사님|선생님|사장님)");

    // 일반 NLP 개체명 인식 없이는 임의 실명을 완벽히 걸러낼 수 없으므로, 흔히 쓰이는 예시/placeholder 인명만
    // 전수 목록으로 차단한다(정규식 기반 성씨 패턴은 "조림"/"전골" 같은 음식 단어를 오탐하므로 사용하지 않는다).
    private static final Set<String> COMMON_PLACEHOLDER_NAMES = Set.of(
            "김철수", "이영희", "박민수", "최영수", "정지훈", "홍길동", "김영희", "이철수", "박철수", "최철수", "김민수", "이민수");

    // ROLE_ADJACENT_TOKEN의 candidate가 흔한 한국 성씨로 시작할 때만 "임의 이름"으로 간주한다.
    // 역할 인접성만으로 판단하면 "짜장면 직원"처럼 역할 단어 근처의 메뉴/일반 명사까지 오탐하므로
    // (성씨로 시작하지 않는 후행 단어까지 이름으로 취급하지 않기 위한) 두 번째 조건으로 좁힌다.
    private static final Set<String> COMMON_SURNAMES = Set.of(
            "김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송",
            "전", "홍", "유", "고", "문", "양", "손", "배", "백", "허", "남", "심", "노", "하", "곽", "성", "차", "주",
            "우", "구", "민", "류", "나", "진", "지", "엄", "채", "원", "천", "방", "공", "현", "함", "변", "염", "여",
            "추", "도", "소", "석", "선", "설");

    // 원문이나 모델 추출값이 외부 전송·저장 가능한지 개인정보 기준으로 확인한다.
    public boolean containsSensitiveIdentifier(String value) {
        if (value == null) {
            return false;
        }
        return PHONE.matcher(value).find() || EMAIL.matcher(value).find() || RESERVATION_NUMBER.matcher(value).find()
                || URL.matcher(value).find() || ACCOUNT_IDENTIFIER.matcher(value).find()
                || identifiesSpecificPerson(value);
    }

    // 모델의 자유 텍스트를 허용된 길이와 문자로 정규화하고 민감 정보가 있으면 폐기한다.
    public String normalizeSafeAspect(String aspect) {
        if (aspect == null) {
            return null;
        }
        String normalized = Normalizer.normalize(aspect, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_ASPECT_CODE_POINTS) {
            return null;
        }
        if (!ALLOWED_ASPECT_CHARS.matcher(normalized).matches()) {
            return null;
        }
        if (containsSensitiveIdentifier(normalized)) {
            return null;
        }
        return normalized;
    }

    public boolean isSafeAspect(String aspect) {
        return normalizeSafeAspect(aspect) != null;
    }

    private boolean identifiesSpecificPerson(String text) {
        if (HONORIFIC_NAME.matcher(text).find()) {
            return true;
        }
        if (text.contains("직원분")) {
            return true;
        }
        if (STAFF_ROLE.matcher(text).find() && PERSON_DESCRIPTOR.matcher(text).find()) {
            return true;
        }
        if (hasRoleAdjacentName(text)) {
            return true;
        }
        String trimmed = text.trim();
        return COMMON_PLACEHOLDER_NAMES.contains(trimmed);
    }

    private boolean hasRoleAdjacentName(String text) {
        Matcher matcher = ROLE_ADJACENT_TOKEN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (candidate == null || GENERIC_ROLE_FEEDBACK.matcher(candidate).find()) {
                continue;
            }
            String firstChar = candidate.substring(0, Character.charCount(candidate.codePointAt(0)));
            if (COMMON_SURNAMES.contains(firstChar)) {
                return true;
            }
        }
        return false;
    }
}
