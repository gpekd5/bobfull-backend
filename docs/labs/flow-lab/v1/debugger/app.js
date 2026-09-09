/* 기준 develop SHA b37b9ee828b26cae09e18220e78706bb119a1a4e의 실제 Java 코드·테스트를 재생한다. 실제 JVM, MySQL, PortOne 호출은 없다. */
const SHA = "b37b9ee828b26cae09e18220e78706bb119a1a4e";
const FILES = {
  controller:{path:"src/main/java/com/bobfull/payment/controller/PaymentController.java", name:"PaymentController", method:"complete", code:`@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentCompletionService paymentCompletionService;
    private final PaymentQueryService paymentQueryService;

    public PaymentController(PaymentCompletionService paymentCompletionService, PaymentQueryService paymentQueryService) {
        this.paymentCompletionService = paymentCompletionService;
        this.paymentQueryService = paymentQueryService;
    }

    @PostMapping("/{paymentId}/complete")
public ApiResponse<PaymentCompletionResponse> complete(@AuthenticationPrincipal AuthMember authMember, @PathVariable String paymentId) {
    PaymentCompletionTransactionService.PaymentCompletionResult result =
            paymentCompletionService.complete(paymentId, authMember.id());
    return ApiResponse.success(PaymentCompletionResponse.from(result.payment(), result.reservationId(), result.participationId()));
}
}`},
  service:{path:"src/main/java/com/bobfull/payment/service/PaymentCompletionService.java", name:"PaymentCompletionService", method:"complete / completeVerified", code:`public PaymentCompletionResult complete(String paymentId, Long memberId) {
    Payment payment = paymentRepository.findByPaymentId(paymentId).orElseThrow(...);
    if (!payment.isOwnedBy(memberId)) throw new CustomException(PAYMENT_ACCESS_DENIED);
    return completeVerified(paymentId, payment, memberId);
}
private PaymentCompletionResult completeVerified(String paymentId, Payment payment, Long memberId) {
    if (payment.getStatus() == PaymentStatus.PAID) return new PaymentCompletionResult(...);
    if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.EXPIRED) throw new CustomException(...);
    PortOnePayment external = portOnePaymentReader.read(paymentId);
    if (!paymentId.equals(external.paymentId()) || !external.paid() || external.amount() == null
            || payment.getAmount().compareTo(external.amount()) != 0
            || !Payment.CURRENCY_KRW.equals(external.currency()) || !payment.getCurrency().equals(external.currency())) throw new CustomException(...);
    return completeAfterExternalPaid(paymentId, memberId);
}`},
  portone:{path:"src/main/java/com/bobfull/payment/adapter/PortOneSdkPaymentReader.java",name:"PortOneSdkPaymentReader",method:"read",code:`@Component
public class PortOneSdkPaymentReader implements PortOnePaymentReader {
    private final PortOneClient portOneClient;

    public PortOneSdkPaymentReader(PortOneClient portOneClient) {
        this.portOneClient = portOneClient;
    }

    @Override
public PortOnePayment read(String paymentId) {
    Payment payment = portOneClient.getPayment().getPayment(paymentId).join();
    if (payment instanceof PaidPayment paidPayment) {
        return new PortOnePayment(paidPayment.getId(), true,
                BigDecimal.valueOf(paidPayment.getAmount().getTotal()), paidPayment.getCurrency().getValue());
    }
    return new PortOnePayment(paymentId, false, null, null);
}
}`},
  transaction:{path:"src/main/java/com/bobfull/payment/service/PaymentCompletionTransactionService.java",name:"PaymentCompletionTransactionService",method:"complete",code:`@Transactional
public PaymentCompletionResult complete(String paymentId, Long memberId) {
    Payment payment = paymentRepository.findWithLockByPaymentId(paymentId).orElseThrow(...);
    if (memberId != null && !payment.isOwnedBy(memberId)) throw new CustomException(PAYMENT_ACCESS_DENIED);
    if (payment.getStatus() == PaymentStatus.PAID) return new PaymentCompletionResult(payment, payment.getReservationId(), payment.getReservationParticipantId());
    if (payment.getStatus() == PaymentStatus.EXPIRED) throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
    if (payment.getStatus() != PaymentStatus.READY) throw new CustomException(PAYMENT_VERIFICATION_FAILED);
    Instant now = clock.instant();
    if (!payment.getExpiresAt().isAfter(now)) throw new PaymentExpiredException(payment.getStatus(), payment.getExpiresAt());
    payment.complete(now);
    ReservationConfirmationResult result = reservationConfirmationPort.confirm(payment);
    payment.attachReservationConfirmation(result.reservationId(), result.participationId());
    return new PaymentCompletionResult(payment, result.reservationId(), result.participationId());
}`},
  payment:{path:"src/main/java/com/bobfull/payment/entity/Payment.java",name:"Payment",method:"complete / attachReservationConfirmation",code:`public boolean isOwnedBy(Long memberId) {
    return this.memberId.equals(memberId);
}

public PaymentStatus getStatus() {
    return status;
}

public Instant getExpiresAt() {
    return expiresAt;
}

public void complete(Instant paidAt) {
    if (status != PaymentStatus.READY) throw new IllegalStateException("READY Payment만 완료할 수 있습니다.");
    status = PaymentStatus.PAID;
    this.paidAt = paidAt;
}
public void attachReservationConfirmation(Long reservationId, Long reservationParticipantId) {
    if (reservationId == null || reservationParticipantId == null) throw new IllegalArgumentException(...);
    this.reservationId = reservationId;
    this.reservationParticipantId = reservationParticipantId;
}`},
  reservation:{path:"src/main/java/com/bobfull/reservation/service/ReservationConfirmationService.java",name:"ReservationConfirmationService",method:"confirm",code:`/** PaymentCompletionTransactionService의 트랜잭션에 참여한다. */
@Transactional(propagation = Propagation.MANDATORY)
public ReservationConfirmationResult confirm(PaymentPurpose purpose, Long timeSlotId, Long reservationId, Long memberId, Integer partySize) {
    Reservation reservation = (purpose == PaymentPurpose.CREATE)
            ? reservationRepository.save(Reservation.create(timeSlotId, memberId))
            : findReservationWithLockOrThrow(reservationId);
    ReservationParticipant participant = reservationParticipantRepository.save(
            ReservationParticipant.create(reservation.getId(), memberId, partySize));
    updateReservationStatus(reservation, timeSlotId);
    return new ReservationConfirmationResult(reservation.getId(), participant.getId());
}

private Reservation findReservationWithLockOrThrow(Long reservationId) {
    return reservationRepository.findWithLockById(reservationId)
            .orElseThrow(() -> new CustomException(ReservationErrorCode.RESOURCE_NOT_FOUND));
}`},
  repository:{path:"src/main/java/com/bobfull/payment/repository/PaymentRepository.java",name:"PaymentRepository",method:"findWithLockByPaymentId",code:`public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByPaymentIdAndMemberId(String paymentId, Long memberId);

    Page<Payment> findAllByMemberId(Long memberId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByPaymentId(String paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockById(Long id);
}`}
};
const base={payment:"READY",paidAt:"-",expiresAt:"2026-09-01T00:00:00Z",reservationId:"-",participantId:"-",tx:"NONE",paymentLock:"NONE",reservationLock:"NONE",reservation:"없음",reservationStatus:"-",recruitment:"-",participants:"0 / 4",participant:"없음",portone:"호출 전",mysql:"대기"};
function step(id,title,file,opts={}){const inferred=id==="http"||id==="response"||id==="error"?"FRAMEWORK":file==="repository"?"DATABASE":file==="portone"?"EXTERNAL":["tx","commit","rollback","test"].includes(id)?"TRANSACTION":"PROJECT_CODE";return {id,title,file,type:opts.type||inferred,stack:opts.stack||[],change:opts.change||{},facts:opts.facts||{},summary:opts.summary||title,evidence:opts.evidence||"기준 SHA 실제 코드",reason:opts.reason||"코드의 실행 순서를 정적으로 재생합니다.",skipped:opts.skipped||"없음"};}
const stack={c:["PaymentController.complete"],s:["PaymentController.complete","PaymentCompletionService.complete"],p:["PaymentController.complete","PaymentCompletionService.completeVerified","PortOneSdkPaymentReader.read"],t:["PaymentController.complete","PaymentCompletionService.completeAfterExternalPaid","PaymentCompletionTransactionService.complete"],r:["PaymentController.complete","PaymentCompletionService.completeAfterExternalPaid","PaymentCompletionTransactionService.complete","ReservationConfirmationAdapter.confirm","ReservationConfirmationService.confirm"]};
function normal(purpose){const join=purpose==="JOIN";return [
 step("http","HTTP POST /complete 수신","controller",{stack:[],facts:{mysql:"요청 수신"},summary:"Spring MVC가 인증된 요청을 Controller로 전달합니다.",evidence:"PaymentController.java:24-29"}),
 step("controller","PaymentController.complete 위임","controller",{stack:stack.c,summary:"Controller는 인증된 memberId와 paymentId를 Service에 전달합니다.",evidence:"PaymentController.java:24-29"}),
 step("lookup","Payment 사전 조회 (락 없음)","service",{stack:stack.s,change:{mysql:"SELECT Payment (락 없음)"},summary:"Payment 존재 여부를 먼저 확인합니다. 이 조회는 비관적 락이 아닙니다.",evidence:"PaymentCompletionService.java:31-36"}),
 step("owner","소유권 및 PAID 조기 반환 검사","service",{stack:stack.s,summary:"소유자만 진행할 수 있으며 PAID면 PortOne과 트랜잭션 없이 기존 결과를 반환합니다.",evidence:"PaymentCompletionService.java:34, 55"}),
 step("portone","PortOne 결제 단건 조회","portone",{stack:stack.p,change:{portone:"PAID 응답 수신"},summary:"외부 SDK 호출은 트랜잭션과 Payment 행 락 밖에서 수행됩니다.",evidence:"PortOneSdkPaymentReader.java:19-27"}),
 step("verify","외부 상태·금액·통화 검증","service",{stack:stack.s,summary:"paymentId, PAID, 금액, KRW, 내부 통화를 모두 비교합니다.",evidence:"PaymentCompletionService.java:60-65"}),
 step("tx","@Transactional 진입","transaction",{stack:stack.t,change:{tx:"ACTIVE",mysql:"트랜잭션 시작"},summary:"짧은 내부 트랜잭션에서 상태 전이와 예약 확정을 묶습니다.",evidence:"PaymentCompletionTransactionService.java:28"}),
 step("lock","Payment PESSIMISTIC_WRITE 획득","repository",{stack:stack.t,change:{paymentLock:"ACQUIRED",mysql:"SELECT ... FOR UPDATE (JPA 락 의도)"},summary:"Payment 행을 잠근 뒤 현재 상태를 다시 읽습니다. 실제 SQL 문자열이 아닌 JPA 락 의도입니다.",evidence:"PaymentRepository.java:31-32"}),
 step("recheck","락 안에서 상태·만료 재검증","transaction",{stack:stack.t,summary:"PAID면 기존 결과를 반환하고, 만료·비READY면 예외를 던집니다.",evidence:"PaymentCompletionTransactionService.java:34-48"}),
 step("paid","Payment READY → PAID","payment",{stack:stack.t,change:{payment:"PAID",paidAt:"clock.instant() (시뮬레이션)",mysql:"Payment dirty checking 대기"},summary:"READY일 때만 paidAt과 함께 PAID로 전이합니다.",evidence:"Payment.java:192-197"}),
 step("confirm",join?"JOIN Reservation 잠금·Participant 저장":"CREATE Reservation·최초 Participant 저장","reservation",{stack:stack.r,change:{reservationLock:join?"ACQUIRED":"NONE",reservation:join?"기존 Reservation":"새 Reservation",participant:"생성·저장",participants:join?"3 / 4":"3 / 4",reservationStatus:"CONFIRMED",recruitment:"OPEN",mysql:join?"Reservation 잠금 + Participant INSERT":"Reservation INSERT + Participant INSERT"},summary:join?"JOIN은 기존 Reservation을 비관적 락으로 읽고 Participant를 추가합니다.":"CREATE는 Reservation과 첫 Participant를 생성합니다.",evidence:"ReservationConfirmationService.java:47-58"}),
 step("attach","Payment에 Reservation 결과 ID 연결","payment",{stack:stack.t,change:{reservationId:join?"기존 Reservation ID":"새 Reservation ID",participantId:"새 Participant ID"},summary:"ReservationConfirmationPort 결과 ID를 Payment에 연결합니다.",evidence:"PaymentCompletionTransactionService.java:50-53"}),
 step("commit","Commit 및 락 해제","transaction",{stack:stack.t,change:{tx:"COMMITTED",paymentLock:"RELEASED",reservationLock:join?"RELEASED":"NONE",mysql:"COMMIT"},summary:"Payment, Reservation, Participant 변경이 한 트랜잭션으로 확정됩니다.",evidence:"PaymentReservationConfirmationTransactionIntegrationTest CREATE/JOIN 완료 테스트"}),
 step("response","HTTP 200 응답","controller",{stack:stack.c,summary:"PaymentCompletionResponse를 ApiResponse.success로 반환합니다.",evidence:"PaymentController.java:27-29"})];}
