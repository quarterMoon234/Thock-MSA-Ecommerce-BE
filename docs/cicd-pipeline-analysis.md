# CI/CD Pipeline Analysis

## 1. 목표

이 프로젝트의 CD는 단순히 이미지를 빌드해서 서버에 올리는 수준이 아니라, 아래 조건을 만족하도록 설계했다.

- PR merge를 기준으로 서비스별 독립 이미지를 배포한다.
- 변경된 서비스만 선택적으로 재배포한다.
- 배포 서버의 `docker compose` 설정도 레포 기준으로 동기화한다.
- 배포 후 내부 health check와 외부 smoke test를 모두 수행한다.
- 필요하면 GitHub Actions에서 수동으로 다시 실행할 수 있다.

## 2. 현재 구성 요소

핵심 파일:

- [deploy.yml](/Users/quarterMoon/Desktop/backend/beadv4_4_Refactoring_BE/.github/workflows/deploy.yml)
- [docker-compose.yml](/Users/quarterMoon/Desktop/backend/beadv4_4_Refactoring_BE/deploy-docker-compose/docker-compose.yml)
- [nginx.conf](/Users/quarterMoon/Desktop/backend/beadv4_4_Refactoring_BE/deploy-docker-compose/nginx/nginx.conf)
- [DEPLOYMENT_RUNBOOK.md](/Users/quarterMoon/Desktop/backend/beadv4_4_Refactoring_BE/docs/DEPLOYMENT_RUNBOOK.md)

배포 환경:

- GitHub Actions
- Docker Hub
- EC2
- `docker compose`
- `nginx`

## 3. 파이프라인 흐름

### 3-1. 변경 감지

`detect-changes` job은 PR에 포함된 파일 목록을 읽어 아래 두 가지를 분리한다.

- `any_service_changed`
- `any_deploy_changed`

판단 기준:

- 서비스 코드 변경:
  - `api-gateway/`
  - `member-service/`
  - `product-service/`
  - `market-service/`
  - `payment-service/`
  - `settlement-service/`
- 공통 코드 변경:
  - `common/`, `gradle/`, `buildSrc/`
  - `build.gradle`, `settings.gradle`, `gradlew` 등
- 배포 변경:
  - `deploy-docker-compose/`
  - `.github/workflows/deploy.yml`

즉 서비스 변경과 배포 변경을 별도로 판단한다.

### 3-2. 이미지 빌드

`build-and-push` job은 `any_service_changed == true`일 때만 실행된다.

- 변경된 서비스만 matrix로 빌드
- Docker Hub에 `sang234/<service>:<commit_sha>` 형식으로 푸시

태그 기준:

- PR merge 실행: `merge_commit_sha`
- 수동 실행: `github.sha`

이 방식으로 배포된 컨테이너가 정확히 어떤 커밋 이미지인지 추적할 수 있다.

### 3-3. 서버 배포

`deploy` job은 아래 조건이면 실행된다.

- `any_deploy_changed == true`
- `build-and-push`가 `success` 또는 `skipped`

중요 포인트:

- `always()`를 넣어 `build-and-push`가 skip돼도 `deploy`가 함께 skip되지 않도록 수정했다.
- 이 수정 전에는 `deploy.yml`만 바뀐 PR에서 `deploy`가 실행되지 않는 문제가 있었다.

배포 순서:

1. `deploy-docker-compose/`를 EC2 `~/deploy-docker-compose`로 동기화
2. `mysql`, `redpanda`, `redis` 선기동
3. 서비스 이미지 변경이 있으면:
   - `release-state.env` 태그 갱신
   - 변경 서비스만 `pull`
   - 변경 서비스만 `up -d --no-deps`
4. `nginx` 재기동
5. 내부 health check
6. 외부 smoke test

## 4. 배포 설정 관리 방식

이전에는 배포 서버의 `deploy-docker-compose`가 레포와 드리프트될 수 있었다.

이를 줄이기 위해:

- `deploy-docker-compose/`의 비민감 설정 파일은 Git으로 관리
- CD에서 서버로 `rsync`
- 아래 파일은 계속 서버 전용으로 유지

제외 대상:

- `.env`
- `release-state.env`
- `logs/`
- `compose.sh`
- `nginx/ssl/`
- `nginx/certbot/`

