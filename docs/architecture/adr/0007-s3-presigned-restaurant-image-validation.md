# ADR 0007: S3 Presigned URL 식당 이미지 검증 구조

- 상태: `Accepted`
- 작성일: `2026-08-04`
- 관련 Issue·PR: #114

## 배경

식당 이미지는 OWNER가 업로드하지만 백엔드 서버가 이미지 바이너리를 직접 수신하면 API 서버 부하와 파일 검증 책임이 커진다. V1에서는 S3 Presigned URL을 사용해 클라이언트가 직접 업로드하고, 백엔드는 Object Key와 검증 결과만 다룬다.

## 문제

Presigned PUT URL은 클라이언트가 S3에 직접 객체를 만들 수 있게 하므로, 업로드 객체가 실제 허용 이미지인지 검증하고 임시 객체와 최종 객체 경계를 분리해야 한다. 또한 식당 등록·수정 시 검증되지 않은 temp 객체나 다른 OWNER 경로의 객체가 연결되면 안 된다.

## 고려한 대안

- Spring Boot가 Multipart 파일을 직접 받고 S3에 업로드한다.
- S3 Presigned URL만 발급하고 별도 검증 없이 final Key를 저장한다.
- S3 temp 업로드 후 Lambda가 검증하고 final Key로 승격한다.

## 결정

S3 Presigned PUT URL은 `temp/restaurants/{ownerId}/{uuid}.{extension}` 경로로 발급한다. S3 ObjectCreated 이벤트가 Java Lambda를 실행하고, Lambda는 경로·확장자·Content-Type·파일 크기·파일 시그니처를 검증한다. 검증 성공 시 `restaurants/{ownerId}/{uuid}.{extension}`로 복사하고 temp 객체를 삭제한다. 검증 실패 시 temp 객체를 삭제한다.

Spring Boot는 `restaurant.image_key`에 최종 Object Key만 저장한다. 조회 응답의 `imageUrl`은 Presigned GET URL로 생성한다. 별도 상태 조회 API는 두지 않고, 식당 등록·수정 시 최종 객체 존재 여부를 확인한다.

## 선택 이유

- API 서버가 이미지 바이너리 전송 경로에서 빠져 요청 부하를 줄인다.
- 검증 전 temp 경로와 검증 후 final 경로를 분리해 DB에 검증 완료 객체만 연결한다.
- Lambda 실패는 AWS 기본 재시도와 CloudWatch Logs로 관찰할 수 있고, DLQ는 운영 고도화 시 추가할 수 있다.
- Spring Boot와 Lambda 코드는 같은 Gradle 저장소에 두되 별도 서브프로젝트로 분리해 Spring 의존성을 Lambda에 끌고 가지 않는다.

## 장점

- 업로드 트래픽이 S3로 직접 흐른다.
- 저장값은 Object Key 하나로 단순하다.
- 이미지 URL 만료 시간을 Presigned GET URL로 제어할 수 있다.
- 이미지 교체 시 새 Key 반영 성공 후 기존 객체를 삭제할 수 있다.

## 단점과 위험

- S3 버킷 CORS, lifecycle, event notification, Lambda IAM 권한을 수동으로 정확히 설정해야 한다.
- 상태 조회 API가 없으므로 클라이언트는 업로드 직후 식당 등록·수정 실패를 통해 아직 검증 전인지 알 수 있다.
- Lambda 검증 실패 상세 사유는 클라이언트에 직접 전달되지 않고 CloudWatch Logs에서 확인한다.

## 검증 방법

- Spring Boot 단위 테스트로 확장자·Content-Type·크기·final Key 소유자 경로 검증을 확인한다.
- Spring Boot WebMvc 테스트로 OWNER 업로드 URL 발급과 MEMBER 접근 차단을 확인한다.
- Lambda 단위 테스트로 JPEG/PNG 시그니처 감지, temp Key 해석, 유효 객체 승격, 실패 객체 삭제, S3 인프라 예외 전파를 확인한다.
- Gradle `test`와 `clean check`로 메인 애플리케이션과 Lambda 서브프로젝트를 함께 검증한다.

## 후속 작업

- 운영에서 DLQ 또는 실패 알림이 필요하면 별도 Issue로 Lambda 비동기 실패 목적지를 결정한다.
- CloudFront signed URL 또는 이미지 리사이징이 필요하면 별도 ADR로 검토한다.