function duplicate(mode){if(mode==="before")return [normal("CREATE")[0],normal("CREATE")[1],step("lookup","Payment 사전 조회: 이미 PAID","service",{stack:stack.s,change:{mysql:"SELECT Payment (락 없음)",payment:"PAID",paidAt:"기존 paidAt",reservationId:"10",participantId:"20"},summary:"사전 조회에서 PAID를 확인하면 즉시 기존 결과를 반환합니다.",evidence:"PaymentCompletionServiceTest.이미_완료된_Payment은_PortOne_재조회와_예약확정_트랜잭션을_수행하지_않는다",skipped:"PortOne, 트랜잭션, Payment 락, ReservationConfirmationPort 모두 호출하지 않음"}),step("return","기존 완료 결과 HTTP 200","controller",{stack:stack.c,summary:"멱등 응답입니다. 새로운 Participant를 만들지 않습니다.",evidence:"PaymentCompletionIdempotencyIntegrationTest.완료_API를_반복_호출해도..."})];
 const x=normal("CREATE").slice(0,8); x.push(step("locked-paid","경합 후 락 안에서 PAID 확인","transaction",{stack:stack.t,change:{payment:"PAID",paidAt:"다른 요청의 paidAt",reservationId:"10",participantId:"20"},summary:"이 요청은 사전 조회와 PortOne 검증은 했지만, 락 획득 후 PAID를 보고 기존 결과를 반환합니다.",evidence:"PaymentCompletionTransactionServiceTest.잠금_획득후_이미_PAID인_Payment은_기존_결과를_반환하고_Port를_호출하지_않는다",skipped:"ReservationConfirmationPort 재호출 및 새 Participant 생성 없음"}),step("commit","읽기 트랜잭션 Commit·락 해제","transaction",{stack:stack.t,change:{tx:"COMMITTED",paymentLock:"RELEASED",mysql:"COMMIT"},summary:"이 요청은 내부 변경 없이 종료합니다.",evidence:"PaymentCompletionIdempotencyIntegrationTest 동시 API·웹훅 테스트"}),step("return","기존 완료 결과 HTTP 200","controller",{stack:stack.c,summary:"PortOne은 이 요청에서도 사전 검증 때문에 호출될 수 있으나 예약 확정은 한 번만 실행됩니다.",evidence:"PaymentCompletionIdempotencyIntegrationTest"}));return x;}
