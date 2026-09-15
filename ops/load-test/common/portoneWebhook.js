// Issue #146 — 환불 완료 웹훅(PortOne `Transaction.Cancelled`/`Transaction.CancelPending`)을
// K6가 직접 서명해서 보낸다. `/api/webhooks/portone`은 Standard Webhooks 스펙
// (webhook-id/webhook-timestamp/webhook-signature 헤더, id.timestamp.body를 HMAC-SHA256)을
// PortOne 서버 SDK(`io.portone.sdk.server.webhook.WebhookVerifier`)가 그대로 구현하므로,
// 이 스펙에 맞춰 서명하면 실제 PortOne 없이도 웹훅 경로를 그대로 재현할 수 있다.
//
// `WebhookVerifier(String)` 생성자(PortOne 서버 SDK)는 인자가 "whsec_"로 시작하면 그 접두어를
// 떼고 나머지를 base64 디코드하고, 그렇지 않으면 문자열 전체를 그대로 base64 디코드한다(SDK jar
// 역어셈블로 확인) — 실제 HMAC 키 바이트를 구하는 절차가 설정값 형태에 따라 다르다. 예를 들어
// `application-local.yml`이 쓰는 실제 값(`whsec_...`)은 앞의 분기를, `application-performance.yml`
// (JUnit 전용, `d2hzZWNf...`로 시작)의 값은 뒤의 분기를 탄다. 아래 두 분기를 그대로 재현한다.
//
// 주의: `application-performance.yml`은 `src/test/resources` 아래에 있어 배포 가능한 jar에는
// 포함되지 않는다(JUnit 테스트 클래스패스 전용). 실제 배포된 인스턴스(로컬 docker 포함)에서 K6를
// 돌릴 때는 `SPRING_PROFILES_ACTIVE`에 `performance`가 포함돼 있어야(예: `local,performance`)
// fake Bean(`PerformanceTestRefundRequester` 등)이 활성화되고, 그 인스턴스에 실제 설정된
// `portone.webhook-secret` 값(예: local 프로파일의 `PORTONE_WEBHOOK_SECRET`)을
// `-e PORTONE_WEBHOOK_SECRET=...`로 넘겨야 한다 — 아래 기본값은 JUnit
// `application-performance.yml` 기준이라 실제 배포 환경과 다를 수 있다.
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import http from 'k6/http';
import { BASE_URL, scenarioTags } from './config.js';

const WEBHOOK_SECRET_CONFIG = __ENV.PORTONE_WEBHOOK_SECRET || 'd2hzZWNfcmVzZXJ2YXRpb24tc2VhdC1jb25jdXJyZW5jeQ==';
const WHSEC_PREFIX = 'whsec_';
const SECRET_KEY_BYTES = encoding.b64decode(
    WEBHOOK_SECRET_CONFIG.startsWith(WHSEC_PREFIX)
        ? WEBHOOK_SECRET_CONFIG.slice(WHSEC_PREFIX.length)
        : WEBHOOK_SECRET_CONFIG,
);

let webhookIdCounter = 0;

function nextWebhookId(prefix) {
    webhookIdCounter += 1;
    return `${prefix || 'evt'}-${__VU}-${__ITER}-${webhookIdCounter}`;
}

function isoNow() {
    return new Date().toISOString();
}

function signedHeaders(rawBody, idPrefix) {
    const id = nextWebhookId(idPrefix);
    const timestamp = String(Math.floor(Date.now() / 1000));
    const signedContent = `${id}.${timestamp}.${rawBody}`;
    const signatureB64 = crypto.hmac('sha256', SECRET_KEY_BYTES, signedContent, 'base64');
    return {
        'webhook-id': id,
        'webhook-timestamp': timestamp,
        'webhook-signature': `v1,${signatureB64}`,
        'Content-Type': 'application/json',
    };
}

/**
 * PerformanceTestRefundRequester(성능 프로파일 전용 fake Bean, `PerformanceTestRefundRequester.java`)가
 * 만드는 cancellationId와 반드시 같은 규칙이어야 한다 — 즉시 응답을 PROCESSING으로 받은 뒤 이
 * 웹훅으로 완료시키는 시나리오(B/C)에서 같은 Refund를 가리켜야 하기 때문이다.
 */
export function refundCancellationIdFor(paymentId) {
    return `perf-cancel-${paymentId}`;
}

function transactionCancelledPayload(type, paymentId, cancellationId) {
    return JSON.stringify({
        type,
        timestamp: isoNow(),
        data: {
            paymentId,
            storeId: 'performance-test-store',
            transactionId: `perf-tx-${paymentId}`,
            cancellationId,
        },
    });
}

/**
 * 서명까지 끝낸 `http.batch()` 호환 요청 객체를 만든다(직접 실행하지 않는다). 시나리오 C처럼
 * 즉시 응답 요청과 웹훅 요청을 진짜 동시에 쏴야 할 때, `http.batch([...])`에 그대로 끼워 넣을
 * 수 있게 하기 위함이다. 단건 호출은 `sendCancelledWebhook`/`sendCancelPendingWebhook`를 쓴다.
 */
export function cancelledWebhookRequest(scenarioName, paymentId, cancellationId, tagName, type) {
    const body = transactionCancelledPayload(type || 'Transaction.Cancelled', paymentId, cancellationId);
    return {
        method: 'POST',
        url: `${BASE_URL}/api/webhooks/portone`,
        body,
        params: {
            headers: signedHeaders(body, type === 'Transaction.CancelPending' ? 'cancel-pending' : 'cancelled'),
            tags: scenarioTags(scenarioName, { name: tagName }),
        },
    };
}

function postWebhook(scenarioName, body, idPrefix, tagName) {
    return http.post(`${BASE_URL}/api/webhooks/portone`, body, {
        headers: signedHeaders(body, idPrefix),
        tags: scenarioTags(scenarioName, { name: tagName }),
    });
}

/** `Transaction.Cancelled` 웹훅 — 환불 완료를 재현한다(시나리오 A/B/C). */
export function sendCancelledWebhook(scenarioName, paymentId, cancellationId, tagName) {
    const body = transactionCancelledPayload('Transaction.Cancelled', paymentId, cancellationId);
    return postWebhook(scenarioName, body, 'cancelled', tagName || 'portone_webhook_cancelled');
}

/** `Transaction.CancelPending` 웹훅 — PortOne이 아직 처리 중임을 알리는 중간 상태를 재현한다. */
export function sendCancelPendingWebhook(scenarioName, paymentId, cancellationId, tagName) {
    const body = transactionCancelledPayload('Transaction.CancelPending', paymentId, cancellationId);
    return postWebhook(scenarioName, body, 'cancel-pending', tagName || 'portone_webhook_cancel_pending');
}
