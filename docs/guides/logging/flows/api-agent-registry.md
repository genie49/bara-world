# API Agent Registry 로깅 필드

## POST /agents (Agent 등록)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 생성된 Agent ID |
| provider_id | UUID | 등록한 Provider ID |
| outcome | `agent_registered` / `agent_name_already_exists` | 성공/실패 |

## GET /agents (Agent 목록)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_count | 숫자 | 반환된 Agent 수 |
| outcome | `agents_listed` | 항상 성공 |

## GET /agents/{id} (Agent 상세)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 조회 대상 Agent ID |
| outcome | `agent_retrieved` / `agent_not_found` | 성공/실패 |

## GET /agents/{id}/.well-known/agent.json (Agent Card)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 조회 대상 Agent ID |
| outcome | `agent_card_retrieved` / `agent_not_found` | 성공/실패 |

## DELETE /agents/{id} (Agent 삭제)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 삭제 대상 Agent ID |
| provider_id | UUID | 삭제 요청 Provider ID |
| outcome | `agent_deleted` / `agent_not_found` | 성공/실패 |

## POST /agents/{agentName}/registry (Agent 레지스트리 등록)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | 등록된 Agent ID |
| agent_name | 문자열 | Agent 이름 |
| provider_id | UUID | Provider ID |
| outcome | `agent_registry` | 성공 시 |
| outcome | `agent_not_found` | Agent 미존재 |
| outcome | `agent_ownership_denied` | 소유권 불일치 |

## POST /agents/{agentName}/heartbeat (Heartbeat)

| 필드 | 값 | 설명 |
|------|-----|------|
| agent_id | UUID | Agent ID |
| agent_name | 문자열 | Agent 이름 |
| provider_id | UUID | Provider ID |
| outcome | `heartbeat_refreshed` | 성공 (TTL 갱신) |
| outcome | `agent_not_found` | Agent 미존재 |
| outcome | `agent_ownership_denied` | 소유권 불일치 |
| outcome | `agent_not_registered` | Registry 미등록 |

## POST /agents/{agentName}/message:send (메시지 발행)

| 필드 | 값 | 설명 |
|------|-----|------|
| task_id | UUID | 생성된 Task ID |
| agent_name | 문자열 | 대상 Agent 이름 |
| agent_id | UUID | 대상 Agent ID |
| user_id | UUID | 요청 사용자 ID |
| outcome | `task_published` | 성공 시 |
| outcome | `agent_unavailable` | Agent 비활성 |