function expired(){const x=normal("CREATE").slice(0,8);x.push(step("expired","락 안에서 expiresAt 재검증 실패","transaction",{stack:stack.t,change:{paymentLock:"ACQUIRED",mysql:"Payment 행 잠금 유지"},summary:"expiresAt이 now보다 이후가 아니면 PaymentExpiredException을 던집니다. Payment는 READY 그대로입니다.",evidence:"PaymentCompletionTransactionService.java:45-48 / PaymentCompletionTransactionServiceTest.락_획득_대기중_만료된_Payment...",skipped:"Payment.complete, ReservationConfirmationPort, ID 연결 모두 실행하지 않음"}),step("rollback","Rollback·락 해제","transaction",{stack:stack.t,change:{tx:"ROLLED_BACK",paymentLock:"RELEASED",mysql:"ROLLBACK",portone:"PAID 응답은 이미 수신됨"},summary:"외부 PAID와 내부 만료가 충돌합니다. 서비스는 보상 필요 로그를 남기고 예외를 다시 던집니다.",evidence:"PaymentCompletionService.java:70-77",reason:"실제 환불 실행 파이프라인은 이 화면에 포함하지 않습니다."}),step("error","오류 응답 (정확한 HTTP 형식은 전역 예외 처리 확인 필요)","controller",{stack:stack.c,summary:"PAYMENT_EXPIRED 예외 경로입니다. 응답 세부 형식은 여기서 추론하지 않습니다.",evidence:"PaymentExpiredException.java"}));return x;}
function rollback(){const x=normal("CREATE").slice(6,10);x.unshift(step("test","테스트가 트랜잭션 서비스를 직접 호출","transaction",{stack:["PaymentCompletionTransactionService.complete"],change:{mysql:"테스트 실패 주입"},summary:"이 시나리오는 운영 장애를 만들어 낸 것이 아니라, 통합 테스트의 저장 실패 주입으로 Rollback 범위를 검증합니다.",evidence:"PaymentReservationConfirmationTransactionIntegrationTest.FailureInjectionConfiguration"}));x.push(step("fail-save","Reservation 또는 Participant 저장 실패","reservation",{stack:["PaymentCompletionTransactionService.complete","ReservationConfirmationAdapter.confirm","ReservationConfirmationService.confirm"],change:{reservation:"메모리 임시 생성",participant:"메모리 임시 생성",mysql:"INSERT 실패 (테스트 주입)"},summary:"Payment는 이미 메모리에서 PAID로 바뀌었을 수 있으나, 같은 트랜잭션의 저장 예외가 발생합니다.",evidence:"PaymentReservationConfirmationTransactionIntegrationTest.예약_저장_실패 / Participant_저장_실패"}),step("rollback","Rollback 후 DB 최종 상태 복원","transaction",{stack:["PaymentCompletionTransactionService.complete"],change:{tx:"ROLLED_BACK",paymentLock:"RELEASED",payment:"DB: READY (메모리 임시 PAID 취소)",paidAt:"DB: -",reservation:"DB: 없음",participant:"DB: 없음",mysql:"ROLLBACK"},summary:"메모리 임시 상태와 DB 최종 상태를 구분합니다. 테스트는 Payment READY 및 Reservation·Participant 0건을 검증합니다.",evidence:"PaymentReservationConfirmationTransactionIntegrationTest.assertPaymentAndReservationRolledBack",skipped:"Commit과 정상 HTTP 200은 실행하지 않음"}));return x;}
const scenarios={normal:{label:"정상 결제 완료",variants:["CREATE","JOIN"],make:normal,note:"실제 완료 API 흐름"},duplicate:{label:"중복 결제 완료 요청",variants:["락 이전 PAID","락 이후 경합 중 PAID"],make:v=>duplicate(v==="락 이전 PAID"?"before":"after"),note:"멱등성 경로 비교"},expired:{label:"내부 Payment 만료",variants:["READY 만료 재검증"],make:expired,note:"외부 PAID 후 내부 만료 경계"},rollback:{label:"Reservation·Participant 저장 실패",variants:["테스트 실패 주입"],make:rollback,note:"통합 테스트의 Rollback 검증"}};
let scenarioKey="normal",variant="CREATE",steps=[],index=0,manualFile=null,timer=null;
const $=id=>document.getElementById(id);function stateAt(){let s={...base};for(let i=0;i<=index;i++)Object.assign(s,steps[i].change);return s;}
const CURRENT_LINES={controller:13,lookup:2,owner:3,portone:10,verify:10,tx:2,lock:3,recheck:5,paid:12,confirm:4,attach:18,commit:15,"locked-paid":5,expired:10,test:2,"fail-save":7,rollback:10,return:7};
function renderCode(){const st=steps[index],active=manualFile||st.file,f=FILES[active],current=CURRENT_LINES[st.id]||1;$("file-tabs").innerHTML=Object.entries(FILES).map(([k,v])=>`<button class="file-tab ${k===active?"active":""}" data-file="${k}" title="${v.path}">${v.name}</button>`).join("");if(st.type==="FRAMEWORK"&&!manualFile){$("code-head").textContent="Framework Step · 프로젝트 Java 코드 실행 전";$("code-view").innerHTML=`<div class="framework-code"><strong>Framework dispatch</strong>Spring MVC가 요청을 PaymentController.complete()로 전달합니다.<br>현재 프로젝트 Java 코드 실행 없음.<em>다음 실행 위치: ${FILES.controller.path}:13<br>PaymentController.complete()</em></div>`;}else{$("code-head").textContent=`${f.path}  ·  ${f.name}.${f.method}`;$("code-view").innerHTML=f.code.split("\n").map((l,i)=>{const line=i+1,style=active===st.file?(line===current?"current":line<current?"past":""):"";return `<span class="line ${style}">${escapeHtml(l)}</span>`;}).join("");}document.querySelectorAll(".file-tab").forEach(b=>b.onclick=()=>{manualFile=b.dataset.file;renderCode();});}
function escapeHtml(s){return s.replace(/[&<>]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;"}[c]));}
function renderFlow(){const st=steps[index],start=Math.max(0,index-2),end=Math.min(steps.length,index+3),near=steps.slice(start,end);$("mini-map").innerHTML=steps.map((s,i)=>`<button class="mini-step ${i<index?"done":""} ${i===index?"current":""} ${i<=index&&isFailureStep(s)?"fail":""}" data-step="${i}" title="${i+1}. ${s.title}">${i+1}</button>`).join("");$("flowchart").innerHTML=near.map((s,offset)=>{const i=start+offset;return `<button class="focus-node ${i<index?"done":""} ${i===index?"current":""} ${i<=index&&isFailureStep(s)?"fail":""}" data-step="${i}"><span class="flow-step">${i+1}. ${flowName(s)}</span><span class="flow-class">${s.file?FILES[s.file].name:s.type}</span></button>`;}).join("");document.querySelectorAll(".mini-step,.focus-node").forEach(n=>n.onclick=()=>go(+n.dataset.step));$("flow-caption").textContent=`Step ${index+1} / ${steps.length}`;$("focus-type").textContent=st.type.replace("_"," ");}
function isFailureStep(s){return ["expired","fail-save","rollback","error"].includes(s.id);}
function flowName(s){const names={http:"HTTP 요청",controller:"Controller 진입",lookup:"Payment 조회",owner:"소유권·PAID 검사",portone:"PortOne 조회",verify:"외부 결제 검증",tx:"트랜잭션 시작",lock:"Payment 락",recheck:"락 내부 재검증",paid:"READY → PAID",confirm:"Reservation 확정",attach:"결과 ID 연결",commit:"Commit",response:"HTTP 응답","locked-paid":"락 안 PAID 확인",expired:"만료 예외",test:"테스트 실패 주입","fail-save":"저장 예외",rollback:"Rollback",return:"기존 결과 반환"};return names[s.id]||s.title;}
function renderStack(){const frames=steps[index].stack;$("call-stack").innerHTML=frames.length?`<li class="framework">Spring MVC dispatch<small>framework frame</small></li>${frames.map((x,i)=>`<li class="${i===frames.length-1?"current":""}">${"└─ ".repeat(i+1)}${x}<small>${i===frames.length-1?"현재 frame":"호출자 frame"}</small></li>`).join("")}`:`<li class="framework">Spring MVC dispatch<small>현재 프로젝트 Java 코드 실행 전</small></li>`;}
function renderState(){const s=stateAt(),groups={Payment:[["status",s.payment],["paidAt",s.paidAt],["expiresAt",s.expiresAt],["reservationId",s.reservationId],["participationId",s.participantId]],Transaction:[["상태",s.tx],["Payment Lock",s.paymentLock],["Reservation Lock",s.reservationLock]],Reservation:[["존재",s.reservation],["status",s.reservationStatus],["recruitment",s.recruitment],["participants / capacity",s.participants]],Participant:[["생성·저장",s.participant]],External:[["PortOne",s.portone],["MySQL",s.mysql]]};$("runtime-state").innerHTML=Object.entries(groups).map(([n,rows])=>`<section class="${n.toLowerCase()}"><h3>${n}</h3>${rows.map(([k,v])=>`<div><b>${k}</b><span class="${String(v).includes("→")||String(v).includes("DB:")?"changed":""}">${v}</span></div>`).join("")}</section>`).join("");}
function renderTimeline(){const max=steps.length-1;$("timeline").max=max;$("timeline").value=index;$("step-count").textContent=`Step ${index+1} / ${steps.length} · ${flowName(steps[index])}`;$("timeline-labels").innerHTML=steps.map((s,i)=>`<button class="${i===index?"active":""}" data-step="${i}" title="${s.title}">${i+1}</button>`).join("");document.querySelectorAll("#timeline-labels button").forEach(b=>b.onclick=()=>go(+b.dataset.step));}
function renderDetail(){const s=steps[index],code=s.type==="FRAMEWORK"?"Framework dispatch (프로젝트 코드 실행 전)":FILES[s.file].name+"."+FILES[s.file].method;$("detail").innerHTML=`<div class="detail-body"><p class="summary"><strong>현재 단계 ${index+1}. ${flowName(s)}</strong> — ${s.summary}</p><div class="facts"><div class="fact"><strong>실행 코드</strong>${code}</div><div class="fact"><strong>상태 변화</strong>${Object.keys(s.change).length?Object.entries(s.change).map(([k,v])=>`${k}: ${v}`).join(", "):"없음"}</div><div class="fact"><strong>실행되지 않는 작업</strong>${s.skipped}</div></div><details><summary>실행 근거·설계 관찰</summary><p>${s.evidence}</p><p>${s.reason} 실제 소요시간은 <strong>측정 전</strong>입니다.</p></details></div>`;}
function render(){renderCode();renderFlow();renderStack();renderState();renderTimeline();renderDetail();$("prev").disabled=index===0;$("next").disabled=index===steps.length-1;}
function go(i){index=Math.max(0,Math.min(i,steps.length-1));manualFile=null;render();if(index===steps.length-1)stop();}
function setScenario(){scenarioKey=$("scenario").value;const cfg=scenarios[scenarioKey];$("variant").innerHTML=cfg.variants.map(v=>`<option>${v}</option>`).join("");variant=cfg.variants[0];$("scenario-note").textContent=cfg.note;restart();}
function restart(){stop();variant=$("variant").value;steps=scenarios[scenarioKey].make(variant);index=0;manualFile=null;render();}
function stop(){if(timer){clearInterval(timer);timer=null;}}
$("scenario").innerHTML=Object.entries(scenarios).map(([k,v])=>`<option value="${k}">${v.label}</option>`).join("");$("scenario").onchange=setScenario;$("variant").onchange=restart;$("timeline").oninput=e=>go(+e.target.value);$("prev").onclick=()=>go(index-1);$("next").onclick=()=>go(index+1);$("reset").onclick=()=>go(0);$("current-code").onclick=()=>{manualFile=null;renderCode();$("code-view").scrollTop=0;};$("auto").onclick=()=>{stop();timer=setInterval(()=>{if(index>=steps.length-1)stop();else go(index+1);},1100);};$("pause").onclick=stop;setScenario();
