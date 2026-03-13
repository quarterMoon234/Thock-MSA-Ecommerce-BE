# Deployment Runbook

## 기준

- 운영 서버: `ubuntu@13.209.190.62`
- 배포 디렉터리: `~/deploy-docker-compose`
- 운영 방식: `docker compose`
- 외부 진입 주소
  - Swagger: `http://13.209.190.62/swagger-ui/index.html`
  - Grafana: `http://13.209.190.62/grafana/`
  - Redpanda Console: `http://13.209.190.62/redpanda/`

## 1. 자동 배포

### 트리거

- `main`, `dev` 대상으로 PR이 `merge`되면 GitHub Actions `CD` 워크플로우가 실행된다.

### 동작

1. 변경된 서비스만 감지한다.
2. Docker Hub에 `sang234/<service>:<merge_commit_sha>` 태그로 이미지 푸시한다.
3. EC2의 `~/deploy-docker-compose/release-state.env` 태그 값을 갱신한다.
4. 변경된 서비스만 `pull` 후 `up -d --no-deps` 한다.
5. 각 서비스의 `/actuator/health`를 검사한다.
6. 마지막에 `nginx -t`, `nginx -s reload`를 실행한다.

### 확인

- GitHub `Actions` 탭에서 `CD` 성공 여부 확인
- EC2에서 확인

```bash
cd ~/deploy-docker-compose
cat release-state.env
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
```

- 헬스체크

```bash
for svc in member-service:8081 product-service:8082 market-service:8083 payment-service:8084 settlement-service:8085; do
  echo "===== $svc ====="
  docker exec api-gateway sh -lc "wget -qO- http://$svc/actuator/health || true"
  echo
done
```

## 2. 수동 배포

### 언제 사용하나

- 자동 배포 실패 시
- 특정 서비스만 다시 올리고 싶을 때
- `nginx` 컨테이너 재생성이 필요할 때

### 기본 원칙

- 서버에서는 가능하면 `./compose.sh`를 사용한다.
- `release-state.env` 태그를 먼저 맞춘 뒤 배포한다.

### 전체 서비스 재기동

```bash
cd ~/deploy-docker-compose
chmod +x compose.sh
./compose.sh up -d
./compose.sh ps
```

### 특정 서비스만 새 태그로 수동 반영

예시: `api-gateway`

```bash
cd ~/deploy-docker-compose
sed -i 's|^API_GATEWAY_IMAGE_TAG=.*|API_GATEWAY_IMAGE_TAG=<NEW_TAG>|' release-state.env
./compose.sh pull api-gateway
./compose.sh up -d --no-deps api-gateway
docker inspect api-gateway --format '{{.Config.Image}}'
```

### 헬스체크

```bash
docker exec api-gateway sh -lc "wget -qO- http://127.0.0.1:8080/actuator/health"
docker exec member-service sh -lc "wget -qO- http://127.0.0.1:8081/actuator/health"
docker exec product-service sh -lc "wget -qO- http://127.0.0.1:8082/actuator/health"
docker exec market-service sh -lc "wget -qO- http://127.0.0.1:8083/actuator/health"
docker exec payment-service sh -lc "wget -qO- http://127.0.0.1:8084/actuator/health"
docker exec settlement-service sh -lc "wget -qO- http://127.0.0.1:8085/actuator/health"
```

## 3. nginx 수정

### 원칙

- `~/deploy-docker-compose/nginx/nginx.conf`를 수정한다.
- 수정 후에는 `reload`만 하지 말고 필요 시 `nginx` 컨테이너를 재생성한다.
- 실제 반영 여부는 반드시 컨테이너 내부 설정으로 확인한다.

### 현재 기준 nginx 역할

- `/grafana/` -> `grafana:3000`
- `/redpanda/` -> `redpanda-console:8090`
- 그 외 전체 -> `api-gateway:8080`
- `/actuator/` 외부 차단

### 설정 문법 검사 + reload

```bash
docker exec nginx nginx -t
docker exec nginx nginx -s reload
```

### 설정이 안 바뀐 것 같을 때

`reload`만으로 안 풀리면 `nginx` 컨테이너를 재생성한다.

```bash
cd ~/deploy-docker-compose
./compose.sh up -d --force-recreate nginx
```

### 실제 적용 설정 확인

```bash
docker exec nginx nginx -T | sed -n '1,220p'
```

Swagger 관련 특수 location이 없어야 하는 현재 기준 점검:

```bash
docker exec nginx nginx -T | grep -n 'swagger-ui' || true
```

현재 정상이라면 특별한 `swagger-ui` 전용 nginx location 없이 `location /`에서 `api-gateway`로 라우팅된다.

### 외부 확인

```bash
curl -I http://13.209.190.62/swagger-ui/index.html
curl -I http://13.209.190.62/swagger-ui.html
curl -I http://13.209.190.62/grafana/
curl -I http://13.209.190.62/redpanda/
```

기대값:

- `/swagger-ui/index.html` -> `200`
- `/swagger-ui.html` -> `/swagger-ui/index.html`로 `302`
- `/grafana/` -> `/grafana/login` 등으로 `302`
- `/redpanda/` -> `GET` 기준 동작, `HEAD`는 `405`일 수 있음

## 4. 자주 쓰는 점검 명령

```bash
cd ~/deploy-docker-compose
./compose.sh ps
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
docker logs --tail=100 nginx
docker logs --tail=100 api-gateway
```

## 5. 장애 시 우선순위

1. `docker ps`로 컨테이너 상태 확인
2. `release-state.env` 태그 확인
3. 서비스 health 확인
4. `docker exec nginx nginx -t`
5. `./compose.sh up -d --force-recreate nginx`

`swagger-ui/index.html`가 외부에서 안 뜨면, 먼저 `nginx` 컨테이너 재생성 여부부터 확인한다.