즉 배포 설정은 레포가 source of truth가 되고, 비밀값과 인증서는 서버에 남긴다.

## 5. Health Check와 Smoke Test

### 5-1. 내부 health check

변경 서비스가 있으면 컨테이너 내부에서:

- `http://127.0.0.1:<port>/actuator/health`

를 확인한다.

목적:

- 컨테이너가 떴는지
- 애플리케이션이 실제로 기동됐는지

를 확인하는 것

### 5-2. 외부 smoke test

CD 마지막에는 공개 경로를 직접 호출한다.

현재 smoke test 대상:

- `http://{EC2_HOST}/member-service/v3/api-docs`

검증 조건:

- 응답 본문에 `"openapi"` 포함

이 테스트는 아래 경로 전체를 확인한다.

- `nginx`
- `api-gateway`
- `member-service`

즉 단순 컨테이너 health check보다 실제 사용자 진입 경로에 더 가깝다.

## 6. 수동 실행 지원

현재 `deploy.yml`은 `workflow_dispatch`를 지원한다.

입력값:

- `services`

동작:

- 비워두면: deploy 설정 반영 + `nginx` 재기동 + smoke test
- 서비스명 입력:
  - 예: `member-service`
  - 해당 서비스만 빌드/배포
- `all` 입력:
  - 전체 서비스 빌드/배포

이 기능을 넣은 이유는:

- PR 없이도 deploy 설정 반영 여부를 검증하고 싶었기 때문
- 배포 실패 후 빠르게 재실행할 수 있어야 했기 때문

## 7. 이번에 실제로 겪은 문제와 수정

### 문제 1. 배포용 compose와 서버 설정 드리프트

증상:

- `member-service`가 `localhost:6379`로 Redis 연결 시도
- 운영 배포 시 설정 불일치 발생

원인:

- 서버의 `deploy-docker-compose`와 레포 설정이 달랐음

조치:

- `deploy-docker-compose/`를 CD에서 서버로 동기화
- Redis 관련 환경변수와 서비스 정의를 배포용 compose에 반영

### 문제 2. `market-service` 읽기 모델 테이블 미생성

증상:

- 로컬은 `ddl-auto:update`라 잘 되지만 운영은 `ddl-auto:validate`라 테이블이 자동 생성되지 않음

조치:

- `market-service`에 Flyway 도입
- 읽기 모델 테이블 migration 추가

### 문제 3. `build-and-push` skip 시 `deploy`도 skip

증상:

- `deploy.yml`만 바꾼 PR인데 `deploy` job이 실행되지 않음

원인:

- `deploy`가 `needs: [detect-changes, build-and-push]`에 묶여 있고
- `build-and-push` skip 상태를 통과시키지 못함

조치:

- `deploy` 조건에 `always()` 추가
- `build-and-push.result == 'success' || 'skipped'` 유지

### 문제 4. 내부 health check만으로는 공개 경로 장애를 못 잡음

조치:

- public smoke test 추가
- `nginx -> gateway -> service` 실제 진입 경로 검증

## 8. 이 파이프라인으로 보여줄 수 있는 역량

- 서비스별 독립 이미지 배포
- 변경 서비스만 선택 배포
- commit SHA 기반 이미지 추적
- 배포 설정과 애플리케이션 배포를 함께 관리
- 운영 환경과 로컬 환경 차이를 마이그레이션으로 해결
- 내부 health check + 외부 smoke test 이중 검증
- 수동 재실행 가능한 CD 설계

## 9. 남은 개선 포인트

- smoke test 대상을 1개 공개 경로에서 2~3개 핵심 API로 확장
- 실패 시 `docker logs` 요약을 더 풍부하게 남기기
- `main` 반영 이후 default branch 기준 수동 실행 UX 정리
- 필요 시 `workflow_dispatch` 입력에 `deploy_only` 같은 명시 옵션 추가

## 10. 한 줄 정리

이 CI/CD는 GitHub Actions로 변경된 서비스만 Docker Hub에 빌드/배포하고, 배포용 compose를 서버와 동기화한 뒤, 내부 health check와 외부 smoke test까지 수행하는 구조다. 단순 자동 배포가 아니라, 운영 설정 드리프트와 공개 경로 장애까지 같이 제어하는 방향으로 고도화했다.
