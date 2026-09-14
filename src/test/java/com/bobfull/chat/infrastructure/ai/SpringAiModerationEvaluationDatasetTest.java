package com.bobfull.chat.infrastructure.ai;

import org.junit.jupiter.api.Test;

/** 실제 Provider 호출 없이 demo2 원본 40건 Dataset의 수·분포 불변식을 확인한다. */
class SpringAiModerationEvaluationDatasetTest {
    @Test
    void demo2에서_추출한_40건_Dataset의_수와_분포가_고정되어_있다() {
        SpringAiModerationAdapterOpenAiEvaluationTest.verifyDatasetContract();
    }
}
