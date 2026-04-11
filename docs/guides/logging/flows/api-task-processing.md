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
