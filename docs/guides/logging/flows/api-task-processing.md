# API Task Processing 로깅 필드

Phase 1 시나리오: `POST /agents/{agentName}/message:send` 블로킹 동기 모드.

## POST /agents/{agentName}/message:send (블로킹)

| 필드       | 값                     | 설명                    |
| ---------- | ---------------------- | ----------------------- |
| task_id    | UUID                   | 생성된 Task ID          |
| agent_name | 문자열                 | 대상 Agent 이름         |
| agent_id   | UUID                   | 레지스트리 resolve 결과 |
| user_id    | UUID                   | 요청 User ID            |
| outcome    | `task_completed`       | 정상 완료               |
| outcome    | `task_failed`          | Agent가 failed 반환     |
| outcome    | `task_canceled`        | Agent가 canceled 반환   |
| outcome    | `task_rejected`        | Agent가 rejected 반환   |
| outcome    | `agent_unavailable`    | Registry 미등록         |
| outcome    | `agent_timeout`        | 30초 내 Agent 무응답    |
| outcome    | `kafka_publish_failed` | Kafka publish ack 실패  |

## Kafka ResultConsumer (results.api)

| 필드    | 값                                                               | 설명                    |
| ------- | ---------------------------------------------------------------- | ----------------------- |
| task_id | UUID                                                             | 처리 대상 Task          |
| state   | `submitted`/`working`/`completed`/`failed`/`canceled`/`rejected` | 결과 상태               |
| outcome | `result_processed`                                               | 터미널 상태 처리 완료   |
| outcome | `result_processed_intermediate`                                  | 비터미널 상태 처리 완료 |
| outcome | `result_skipped_unknown`                                         | taskId 가 Mongo 에 없음 |

## POST /agents/{agentName}/message:send (returnImmediately=true 비동기)

| 필드               | 값                     | 설명                      |
| ------------------ | ---------------------- | ------------------------- |
| task_id            | UUID                   | 생성된 Task ID            |
| agent_name         | 문자열                 | 대상 Agent 이름           |
| agent_id           | UUID                   | 레지스트리 resolve 결과   |
| user_id            | UUID                   | 요청 User ID              |
| return_immediately | true                   | 비동기 분기 식별          |
| outcome            | `task_submitted`       | Kafka ack 성공, 즉시 반환 |
| outcome            | `agent_unavailable`    | Registry 미등록           |
| outcome            | `kafka_publish_failed` | Kafka publish ack 실패    |

## GET /agents/{agentName}/tasks/{taskId}

| 필드          | 값                                                               | 설명                                |
| ------------- | ---------------------------------------------------------------- | ----------------------------------- |
| task_id       | UUID                                                             | 조회 대상 Task                      |
| user_id       | UUID                                                             | 요청 User ID                        |
| current_state | `submitted`/`working`/`completed`/`failed`/`canceled`/`rejected` | 현재 상태 (성공 시)                 |
| outcome       | `task_retrieved`                                                 | 정상 조회                           |
| outcome       | `task_not_found`                                                 | Mongo 에 taskId 없음 (-32064 / 404) |
| outcome       | `task_access_denied`                                             | userId 불일치 (-32065 / 403)        |

## POST /agents/{agentName}/message:stream (SSE 스트리밍)

Phase 3 시나리오: 동일한 `submit → subscribe → Kafka publish` 흐름이지만 결과가 SSE 로 클라이언트에 흘러간다. `StreamMessageService` 가 `outcome=stream_started` 한 번만 기록하고, 이후 이벤트는 `SseBridge` 로만 전달된다 (프레임마다 WideEvent 출력하지 않음).

| 필드       | 값                     | 설명                                                 |
| ---------- | ---------------------- | ---------------------------------------------------- |
| task_id    | UUID                   | 생성된 Task ID                                       |
| agent_name | 문자열                 | 대상 Agent 이름                                      |
| agent_id   | UUID                   | 레지스트리 resolve 결과                              |
| user_id    | UUID                   | 요청 User ID                                         |
| request_id | UUID                   | 로그 correlation                                     |
| outcome    | `stream_started`       | 태스크 생성 + SSE 연결 수립 성공                     |
| outcome    | `agent_unavailable`    | Registry 미등록 (-32062 / 503, SSE 열리기 전)        |
| outcome    | `kafka_publish_failed` | Kafka publish ack 실패 (-32001 / 502, SSE 열리기 전) |

## GET /agents/{agentName}/tasks/{taskId}:subscribe (SSE 재연결)

기존 Task 에 SSE 로 재연결. Redis Stream 의 `Last-Event-ID` 오프셋 이후 이벤트를 backfill. 터미널 + grace period 경과 시 `StreamUnsupportedException` (-32067 / 410).

| 필드          | 값                            | 설명                                              |
| ------------- | ----------------------------- | ------------------------------------------------- |
| task_id       | UUID                          | 재연결 대상 Task                                  |
| user_id       | UUID                          | 요청 User ID                                      |
| last_event_id | `ms-seq` 문자열 또는 `"null"` | 클라이언트 `Last-Event-ID` 헤더 값, 없으면 `null` |
| outcome       | `stream_resubscribed`         | 정상 재연결 (subscribe 성공)                      |
| outcome       | `stream_expired`              | 스트림 만료 (-32067 / 410)                        |
| outcome       | `task_not_found`              | Mongo 에 taskId 없음 (-32064 / 404)               |
| outcome       | `task_access_denied`          | userId 불일치 (-32065 / 403)                      |

## A2AExceptionHandler 공통 필드

모든 A2AException (AgentUnavailable / AgentTimeout / KafkaPublish / TaskNotFound / TaskAccessDenied / StreamUnsupported) 은 `A2AExceptionHandler` 에서 다음 필드를 추가 기록한다:

| 필드       | 값                        |
| ---------- | ------------------------- |
| error_type | 예외 클래스 simpleName    |
| error_code | JSON-RPC 에러 코드 (음수) |
| outcome    | 위 각 엔드포인트 표 참조  |

응답 Content-Type 은 JSON-RPC envelope 을 보장하기 위해 항상 `application/json` 으로 고정된다 (SSE 클라이언트가 `Accept: text/event-stream` 만 보낸 경우에도).
